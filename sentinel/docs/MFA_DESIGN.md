# Sentinel — MFA / TOTP & Security Features Design

Design for adding TOTP MFA, backup recovery codes, trusted devices, new-device alerts,
and an async security-email system to the Sentinel auth service.

This design follows the conventions already in the codebase:
- `pgx/v5` pool, raw SQL (no ORM)
- `zerolog` → stdout JSON for **application** logs (`log_type: application`)
- channel-based async **audit** logger → stdout JSON (`log_type: audit`)
- Redis sliding-window limiter (Lua, fail-open) for rate limiting
- Persona for IAM/user existence; Swarm secrets for key material
- Handlers use `c.ShouldBindBodyWith(&req, binding.JSON)` so rate-limit middleware can pre-read the body
- `GOWORK=off go build ./...` from `sentinel/`

---

## 1. System Architecture

```
                         ┌────────────────────────────────────────────────┐
                         │                  Sentinel (xN replicas)         │
   client ──TLS──▶ LB ──▶│  gin router                                     │
                         │   ├─ /otp/* /refresh /logout    (existing)      │
                         │   ├─ /mfa/*      (TOTP setup/verify/challenge)  │
                         │   ├─ /devices/*  (trusted device mgmt)          │
                         │   └─ middleware: tracing, ratelimit, auth, RBAC │
                         │                                                 │
                         │  service:  OTP │ Tokens │ MFA │ Devices │ Mail  │
                         └───────┬───────────────┬───────────────┬─────────┘
                                 │               │               │
                    ┌────────────▼───┐  ┌────────▼─────┐  ┌──────▼─────────┐
                    │  PostgreSQL    │  │    Redis     │  │  SMTP / Email  │
                    │  (shared)      │  │  rate limit  │  │  provider      │
                    │  users, mfa_*, │  │  totp replay │  └────────────────┘
                    │  devices,      │  │  attempt ctr │
                    │  email_outbox  │  └──────────────┘
                    └───────┬────────┘
                            │ NOTIFY / poll
                    ┌───────▼─────────────────────┐
                    │ Email dispatcher goroutine   │  (transactional outbox,
                    │ (leader-elected via advisory │   at-least-once, retry+backoff)
                    │  lock — Swarm-safe)          │
                    └──────────────────────────────┘

   stdout JSON (app + audit) ──▶ Fluent Bit ──▶ ES indices app-logs-* / audit-logs-*
```

**Stateless verification.** All MFA/device verification reads keys/secrets from the shared
Postgres + Redis, so any replica can serve any request. The only stateful, single-runner
component is the email dispatcher, which is made Swarm-safe via a Postgres advisory lock
(only one replica drains the outbox at a time; see §7).

---

## 2. New Configuration (`internal/config`)

Add to `Config`, loaded the same way as existing fields:

```go
// MFA
MFAEncryptionKey []byte        // 32 bytes (AES-256-GCM) from secret /run/secrets/mfa_encryption_key
RecoveryPepper   []byte        // 32 bytes from secret /run/secrets/recovery_pepper
TOTPIssuer       string        // "Tafakori" — shown in authenticator apps
TOTPSkew         uint          // allowed ± time steps (default 1 → ±30s)
MFAChallengeTTL  time.Duration // life of the intermediate mfa_challenge token (default 5m)

// Trusted devices
DeviceTrustTTL   time.Duration // default 30d (config DEVICE_TRUST_TTL_DAYS)
DeviceSecret     []byte        // HMAC key for signing device tokens (secret)

// Email
SMTPHost, SMTPUser, SMTPPass string
SMTPPort                     int
MailFrom                     string
MailMaxRetries               int           // default 5
MailRetryBackoff             time.Duration // base backoff, default 30s

// Rate limits (added to RateLimits struct)
MFAVerify     RateLimitRule // default 5 / 5m
RecoveryVerify RateLimitRule // default 5 / 15m
DeviceTrust   RateLimitRule // default 10 / 1h
```

Key material comes from **Swarm secrets**, read at startup (`os.ReadFile("/run/secrets/...")`),
never from plain env. Add to `deploy/stack.yml`:

```yaml
secrets:
  mfa_encryption_key: { external: true }
  recovery_pepper:    { external: true }
  device_secret:      { external: true }
```

Validation: fail fast in `config.Load()` if any MFA secret is missing or not 32 bytes.

---

## 3. Database Schema (`migrations/003_mfa_devices_email.sql`)

```sql
BEGIN;

-- ── Users: MFA state ─────────────────────────────────────────────────────────
-- state: 'disabled' | 'pending_setup' | 'enabled'
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS mfa_state            TEXT NOT NULL DEFAULT 'disabled',
    ADD COLUMN IF NOT EXISTS mfa_secret_enc       BYTEA,        -- AES-256-GCM(nonce||ciphertext)
    ADD COLUMN IF NOT EXISTS mfa_last_timestep    BIGINT,       -- replay guard (last accepted T)
    ADD COLUMN IF NOT EXISTS mfa_enabled_at       TIMESTAMPTZ;

ALTER TABLE users ADD CONSTRAINT chk_mfa_state
    CHECK (mfa_state IN ('disabled','pending_setup','enabled'));

-- ── Recovery codes (one row per code, hashed) ────────────────────────────────
CREATE TABLE IF NOT EXISTS mfa_recovery_codes (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash  TEXT        NOT NULL,            -- hex(sha256(pepper || code))
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- O(1) verification lookup; pepper makes the hash non-brute-forceable from a DB dump.
CREATE UNIQUE INDEX IF NOT EXISTS idx_recovery_hash    ON mfa_recovery_codes (code_hash);
CREATE        INDEX IF NOT EXISTS idx_recovery_user    ON mfa_recovery_codes (user_id) WHERE used = FALSE;

-- ── Trusted / known devices ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS devices (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    fingerprint_hash TEXT        NOT NULL,        -- sha256 of stable fingerprint inputs
    user_agent       TEXT,
    ip_address       TEXT,
    trusted          BOOLEAN     NOT NULL DEFAULT FALSE,
    trust_expires_at TIMESTAMPTZ,                 -- NULL until trusted
    last_seen        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked          BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_device_user_fp ON devices (user_id, fingerprint_hash);
CREATE        INDEX IF NOT EXISTS idx_device_user    ON devices (user_id) WHERE revoked = FALSE;

-- ── Email outbox (transactional, durable, retry-able) ────────────────────────
CREATE TABLE IF NOT EXISTS email_outbox (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      REFERENCES users(id) ON DELETE SET NULL,
    to_addr     TEXT        NOT NULL,
    template    TEXT        NOT NULL,            -- e.g. 'new_device','mfa_enabled'
    payload     JSONB       NOT NULL,            -- template variables
    status      TEXT        NOT NULL DEFAULT 'pending', -- pending|sent|failed
    attempts    INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_outbox_due ON email_outbox (next_attempt_at)
    WHERE status = 'pending';

COMMIT;
```

Notes:
- **MFA secret** stored as `BYTEA` AES-256-GCM ciphertext (nonce prepended). Decrypted only
  in-process during verification; never logged.
- **Recovery codes** stored as `sha256(pepper||code)`. SHA-256 (not bcrypt) is the right call
  here because codes are high-entropy (≥80 bits) random tokens — the pepper defeats offline
  brute force, and the unique hash index gives O(1) lookup instead of bcrypt's per-row scan.
- **Devices** keyed by `(user_id, fingerprint_hash)` so the same browser upserts rather than
  duplicating. `trusted=false` rows are "known" devices used for new-device alerts.
- **Outbox** is the durability layer: an email row is inserted in the *same transaction* as the
  security event, so it cannot be lost on crash, and is retried with backoff.

---

## 4. Package Layout

```
internal/
  mfa/
    totp.go        # secret gen, QR, code validation (pquerna/otp wrapper)
    crypto.go      # AES-256-GCM encrypt/decrypt of TOTP secret; recovery-code hashing
    service.go     # SetupMFA, VerifySetup, DisableMFA, Challenge, recovery codes
  device/
    fingerprint.go # ComputeFingerprint(ua, ip, accept headers, client hint)
    service.go     # Trust, ListTrusted, Revoke, IsTrusted, RecordSeen
    token.go       # HMAC-signed device token (issue/verify)
  mail/
    outbox.go      # Enqueue(tx, ...) — write to email_outbox in caller's tx
    dispatcher.go  # background drainer w/ advisory lock + retry/backoff
    templates/     # *.tmpl (html/template)
    smtp.go        # provider transport (net/smtp or provider SDK)
  service/         # existing — VerifyOTP gains MFA branch; add MFAStatus to AuthResult
  handler/         # existing — add mfa_handler.go, device_handler.go
  audit/           # existing — add MFA/device/email event emitters
```

`go.mod` additions: `github.com/pquerna/otp` (TOTP + QR-compatible key URLs).
QR PNG generated with `github.com/skip2/go-qrcode` from the otpauth:// URL.

---

## 5. MFA + TOTP Flow Logic

### 5.1 Enrollment

```
POST /mfa/setup            (auth required, mfa_state != enabled)
  1. generate 20-byte random secret → otp.Key (issuer, account=identifier)
  2. AES-256-GCM encrypt secret → users.mfa_secret_enc; set mfa_state='pending_setup'
  3. return { otpauth_url, qr_png_base64, secret_base32 }   # shown ONCE
  audit: mfa.setup.started

POST /mfa/verify-setup     { code }
  1. require mfa_state='pending_setup'
  2. decrypt secret; totp.Validate(code, secret, skew)
  3. on success: mfa_state='enabled', mfa_enabled_at=now, store last_timestep
  4. generate 10 recovery codes (return once), insert hashes
  5. enqueue mfa_enabled email (same tx)
  audit: mfa.enabled  (or mfa.challenge.failed on bad code)
```

### 5.2 Login with MFA (the key flow change)

The existing `POST /otp/verify` issues full tokens. With MFA we split into two stateless steps
using a short-lived **intermediate challenge token** (JWT, `typ=mfa_challenge`, 5 min, signed with
JWTSecret, carries `uid` + `sid_pending`). No server-side session state needed → Swarm-safe.

```
POST /otp/verify  { identifier, otp, device_token? }
  ├─ verify OTP (existing logic)
  ├─ load user.mfa_state
  ├─ if mfa_state != 'enabled'              → issue tokens (unchanged) + new-device check
  ├─ if device_token valid & device trusted & not expired → issue tokens (MFA bypassed)
  │                                                          audit: mfa.bypassed.trusted_device
  └─ else  → 200 { "mfa_required": true, "mfa_token": "<challenge jwt>" }
            audit: mfa.challenge.required

POST /mfa/challenge  { mfa_token, code, trust_device? }
  ├─ parse+validate mfa_token (typ=mfa_challenge, not expired)
  ├─ rate-limit per user (5/5m) + Redis attempt counter
  ├─ validate code as TOTP  OR  as recovery code
  │     TOTP: decrypt secret; reject if codeTimestep <= mfa_last_timestep (replay);
  │           totp.ValidateCustom with skew; on success persist new last_timestep
  │     recovery: sha256(pepper||code) → mark used=true (single-use, atomic UPDATE)
  ├─ on success: issueTokens(); if trust_device → create trusted device + return device_token
  └─ audit: mfa.challenge.success / mfa.challenge.failed / recovery_code.used
```

### 5.3 TOTP validation core (replay-safe)

```go
// internal/mfa/totp.go
func (s *Service) validateTOTP(ctx context.Context, u *userMFA, code string) error {
    secret, err := s.crypto.Decrypt(u.SecretEnc)   // []byte, zeroed after use
    if err != nil { return err }
    defer zero(secret)

    now := time.Now()
    // pquerna/otp ValidateCustom returns which timestep matched (via GenerateCodeCustom loop)
    ok, ts := validateWithTimestep(string(secret), code, now, s.cfg.TOTPSkew)
    if !ok {
        return ErrInvalidTOTP
    }
    // Replay guard: each timestep usable once per user.
    if u.LastTimestep != nil && ts <= *u.LastTimestep {
        return ErrTOTPReplay
    }
    return s.repo.SetLastTimestep(ctx, u.UserID, ts)
}
```

Brute-force defense (layered):
1. Per-endpoint Redis sliding window (`/mfa/challenge`, 5/5m per user + per IP).
2. Per-user attempt counter in Redis (`mfa:fails:<uid>`) reusing the existing auto-lock pattern
   (`incrementFailCount` style) — lock account after `FailLockAt` failures.
3. Replay guard above (a captured code can't be reused once its timestep passes).

### 5.4 Disable

```
POST /mfa/disable  { code | recovery_code }   (auth required)
  - require a valid current factor (don't let a stolen session disable MFA freely)
  - mfa_state='disabled', null secret + last_timestep, delete recovery codes
  - enqueue mfa_disabled email; audit: mfa.disabled
```

---

## 6. Trusted Devices & New-Device Alerts

### 6.1 Fingerprint

```go
// Stable, privacy-respecting inputs (no invasive client JS required server-side):
// user-agent + Accept-Language + a client-sent device hint header if present.
// IP is recorded but NOT part of the hash (mobile IPs change constantly).
func ComputeFingerprint(ua, acceptLang, clientHint string) string {
    h := sha256.Sum256([]byte(ua + "|" + acceptLang + "|" + clientHint))
    return hex.EncodeToString(h[:])
}
```

### 6.2 Device token (trusted-device bypass)

When a user opts to trust a device at `/mfa/challenge` (or `POST /devices/trust`), we:
1. upsert a `devices` row (`trusted=true`, `trust_expires_at=now+TTL`),
2. return a **device_token** = `deviceID.HMAC_sha256(deviceSecret, deviceID|user_id|expiry)`.

On subsequent logins the client sends `device_token`; we verify the HMAC, look up the device,
and bypass MFA only if `trusted && !revoked && trust_expires_at > now && fingerprint matches`.
HMAC means a stolen token alone is useless without a matching live DB row we control.

### 6.3 New-device detection & alert

```
during /otp/verify (and /mfa/challenge success):
  fp = ComputeFingerprint(...)
  dev, found = repo.GetByUserFingerprint(uid, fp)
  if !found:
      repo.Insert(dev{trusted:false, ...})       # "known" but untrusted
      enqueue email template=new_device {ip, geo?, ua, ts}   # same tx
      audit: login.new_device
  else:
      repo.UpdateLastSeen(dev.id, ip)
```

Geo-IP is optional: a `geoip.Lookup(ip)` hook (MaxMind GeoLite2) populates `payload.location`
if the DB file is mounted; the field is omitted otherwise. A device becomes **trusted** only
after explicit user action (`/devices/trust` or `trust_device:true` on challenge) — never
automatically.

### 6.4 API

```
POST   /devices/trust            { device_token? } → mark current device trusted, return token
GET    /devices/trusted                            → list (id, ua, ip, last_seen, expires)
DELETE /devices/trusted/:device_id                 → revoke (sets revoked=true)
```

All require `RequireAuth`; ownership enforced (`WHERE user_id = $caller`), mirroring
`RevokeSession`'s `ErrForbidden` pattern.

---

## 7. Email Notification System (async, durable, retry)

**Transactional outbox + leader dispatcher.** A channel-only approach loses mail on crash and
double-sends across replicas. Instead:

1. **Enqueue** writes a row to `email_outbox` *inside the same DB transaction* as the triggering
   security event (e.g. MFA enabled). Atomic: either both the state change and the email intent
   commit, or neither.
2. **Dispatcher** goroutine (started in `main.go`) loops:
   - acquire Postgres advisory lock `pg_try_advisory_lock(<const>)` → only one replica drains at
     a time (Swarm-safe; if that replica dies another acquires the lock).
   - `SELECT ... FROM email_outbox WHERE status='pending' AND next_attempt_at<=NOW()
      ORDER BY id FOR UPDATE SKIP LOCKED LIMIT N`
   - send via SMTP; on success `status='sent', sent_at=NOW()`.
   - on failure: `attempts++`, `next_attempt_at = NOW() + base*2^attempts` (capped), record
     `last_error`; after `MailMaxRetries` → `status='failed'` + audit `email.notification.failed`.
3. **Templates**: `html/template` files embedded via `//go:embed templates/*.tmpl`, keyed by the
   `template` column. One renderer, many events.

Events that enqueue mail: login success (optional/configurable), failed-login threshold,
new-device login, password change, MFA enabled/disabled, recovery-code used.

Every successful send emits audit `email.notification.sent`.

---

## 8. ELK Audit Logging Integration

Add to `internal/audit/audit.go` (same `emit` + channel pattern already there):

```go
const (
    EventMFASetupStarted   = "mfa.setup.started"
    EventMFAEnabled        = "mfa.enabled"
    EventMFADisabled       = "mfa.disabled"
    EventMFAChallengeOK    = "mfa.challenge.success"
    EventMFAChallengeFail  = "mfa.challenge.failed"
    EventMFABypassDevice   = "mfa.bypassed.trusted_device"
    EventRecoveryCodeUsed  = "recovery_code.used"
    EventRecoveryRegen     = "recovery_code.regenerated"
    EventDeviceTrusted     = "device.trusted"
    EventDeviceUntrusted   = "device.untrusted"
    EventLoginNewDevice    = "login.new_device"
    EventEmailSent         = "email.notification.sent"
    EventEmailFailed       = "email.notification.failed"
)
```

Each gets a typed emitter (e.g. `MFAChallengeFailed(userID, deviceID, ip, reason, traceID, requestID)`)
matching the existing `Actor`/`EventContext`/`Meta` shape, so a failed challenge logs:

```json
{
  "log_type": "audit",
  "event": "mfa.challenge.failed",
  "result": "failure",
  "actor": { "user_id": "user123", "type": "user" },
  "context": { "ip": "1.2.3.4", "device_id": "dev456" },
  "meta": { "reason": "invalid_totp" },
  "trace_id": "abc123",
  "service": "sentinel"
}
```

No new pipeline work: this rides the existing Fluent Bit → `audit-logs-YYYY.MM.DD` ES index.
**Never** log: TOTP secrets, raw recovery codes, device tokens, OTP codes (existing rule).

---

## 9. Rate Limiting Integration

Reuse the existing `RateLimiter` middleware + `ratelimit.Rule`. Wire in `main.go`:

```go
mfaGroup := r.Group("/mfa")
mfaGroup.POST("/challenge",
    rateLimiter.ByIP(rl.Rule{Limit: 10, Window: 5*time.Minute}),
    h.MFAChallenge)                                  // + per-user counter inside handler/service
authed.POST("/mfa/setup",        rateLimiter.ByUser(rl.Rule{Limit: 5,  Window: time.Hour}), h.MFASetup)
authed.POST("/mfa/verify-setup", rateLimiter.ByUser(rl.Rule{Limit: 10, Window: 10*time.Minute}), h.MFAVerifySetup)
authed.POST("/mfa/disable",      rateLimiter.ByUser(rl.Rule{Limit: 5,  Window: time.Hour}), h.MFADisable)
authed.POST("/mfa/recovery-codes/generate", rateLimiter.ByUser(rl.Rule{Limit: 3, Window: time.Hour}), h.GenRecoveryCodes)
authed.POST("/devices/trust",    rateLimiter.ByUser(rl.Rule{Limit: 10, Window: time.Hour}), h.TrustDevice)
```

`/mfa/challenge` is pre-auth (only the challenge token), so it's limited `ByIP` plus the
per-user Redis counter that drives auto-lock — covering brute force across rotating IPs.

---

## 10. Security Requirements — how each is met

| Requirement | Mechanism |
|---|---|
| TOTP secret encrypted at rest | AES-256-GCM, key from Swarm secret, `BYTEA` column, decrypt in-proc only |
| Recovery codes never plaintext | `sha256(pepper||code)`, shown once, single-use atomic `UPDATE...WHERE used=false` |
| MFA enforced after OTP step | `/otp/verify` branches to challenge token when `mfa_state='enabled'` |
| Rate-limit MFA attempts | sliding-window per IP + per-user Redis counter + account auto-lock |
| Prevent TOTP brute force | skew kept at ±1, replay guard on `mfa_last_timestep`, attempt lockout |
| Invalidate trusted devices on password/credential reset | reset path runs `UPDATE devices SET revoked=true WHERE user_id=$1` + `LogoutAll` |
| RBAC / auth on endpoints | `RequireAuth` (+ `RequireRole` for admin); ownership checks in queries |
| Stateless / horizontally scalable | challenge token is a signed JWT; all state in shared PG/Redis |

---

## 11. Implementation Plan (incremental, build-green at each step)

1. **Migration 003** + `config` secret loading + validation. `GOWORK=off go build ./...`.
2. **`internal/mfa/crypto.go`** (AES-GCM + recovery hashing) with unit tests (encrypt/decrypt roundtrip, tamper detection).
3. **`internal/mfa`** TOTP setup/verify-setup/disable + recovery codes; wire `/mfa/setup`, `/mfa/verify-setup`, `/mfa/disable`, recovery endpoints; audit events.
4. **Login flow**: challenge token + `/mfa/challenge`; branch in `VerifyOTP`/handler. Tests for replay + lockout.
5. **`internal/device`**: fingerprint, trust token, endpoints, new-device detection hook.
6. **`internal/mail`**: outbox enqueue + dispatcher + templates; wire enqueues into events.
7. **Swagger** (`swagger.json`) + `deploy/stack.yml` secrets + README updates.

Each step is independently shippable and leaves `go build ./...` / tests green.
```

---

### Open decisions worth confirming before coding
- **Recovery code hash**: SHA-256+pepper (fast, indexable — recommended for high-entropy codes) vs bcrypt (slower, scan-based). Design uses SHA-256+pepper.
- **Email transport**: raw `net/smtp` vs a provider SDK (SES/SendGrid). Outbox design is transport-agnostic.
- **Geo-IP**: optional MaxMind GeoLite2 mount, off by default.

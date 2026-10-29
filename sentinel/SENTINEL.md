# Sentinel

Authentication and session management service for the TafakoriTech platform.
Handles OTP login, JWT issuance, refresh token rotation, MFA (TOTP), session management, JWT blacklisting, rate limiting, and audit logging.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Authentication Flow](#authentication-flow)
4. [API Reference](#api-reference)
5. [Security Features](#security-features)
6. [Database Schema](#database-schema)
7. [Redis Key Space](#redis-key-space)
8. [Configuration](#configuration)
9. [Rate Limiting](#rate-limiting)
10. [MFA / TOTP](#mfa--totp)
11. [JWT Blacklist](#jwt-blacklist)
12. [Altcha (proof-of-work)](#altcha-proof-of-work)
13. [Audit Logging](#audit-logging)
14. [Prometheus Metrics](#prometheus-metrics)
15. [Running Locally](#running-locally)
16. [Migrations](#migrations)

---

## Overview

Sentinel is a Go/Gin service that acts as the single authentication authority for all platform services. Other services validate access tokens by verifying the JWT signature with the shared `JWT_SECRET`; they do not call Sentinel on the hot path. Session lifecycle events (logout, revocation) are communicated through the JWT blacklist stored in Redis.

**Module:** `github.com/mtafakori/sentinel`  
**Default port:** `8094`  
**Dependencies:** PostgreSQL, Redis

---

## Architecture

```
Client
  │
  ▼
Gin Router
  ├── Middleware stack (tracing → recovery → logging → CORS)
  ├── Rate limiter (Redis sliding window, per-IP + per-identifier + per-user)
  ├── Altcha PoW check (optional, public endpoints only — see note below)
  ├── RequireAuth (JWT validate + blacklist check — protected endpoints)
  └── RequireRole (admin endpoints)
        │
        ▼
    Handler layer
        │
        ├── service.Service   (auth, session, account logic — PostgreSQL + Redis)
        └── mfa.Service       (TOTP enrollment, challenge, recovery codes — PostgreSQL)

Persistence
  ├── PostgreSQL — users, otp_codes, sessions, refresh_tokens,
  │                mfa_recovery_codes, devices, email_outbox
  └── Redis      — rate limit counters, fail counters, auto-lock flags,
                   JWT session blacklist (sentinel:session:revoked:<uuid>)
```

---

## Authentication Flow

### Step 1 — Request OTP

```
POST /otp/request
{ "identifier": "user@example.com" }
```

1. Checks Persona service: user must exist and be active.
2. Checks account lock (DB + Redis).
3. Generates a 6-digit OTP, stores it in `otp_codes` (5-minute TTL).
4. In production, dispatches via SMS/email (not yet wired; see future.txt).

### Step 2 — Verify OTP → receive tokens

```
POST /otp/verify
{ "identifier": "user@example.com", "otp": "123456" }
```

1. Validates OTP (single-use, expiry check).
2. On failure: increments Redis fail counter; auto-locks account after `FAIL_LOCK_AT` attempts.
3. On success: creates a `sessions` row and a `refresh_tokens` row; issues access + refresh JWTs.

**Response:**
```json
{ "api_key": "<access JWT>", "refresh_key": "<refresh JWT>" }
```

### Step 3 — Use access token

Attach to all protected API calls:
```
Authorization: Bearer <api_key>
```

Access tokens are signed HS256 JWTs with a configurable TTL (default 60 min).

### Step 4 — Refresh

```
POST /refresh
{ "refresh_key": "<refresh JWT>" }
```

Single-use rotation with `SELECT FOR UPDATE` to prevent concurrent rotation races.  
Presenting an already-rotated token triggers **reuse detection**: the entire session chain is force-revoked and a security audit event is emitted.

### Step 5 — Logout

```
POST /logout
{ "refresh_key": "<refresh JWT>" }
```

Revokes the session in PostgreSQL and immediately adds the session UUID to the JWT blacklist in Redis, so in-flight access tokens are rejected at the next request.

---

## API Reference

### Public endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/otp/request` | Request a login OTP |
| POST | `/otp/verify` | Verify OTP, receive token pair |
| POST | `/refresh` | Rotate refresh token |
| POST | `/logout` | Revoke session |
| GET | `/health` | Health check |
| GET | `/metrics` | Prometheus metrics |
| GET | `/openapi.json` | OpenAPI 2.0 spec |

### Authenticated endpoints (Bearer access token required)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/sessions` | List active sessions (paginated) |
| DELETE | `/sessions/:session_uuid` | Revoke a specific session |
| POST | `/sessions/logout-all` | Revoke all sessions |
| POST | `/mfa/setup` | Begin TOTP enrollment |
| POST | `/mfa/verify-setup` | Confirm first TOTP code, activate MFA |
| POST | `/mfa/disable` | Disable MFA (requires current factor) |
| POST | `/mfa/recovery-codes/generate` | Regenerate recovery codes |

### Admin endpoints (role: `super_admin` required)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/accounts/lock` | Lock a user account |
| POST | `/accounts/unlock` | Unlock a user account |

---

### Request / Response details

#### POST /otp/request
```json
// Request
{ "identifier": "user@example.com" }

// Response 200
{ "message": "OTP sent" }
```

#### POST /otp/verify
```json
// Request
{ "identifier": "user@example.com", "otp": "123456" }

// Response 200
{ "api_key": "eyJ...", "refresh_key": "eyJ..." }
```

#### POST /refresh
```json
// Request
{ "refresh_key": "eyJ..." }

// Response 200
{ "api_key": "eyJ...", "refresh_key": "eyJ..." }
```

#### POST /logout
```json
// Request
{ "refresh_key": "eyJ..." }

// Response 200
{ "message": "logged out" }
```

#### GET /sessions
```
?page=1&page_size=20
```
```json
// Response 200
{
  "sessions": [
    {
      "ID": 1,
      "SessionUUID": "550e8400-...",
      "UserID": 42,
      "IPAddress": "1.2.3.4",
      "UserAgent": "Mozilla/5.0 ...",
      "CreatedAt": "2026-01-01T00:00:00Z",
      "LastActive": "2026-01-02T00:00:00Z",
      "ExpiresAt": "2026-04-01T00:00:00Z",
      "Revoked": false,
      "RevokedAt": null
    }
  ],
  "total": 1,
  "page": 1,
  "page_size": 20
}
```

#### POST /accounts/lock
```json
// Request
{
  "user_id": 42,
  "reason": "abuse",
  "locked_until": "2026-12-31T00:00:00Z"  // omit for permanent lock
}

// Response 200
{ "message": "account locked" }
```

#### POST /mfa/setup
```json
// Response 200
{
  "otpauth_url": "otpauth://totp/...",
  "qr_png_base64": "iVBOR...",
  "secret": "JBSWY3DPEHPK3PXP"  // shown once for manual entry
}
```

#### POST /mfa/verify-setup
```json
// Request
{ "code": "123456" }

// Response 200
{
  "message": "mfa enabled",
  "recovery_codes": ["abc12-def34", ...]  // 10 codes, shown ONCE
}
```

#### POST /mfa/disable
```json
// Request
{ "code": "123456" }  // TOTP code OR a recovery code

// Response 200
{ "message": "mfa disabled" }
```

---

## Security Features

### JWT structure

Both access and refresh tokens are HS256 JWTs signed with `JWT_SECRET`.

Custom claims:
| Claim | Type | Description |
|-------|------|-------------|
| `uid` | int64 | User ID |
| `role` | string | User role (`user`, `super_admin`) |
| `sid` | string | Session UUID |
| `typ` | string | `"access"` or `"refresh"` |
| `jti` | string | UUID — unique token ID |
| `iss` | string | `"tafakori-auth"` |
| `sub` | string | User ID as string |
| `exp` | int | Unix expiry |
| `iat` | int | Unix issued-at |

### Token lifetimes (defaults)

| Token | Default TTL |
|-------|-------------|
| OTP code | 5 minutes |
| Access token | 60 minutes |
| Refresh token | 7 days |
| Session | 90 days |
| MFA challenge token | 5 minutes |
| Trusted device | 30 days |

### Refresh token rotation

- Each refresh token is single-use. On use it is marked `revoked=true` and a new token is issued.
- `replaced_by` UUID links the chain for forensic tracing.
- `SELECT FOR UPDATE` prevents concurrent rotation races on the same token.
- **Reuse detection**: if a revoked token is presented again (possible theft), the entire session chain is force-revoked and a `security.refresh_token.reuse_detected` audit event is emitted.

### Account locking

Two independent lock mechanisms:

| Mechanism | Trigger | Storage |
|-----------|---------|---------|
| Admin lock | `POST /accounts/lock` | PostgreSQL `users.locked` |
| Auto-lock | `FAIL_LOCK_AT` consecutive OTP failures | Redis `auth:lock:<user_id>` |

When `LockAccount` is called, all active sessions for the user are immediately added to the JWT blacklist so in-flight access tokens are rejected without waiting for expiry.

---

## Database Schema

### `users`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| identifier | TEXT UNIQUE | email or phone |
| role | TEXT | default `'user'` |
| created_at | TIMESTAMPTZ | |
| locked | BOOLEAN | admin lock flag |
| locked_at | TIMESTAMPTZ | |
| locked_until | TIMESTAMPTZ | NULL = permanent |
| lock_reason | TEXT | |
| mfa_state | TEXT | `disabled` \| `pending_setup` \| `enabled` |
| mfa_secret_enc | BYTEA | AES-256-GCM encrypted TOTP secret |
| mfa_last_timestep | BIGINT | replay guard |
| mfa_enabled_at | TIMESTAMPTZ | |

### `otp_codes`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| user_id | BIGINT FK → users | |
| code | TEXT | 6-digit OTP |
| expires_at | TIMESTAMPTZ | 5 min from creation |
| used | BOOLEAN | single-use |
| created_at | TIMESTAMPTZ | |

### `sessions`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| session_uuid | UUID UNIQUE | embedded in JWT `sid` claim |
| user_id | BIGINT FK → users | |
| ip_address | TEXT | |
| user_agent | TEXT | |
| created_at | TIMESTAMPTZ | |
| last_active | TIMESTAMPTZ | updated on each refresh |
| expires_at | TIMESTAMPTZ | `SESSION_MAX_AGE_DAYS` |
| revoked | BOOLEAN | |
| revoked_at | TIMESTAMPTZ | |

### `refresh_tokens`
| Column | Type | Notes |
|--------|------|-------|
| id | UUID PK | = JWT `jti` |
| session_id | BIGINT FK → sessions | |
| user_id | BIGINT FK → users | |
| issued_at | TIMESTAMPTZ | |
| expires_at | TIMESTAMPTZ | |
| revoked | BOOLEAN | |
| revoked_at | TIMESTAMPTZ | |
| replaced_by | UUID FK → refresh_tokens | rotation chain |

### `mfa_recovery_codes`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| user_id | BIGINT FK → users | |
| code_hash | TEXT UNIQUE | `hex(sha256(pepper ‖ code))` |
| used | BOOLEAN | single-use |
| used_at | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ | |

### `devices`
| Column | Type | Notes |
|--------|------|-------|
| id | UUID PK | |
| user_id | BIGINT FK → users | |
| fingerprint_hash | TEXT | |
| user_agent | TEXT | |
| ip_address | TEXT | |
| trusted | BOOLEAN | true = bypasses MFA challenge |
| trust_expires_at | TIMESTAMPTZ | |
| last_seen | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ | |
| revoked | BOOLEAN | |

### `email_outbox`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| user_id | BIGINT FK → users | nullable |
| to_addr | TEXT | |
| template | TEXT | |
| payload | JSONB | template variables |
| status | TEXT | `pending` \| `sent` \| `failed` |
| attempts | INT | |
| next_attempt_at | TIMESTAMPTZ | exponential backoff |
| last_error | TEXT | |
| created_at | TIMESTAMPTZ | |
| sent_at | TIMESTAMPTZ | |

---

## Redis Key Space

| Key pattern | TTL | Purpose |
|-------------|-----|---------|
| `ratelimit:ip:<ip>:<path>` | sliding window | IP-based rate limit counter |
| `ratelimit:identifier:<id>:<path>` | sliding window | Per-identifier rate limit |
| `ratelimit:user:<uid>:<path>` | sliding window | Per-user rate limit |
| `auth:fails:<identifier>` | `FAIL_WINDOW` | OTP failure counter |
| `auth:lock:<user_id>` | `LOCK_DURATION` | Auto-lock flag |
| `sentinel:session:revoked:<session_uuid>` | `ACCESS_TOKEN_TTL` | JWT blacklist entry |

All rate limit counters use an atomic Lua sliding-window script (sorted sets). The blacklist uses plain string keys with TTL.

**Fail-open policy:** Both the rate limiter and the JWT blacklist fail open on Redis errors — a transient Redis outage will not block legitimate traffic, but will temporarily disable rate limiting and revocation enforcement.

---

## Configuration

All configuration is read from environment variables at startup. Copy `.env` and fill in the required values.

### Required

| Variable | Description |
|----------|-------------|
| `DATABASE_URL` | PostgreSQL DSN |
| `JWT_SECRET` | Signing key for all JWTs — long random string |
| `MFA_ENCRYPTION_KEY` | `base64:<32 bytes>` — AES-256-GCM key for TOTP secrets at rest |
| `RECOVERY_PEPPER` | Server-side pepper for recovery code hashes |
| `DEVICE_SECRET` | HMAC key for trusted-device tokens |

### Server

| Variable | Default | Description |
|----------|---------|-------------|
| `HOST` | `0.0.0.0` | Bind address |
| `PORT` | `8094` | Listen port |
| `ENV` | `development` | Set to `production` to enable Gin release mode |

### Tokens & sessions

| Variable | Default | Description |
|----------|---------|-------------|
| `ACCESS_TOKEN_TTL_MINUTES` | `60` | Access token lifetime |
| `REFRESH_TOKEN_TTL_DAYS` | `7` | Refresh token lifetime |
| `SESSION_MAX_AGE_DAYS` | `90` | Session lifetime |

### Redis & integrations

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_URL` | `redis://redis:6379` | Redis connection URL |
| `PERSONA_URL` | `http://persona:8095` | Persona service URL |
| `PERSONA_API_KEY` | — | Persona service API key |

### Failed-login & auto-lock

| Variable | Default | Description |
|----------|---------|-------------|
| `FAIL_WARN_AT` | `5` | Warn in logs after N failures |
| `FAIL_LOCK_AT` | `10` | Auto-lock after N failures |
| `FAIL_WINDOW` | `10m` | Sliding window for failure counter |
| `LOCK_DURATION` | `30m` | Auto-lock duration (Go duration; `0` = permanent) |

### MFA / TOTP

| Variable | Default | Description |
|----------|---------|-------------|
| `TOTP_ISSUER` | `Tafakori` | Label shown in authenticator apps |
| `TOTP_SKEW` | `1` | Allowed ±time steps (1 = ±30 s) |
| `MFA_CHALLENGE_TTL` | `5m` | Intermediate MFA challenge token lifetime |
| `DEVICE_TRUST_TTL_DAYS` | `30` | Days a trusted device bypasses MFA |

### Altcha (proof-of-work, optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `ALTCHA_HMAC_KEY` | — | HMAC-SHA256 secret; empty = disabled |
| `ALTCHA_MAX_NUMBER` | `50000` | PoW search space (avg iterations = max / 2) |
| `ALTCHA_EXPIRES` | `10m` | Challenge validity window |

### Rate limits

Each rule has `_LIMIT` (count) and `_WINDOW` (Go duration) suffixes.

| Prefix | Default limit | Default window |
|--------|--------------|----------------|
| `RATE_LIMIT_OTP_REQUEST` | 5 | 10m |
| `RATE_LIMIT_OTP_VERIFY` | 10 | 10m |
| `RATE_LIMIT_LOGIN` | 20 | 1h |
| `RATE_LIMIT_REFRESH` | 30 | 1h |
| `RATE_LIMIT_MFA_CHALLENGE` | 10 | 5m |
| `RATE_LIMIT_MFA_SETUP` | 5 | 1h |
| `RATE_LIMIT_RECOVERY_VERIFY` | 5 | 15m |
| `RATE_LIMIT_DEVICE_TRUST` | 10 | 1h |

### Email / SMTP

| Variable | Default | Description |
|----------|---------|-------------|
| `SMTP_HOST` | — | SMTP server host |
| `SMTP_PORT` | `587` | SMTP port |
| `SMTP_USER` | — | SMTP username |
| `SMTP_PASS` | — | SMTP password |
| `MAIL_FROM` | `no-reply@tafakori.tech` | Sender address |
| `MAIL_MAX_RETRIES` | `5` | Max delivery attempts |
| `MAIL_RETRY_BACKOFF` | `30s` | Initial retry backoff |

### Key material loading

`MFA_ENCRYPTION_KEY`, `RECOVERY_PEPPER`, and `DEVICE_SECRET` are loaded from Docker Swarm secrets first (`/run/secrets/<name>`), falling back to env vars. A `base64:` prefix causes the value to be base64-decoded before use.

**Generating key material:**
```bash
# 32-byte AES key for MFA_ENCRYPTION_KEY
echo "base64:$(openssl rand -base64 32)"

# Pepper / device secret
openssl rand -hex 32
```

---

## Rate Limiting

All rate limits use an atomic Lua sliding-window algorithm backed by Redis sorted sets. Three strategies are available and can be composed per-endpoint:

| Strategy | Keyed by | Used on |
|----------|----------|---------|
| `ByIP` | `<ip>:<path>` | All public endpoints |
| `ByIdentifier` | `<identifier>:<path>` | `/otp/request`, `/otp/verify` |
| `ByUser` | `<user_id>:<path>` | MFA endpoints, recovery codes |

When a limit is exceeded the response is:
```json
HTTP 429
Retry-After: <seconds>
{ "error": "rate_limit_exceeded", "message": "...", "retry_after": 600 }
```

Redis failures cause the limiter to fail open (traffic is not blocked).

---

## MFA / TOTP

### Enrollment flow

1. `POST /mfa/setup` — generates a TOTP secret (AES-256-GCM encrypted before storage), returns `otpauth://` URL, QR PNG (base64), and the raw secret for manual entry. User's `mfa_state` transitions to `pending_setup`.
2. `POST /mfa/verify-setup` — validates the first TOTP code. On success: `mfa_state` → `enabled`; returns 10 single-use recovery codes (shown once, stored as `sha256(pepper ‖ code)` hashes).

### Challenge / verify

`mfa.Service.VerifyChallenge` accepts either:
- A 6-digit TOTP code (validated with ±`TOTP_SKEW` time step tolerance and a replay guard via `mfa_last_timestep`)
- A recovery code (16-char, `XXXXX-XXXXX` format; single-use, atomically marked used)

### Disable

`POST /mfa/disable` requires a valid current factor (TOTP or recovery code) before clearing MFA, preventing a hijacked session from silently removing the second factor.

### Key material

- **TOTP secrets**: stored as `AES-256-GCM(nonce ‖ ciphertext)` in `users.mfa_secret_enc`, encrypted with `MFA_ENCRYPTION_KEY`.
- **Recovery codes**: stored as `hex(sha256(RECOVERY_PEPPER ‖ code))` — the pepper prevents offline dictionary attacks even if the table is leaked.

---

## JWT Blacklist

Access tokens are stateless JWTs, but Sentinel achieves **immediate revocation** without a per-request database lookup by blacklisting at the session level.

### How it works

1. Every access token embeds a session UUID in the `sid` claim.
2. On any revocation event (logout, `DELETE /sessions/:uuid`, logout-all, account lock), the session UUID is written to Redis:
   ```
   SET sentinel:session:revoked:<session_uuid> 1 EX <access_token_ttl_seconds>
   ```
3. `RequireAuth` middleware checks this key after validating the JWT signature. A hit → `401 session revoked`.
4. The TTL is set to `ACCESS_TOKEN_TTL` (default 60 min) — the maximum window in which a revoked-session access token could legitimately appear.

### Revocation triggers

| Event | Sessions blacklisted |
|-------|---------------------|
| `POST /logout` | The session from the refresh token |
| `DELETE /sessions/:uuid` | The specified session |
| `POST /sessions/logout-all` | All active sessions for the user |
| `POST /accounts/lock` | All active sessions for the target user |

### Fail-open

Redis errors during the blacklist check cause the request to pass through rather than blocking traffic. This is a deliberate availability-over-security trade-off for transient failures; persistent Redis outages should trigger alerting.

---

## Altcha (proof-of-work)

Sentinel ships with optional [Altcha](https://altcha.org) proof-of-work support on `POST /otp/request` and `POST /otp/verify`. It is **disabled by default** and not required for correct operation.

### How it works

1. Client calls `GET /altcha/challenge` — server returns a signed SHA-256 puzzle.
2. Client brute-forces the puzzle (tries integers until `SHA-256(salt + n) == challenge`).
3. Client sends the base64-encoded solution in `X-Captcha-Token` header.
4. Server re-derives the hash and verifies the HMAC signature — no external service call.

### Limitations

Altcha adds **CPU cost per request** to slow down automated flooding. It does **not** reliably distinguish humans from bots — any attacker who can reach the endpoint can also solve the PoW puzzle programmatically.

For this platform, the primary human signal is **OTP delivery**: a bot cannot complete login without access to the target phone number or email inbox. Rate limiting covers flood attacks. Altcha is therefore supplementary and can remain disabled in most deployments.

If device-level attestation is needed (proving the request originates from a genuine, unmodified app on a real device), use **Google Play Integrity API** instead.

### Enabling

Set `ALTCHA_HMAC_KEY` to any non-empty secret (or use the Docker secret `/run/secrets/altcha_hmac_key`). Leave it empty to disable — all requests pass through without a token.

```bash
echo -n "$(openssl rand -hex 32)" | docker secret create altcha_hmac_key -
```

### Client flow

```
GET  /altcha/challenge          → { algorithm, challenge, maxNumber, salt, signature }
                                   client solves: find n where SHA-256(salt+n) == challenge
POST /otp/request               → X-Captcha-Token: <base64 solution JSON>
```

Returns `503` on the challenge endpoint if `ALTCHA_HMAC_KEY` is not set — clients should treat this as "disabled" and proceed without a token.

---

## Audit Logging

All security-relevant events are emitted as structured JSON to stdout in ELK-compatible format. The audit logger is async (buffered channel, 2048 entries) to avoid blocking request handlers.

### Log format

```json
{
  "timestamp": "2026-06-15T10:00:00Z",
  "log_type": "audit",
  "event": "auth.otp.verify",
  "result": "success",
  "actor": { "user_id": "42", "type": "user" },
  "context": { "ip": "1.2.3.4", "user_agent": "..." },
  "trace_id": "abc123",
  "request_id": "def456",
  "service": "sentinel",
  "meta": { ... }
}
```

### Event catalogue

| Event | Trigger |
|-------|---------|
| `auth.otp.request` | OTP requested |
| `auth.otp.verify` | OTP verify attempt (success or failure) |
| `auth.login.success` | Token pair issued |
| `auth.logout` | Session logged out |
| `auth.token.refresh.success` | Refresh token rotated |
| `security.refresh_token.reuse_detected` | Revoked refresh token re-presented |
| `auth.session.create` | New session created |
| `auth.session.revoke` | Session revoked by user |
| `auth.session.list` | Sessions listed |
| `auth.session.logout_all` | All sessions revoked |
| `security.rate_limit.hit` | Rate limit exceeded |
| `security.account.locked` | Account locked by admin |
| `security.account.unlocked` | Account unlocked by admin |
| `security.account.auto_locked` | Account auto-locked after failures |
| `mfa.setup.started` | TOTP enrollment begun |
| `mfa.enabled` | TOTP confirmed and activated |
| `mfa.disabled` | MFA turned off |
| `mfa.challenge.success` | MFA challenge passed |
| `mfa.challenge.failed` | MFA challenge failed |
| `mfa.bypassed.trusted_device` | MFA skipped for trusted device |
| `recovery_code.used` | Recovery code consumed |
| `recovery_code.regenerated` | Recovery codes regenerated |
| `device.trusted` | Device added to trusted list |
| `device.untrusted` | Device removed from trusted list |
| `login.new_device` | Login from an unrecognised device |
| `email.notification.sent` | Email dispatched |
| `email.notification.failed` | Email delivery failed |

---

## Prometheus Metrics

Exposed at `GET /metrics` (Prometheus scrape endpoint).

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `sentinel_requests_total` | Counter | `method`, `path`, `status` | Total HTTP requests |
| `sentinel_request_duration_seconds` | Histogram | `method`, `path` | Request latency |
| `sentinel_rate_limit_hits_total` | Counter | `limit_type`, `endpoint` | Rate limit violations |
| `sentinel_auth_events_total` | Counter | `event`, `result` | Auth events by type and outcome |
| `sentinel_otp_abuse_attempts_total` | Counter | `identifier_type` | OTP failures (abuse signal) |
| `sentinel_token_reuse_total` | Counter | — | Refresh token reuse detections |
| `sentinel_session_revocations_total` | Counter | `reason` | Session revocations (`logout`, `logout_all`, `reuse_detected`, `admin`) |
| `sentinel_account_locks_total` | Counter | `type` | Account locks (`auto`, `admin`) |
| `sentinel_blacklist_hits_total` | Counter | — | Requests rejected by JWT blacklist |

---

## Running Locally

### Prerequisites

- Go 1.22+
- PostgreSQL 15+
- Redis 7+

### Steps

```bash
# 1. Copy and fill in required env vars
cp .env.example .env   # or copy from sentinel/.env

# 2. Apply migrations
psql $DATABASE_URL -f migrations/001_init.sql
psql $DATABASE_URL -f migrations/002_refresh_token_rotation.sql
psql $DATABASE_URL -f migrations/003_mfa_devices_email.sql

# 3. Run
cd sentinel
go run ./cmd/sentinel
```

The server starts on `0.0.0.0:8094` by default.

### Generating key material for local dev

```bash
# MFA_ENCRYPTION_KEY (exactly 32 bytes, base64-prefixed)
echo "MFA_ENCRYPTION_KEY=base64:$(openssl rand -base64 32)"

# RECOVERY_PEPPER
echo "RECOVERY_PEPPER=$(openssl rand -hex 32)"

# DEVICE_SECRET
echo "DEVICE_SECRET=$(openssl rand -hex 32)"

# JWT_SECRET
echo "JWT_SECRET=$(openssl rand -hex 32)"
```

---

## Migrations

Migrations are plain SQL files in `migrations/`. They must be applied in order. No migration framework — run them directly with `psql`.

| File | Description |
|------|-------------|
| `001_init.sql` | Base schema: `users`, `otp_codes`, `sessions` |
| `002_refresh_token_rotation.sql` | Session UUIDs, revocation, `refresh_tokens` table, account locking |
| `003_mfa_devices_email.sql` | MFA state columns, `mfa_recovery_codes`, `devices`, `email_outbox` |

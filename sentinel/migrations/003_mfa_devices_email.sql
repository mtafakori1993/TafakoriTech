BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- Users: MFA state machine
--   mfa_state: 'disabled' | 'pending_setup' | 'enabled'
--   mfa_secret_enc: AES-256-GCM(nonce || ciphertext) of the TOTP secret
--   mfa_last_timestep: last accepted TOTP time-step T, used as a replay guard
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS mfa_state         TEXT        NOT NULL DEFAULT 'disabled',
    ADD COLUMN IF NOT EXISTS mfa_secret_enc    BYTEA,
    ADD COLUMN IF NOT EXISTS mfa_last_timestep BIGINT,
    ADD COLUMN IF NOT EXISTS mfa_enabled_at    TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_mfa_state'
    ) THEN
        ALTER TABLE users
            ADD CONSTRAINT chk_mfa_state
            CHECK (mfa_state IN ('disabled', 'pending_setup', 'enabled'));
    END IF;
END$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Recovery codes: one row per code, hashed as hex(sha256(pepper || code)).
-- High-entropy codes + server-side pepper → safe against offline brute force,
-- and the unique hash index gives O(1) single-use verification lookup.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mfa_recovery_codes (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash  TEXT        NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_recovery_hash ON mfa_recovery_codes (code_hash);
CREATE        INDEX IF NOT EXISTS idx_recovery_user ON mfa_recovery_codes (user_id) WHERE used = FALSE;

-- ─────────────────────────────────────────────────────────────────────────────
-- Devices: trusted-device bypass + new-device detection.
-- A row with trusted=false is a "known" device (used for new-device alerts);
-- trusted=true with a live trust_expires_at bypasses the MFA challenge.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS devices (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    fingerprint_hash TEXT        NOT NULL,
    user_agent       TEXT,
    ip_address       TEXT,
    trusted          BOOLEAN     NOT NULL DEFAULT FALSE,
    trust_expires_at TIMESTAMPTZ,
    last_seen        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked          BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_device_user_fp ON devices (user_id, fingerprint_hash);
CREATE        INDEX IF NOT EXISTS idx_device_user    ON devices (user_id) WHERE revoked = FALSE;

-- ─────────────────────────────────────────────────────────────────────────────
-- Email outbox: transactional, durable, retry-able.
-- An email row is inserted in the SAME transaction as its triggering security
-- event, then drained by a single leader replica (advisory lock) with backoff.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS email_outbox (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      REFERENCES users(id) ON DELETE SET NULL,
    to_addr         TEXT        NOT NULL,
    template        TEXT        NOT NULL,
    payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status          TEXT        NOT NULL DEFAULT 'pending',
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMPTZ
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_outbox_status'
    ) THEN
        ALTER TABLE email_outbox
            ADD CONSTRAINT chk_outbox_status
            CHECK (status IN ('pending', 'sent', 'failed'));
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_outbox_due ON email_outbox (next_attempt_at) WHERE status = 'pending';

COMMIT;

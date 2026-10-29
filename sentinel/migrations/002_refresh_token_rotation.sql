BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- Sessions: add device tracking, revocation, stable UUID
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS session_uuid  UUID        NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS ip_address    TEXT,
    ADD COLUMN IF NOT EXISTS user_agent    TEXT,
    ADD COLUMN IF NOT EXISTS last_active   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS revoked       BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS revoked_at    TIMESTAMPTZ;

-- Revoke all pre-rotation sessions; they have no rotation chain so they
-- cannot be safely refreshed after this migration.
UPDATE sessions SET revoked = TRUE, revoked_at = NOW() WHERE revoked = FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_uuid   ON sessions (session_uuid);
CREATE        INDEX IF NOT EXISTS idx_sessions_active ON sessions (user_id) WHERE revoked = FALSE;

-- Drop the old per-session hash column; rotation is tracked in refresh_tokens.
ALTER TABLE sessions DROP COLUMN IF EXISTS refresh_token_hash;

-- ─────────────────────────────────────────────────────────────────────────────
-- Refresh token rotation chain
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          UUID         PRIMARY KEY,
    session_id  BIGINT       NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    user_id     BIGINT       NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at  TIMESTAMPTZ,
    -- Links old token → new token for forensic chain tracing.
    -- DEFERRABLE so we can insert the new token before updating the old one
    -- within the same transaction.
    replaced_by UUID REFERENCES refresh_tokens(id) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX IF NOT EXISTS idx_rt_session_id ON refresh_tokens (session_id);
CREATE INDEX IF NOT EXISTS idx_rt_user_id    ON refresh_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_rt_active     ON refresh_tokens (session_id) WHERE revoked = FALSE;

-- ─────────────────────────────────────────────────────────────────────────────
-- Users: admin-controlled account locking
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS locked        BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS locked_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS locked_until  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lock_reason   TEXT;

COMMIT;

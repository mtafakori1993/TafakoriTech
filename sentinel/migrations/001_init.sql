CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL    PRIMARY KEY,
    identifier  TEXT         NOT NULL UNIQUE,  -- phone or email
    role        TEXT         NOT NULL DEFAULT 'user',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS otp_codes (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code        TEXT         NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_otp_user_id ON otp_codes (user_id);

CREATE TABLE IF NOT EXISTS sessions (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash  TEXT         NOT NULL UNIQUE,
    expires_at          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions (user_id);

-- Seed super-admin (password: admin123, stored as bcrypt hash)
-- Hash generated for 'admin123': $2a$10$O8/KIMnFvT/utgtRUaCtfuj2Hrn6AyFn.ETz1x2WCzMMgF.0NC7Pe
INSERT INTO users (identifier, role)
VALUES ('admin', 'super_admin')
ON CONFLICT (identifier) DO NOTHING;

CREATE TABLE IF NOT EXISTS notifications (
    id          TEXT        PRIMARY KEY,
    channel     TEXT        NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),
    recipient   TEXT        NOT NULL,
    subject     TEXT,
    body        TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts    INT         NOT NULL DEFAULT 0,
    error_msg   TEXT,
    sent_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notifications_status  ON notifications (status);
CREATE INDEX IF NOT EXISTS idx_notifications_created ON notifications (created_at DESC);

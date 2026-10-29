-- Herald CMS: static pages (About Us, Privacy Policy, etc.)
CREATE TABLE IF NOT EXISTS pages (
    id          BIGSERIAL       PRIMARY KEY,
    content_id  BIGINT          NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    route       VARCHAR(500)    NOT NULL,
    template    VARCHAR(100)    NOT NULL DEFAULT 'default',
    is_system   BOOLEAN         NOT NULL DEFAULT FALSE,

    CONSTRAINT uni_pages_content_id UNIQUE (content_id),
    CONSTRAINT uni_pages_route      UNIQUE (route)
);

CREATE INDEX IF NOT EXISTS idx_pages_content_id ON pages (content_id);

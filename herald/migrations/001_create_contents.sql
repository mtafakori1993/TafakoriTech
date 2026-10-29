-- Herald CMS: base content table
CREATE TABLE IF NOT EXISTS contents (
    id              BIGSERIAL       PRIMARY KEY,
    type            VARCHAR(20)     NOT NULL CHECK (type IN ('page', 'post', 'article')),
    title           VARCHAR(500)    NOT NULL,
    slug            VARCHAR(500)    NOT NULL,
    body            TEXT            NOT NULL DEFAULT '',
    excerpt         TEXT            NOT NULL DEFAULT '',
    status          VARCHAR(20)     NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    seo_title       VARCHAR(500)    NOT NULL DEFAULT '',
    seo_description TEXT            NOT NULL DEFAULT '',
    author_id       BIGINT          NOT NULL DEFAULT 1,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uni_contents_slug UNIQUE (slug)
);

CREATE INDEX IF NOT EXISTS idx_contents_type        ON contents (type);
CREATE INDEX IF NOT EXISTS idx_contents_status      ON contents (status);
CREATE INDEX IF NOT EXISTS idx_contents_author_id   ON contents (author_id);
CREATE INDEX IF NOT EXISTS idx_contents_published_at ON contents (published_at DESC);

CREATE OR REPLACE FUNCTION fn_set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_contents_updated_at ON contents;
CREATE TRIGGER trg_contents_updated_at
    BEFORE UPDATE ON contents
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

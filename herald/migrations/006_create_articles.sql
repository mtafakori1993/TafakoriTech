-- Herald CMS: long-form articles
CREATE TABLE IF NOT EXISTS articles (
    id          BIGSERIAL   PRIMARY KEY,
    content_id  BIGINT      NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    read_time   INT         NOT NULL DEFAULT 0,

    CONSTRAINT uni_articles_content_id UNIQUE (content_id)
);

CREATE INDEX IF NOT EXISTS idx_articles_content_id ON articles (content_id);

-- Article ↔ Tag join table
CREATE TABLE IF NOT EXISTS article_tags (
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    tag_id     BIGINT NOT NULL REFERENCES tags(id)     ON DELETE CASCADE,
    PRIMARY KEY (article_id, tag_id)
);

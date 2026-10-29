-- Herald CMS: blog posts
CREATE TABLE IF NOT EXISTS posts (
    id          BIGSERIAL   PRIMARY KEY,
    content_id  BIGINT      NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    category_id BIGINT      REFERENCES categories(id) ON DELETE SET NULL,

    CONSTRAINT uni_posts_content_id UNIQUE (content_id)
);

CREATE INDEX IF NOT EXISTS idx_posts_content_id  ON posts (content_id);
CREATE INDEX IF NOT EXISTS idx_posts_category_id ON posts (category_id);

-- Post ↔ Tag join table
CREATE TABLE IF NOT EXISTS post_tags (
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    tag_id  BIGINT NOT NULL REFERENCES tags(id)  ON DELETE CASCADE,
    PRIMARY KEY (post_id, tag_id)
);

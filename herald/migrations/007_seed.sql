-- Herald CMS: seed data
-- Run after all schema migrations. Safe to re-run (uses INSERT … ON CONFLICT DO NOTHING).

-- Categories
INSERT INTO categories (name, slug, description) VALUES
    ('Engineering',  'engineering',  'Technical posts about software and systems'),
    ('Product',      'product',      'Product updates and announcements'),
    ('Company',      'company',      'Company news and culture')
ON CONFLICT (slug) DO NOTHING;

-- Tags
INSERT INTO tags (name, slug) VALUES
    ('Go',          'go'),
    ('PostgreSQL',  'postgresql'),
    ('Docker',      'docker'),
    ('API',         'api'),
    ('DDD',         'ddd')
ON CONFLICT (slug) DO NOTHING;

-- System pages (draft — publish via API)
INSERT INTO contents (type, title, slug, body, excerpt, status, seo_title, seo_description, author_id)
VALUES
    ('page', 'About Us',        'about-us',        '<h1>About Us</h1><p>We build great software.</p>', 'Learn about our mission.', 'draft', 'About Us', 'Learn about our company and mission.', 1),
    ('page', 'Contact',         'contact',         '<h1>Contact</h1><p>Get in touch.</p>',             'Reach out to us.',         'draft', 'Contact', 'Get in touch with our team.',          1),
    ('page', 'Privacy Policy',  'privacy-policy',  '<h1>Privacy Policy</h1><p>Your data is safe.</p>', 'Our privacy commitments.', 'draft', 'Privacy Policy', 'How we handle your data.',      1),
    ('page', 'Terms of Service','terms-of-service','<h1>Terms of Service</h1><p>Usage terms.</p>',     'Terms governing usage.',   'draft', 'Terms of Service', 'Terms for using our service.',  1)
ON CONFLICT (slug) DO NOTHING;

-- Wire the page records (only insert if content was just created)
INSERT INTO pages (content_id, route, template, is_system)
SELECT id, '/about',   'default', true FROM contents WHERE slug = 'about-us'        ON CONFLICT (content_id) DO NOTHING;
INSERT INTO pages (content_id, route, template, is_system)
SELECT id, '/contact', 'default', true FROM contents WHERE slug = 'contact'         ON CONFLICT (content_id) DO NOTHING;
INSERT INTO pages (content_id, route, template, is_system)
SELECT id, '/privacy', 'legal',   true FROM contents WHERE slug = 'privacy-policy'  ON CONFLICT (content_id) DO NOTHING;
INSERT INTO pages (content_id, route, template, is_system)
SELECT id, '/terms',   'legal',   true FROM contents WHERE slug = 'terms-of-service' ON CONFLICT (content_id) DO NOTHING;

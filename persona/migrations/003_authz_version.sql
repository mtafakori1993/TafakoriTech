-- Add authorization version for cache invalidation and JWT validation strategies.
-- Incremented whenever a user's effective permissions change (role/permission assignments).
ALTER TABLE users ADD COLUMN IF NOT EXISTS authz_version BIGINT NOT NULL DEFAULT 1;

-- Create the Herald CMS database.
-- Executed by the postgres Docker container on first start (empty data dir).
-- For an already-running instance: psql -U <user> -c "CREATE DATABASE herald;"
SELECT 'CREATE DATABASE herald'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'herald')\gexec

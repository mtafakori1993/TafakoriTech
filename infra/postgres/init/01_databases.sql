-- Create all service databases on first postgres start.
SELECT 'CREATE DATABASE sentinel' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'sentinel')\gexec
SELECT 'CREATE DATABASE persona'  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'persona')\gexec
SELECT 'CREATE DATABASE marshal'  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'marshal')\gexec
SELECT 'CREATE DATABASE herald'   WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'herald')\gexec
SELECT 'CREATE DATABASE pulse'    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'pulse')\gexec

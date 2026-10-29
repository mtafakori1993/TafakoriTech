#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

set -a
# shellcheck source=.env
source .env
set +a

# Run pending database migrations against the live postgres container
POSTGRES_CONTAINER=$(docker ps --filter name=TafakoriTech_postgres --format "{{.ID}}" | head -1)
if [ -n "$POSTGRES_CONTAINER" ]; then
  echo "Running marshal migration..."
  docker exec -i "$POSTGRES_CONTAINER" \
    psql -U "$POSTGRES_USER" -d marshal \
    < "$(dirname "$0")/../marshal/migrations/001_init.sql"
fi

# Pre-process Kong declarative config
envsubst < kong/kong.yml > kong/kong-processed.yml

# Hash the processed config to create a versioned config name
KONG_CONFIG_HASH=$(md5sum kong/kong-processed.yml | cut -c1-8)
KONG_CONFIG_NAME="kong-config-${KONG_CONFIG_HASH}"

export KONG_CONFIG_NAME

# Deploy with versioned config name
envsubst < docker-stack.yml | \
  sed "s/kong-config-PLACEHOLDER/${KONG_CONFIG_NAME}/g" | \
  docker stack deploy -c - TafakoriTech

# Remove old kong configs (keep only the current one)
docker config ls --format '{{.Name}}' | grep '^TafakoriTech_kong-config-' | \
  grep -v "TafakoriTech_${KONG_CONFIG_NAME}" | \
  xargs -r docker config rm 2>/dev/null || true

rm -f kong/kong-processed.yml

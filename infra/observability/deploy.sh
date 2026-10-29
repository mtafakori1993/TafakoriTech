#!/usr/bin/env bash
# Deploy or redeploy the observability stack.
# Usage: ./deploy.sh
set -euo pipefail

cd "$(dirname "$0")"

set -a
source .env
set +a

# Fluent Bit configs are immutable — recreate them if content changed
for cfg in fluent-bit-config fluent-bit-parsers fluent-bit-lua; do
    docker config rm "observability_${cfg}" 2>/dev/null && echo "removed old ${cfg}" || true
done
docker service rm observability_fluent-bit 2>/dev/null || true

envsubst < stack.yml | docker stack deploy --resolve-image never -c - observability

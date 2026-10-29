#!/usr/bin/env bash
# refresh.sh — pull latest code, rebuild all service images, redeploy the stack.
#
# Usage:
#   cd ~/projects/TafakoriTech/infra
#   ./refresh.sh [service ...]
#
# With no arguments every app service is rebuilt.
# Pass one or more service names to rebuild only those:
#   ./refresh.sh sentinel persona
#
# Available services: sentinel persona herald marshal pulse
set -euo pipefail

# ── Paths ─────────────────────────────────────────────────────────────────────
INFRA_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${INFRA_DIR}/.." && pwd)"

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[refresh]${NC} $*"; }
success() { echo -e "${GREEN}[refresh]${NC} $*"; }
warn()    { echo -e "${YELLOW}[refresh]${NC} $*"; }
die()     { echo -e "${RED}[refresh] ERROR:${NC} $*" >&2; exit 1; }

# ── Load .env ─────────────────────────────────────────────────────────────────
ENV_FILE="${INFRA_DIR}/.env"
[ -f "${ENV_FILE}" ] || die ".env not found at ${ENV_FILE}"
set -a
# shellcheck source=.env
source "${ENV_FILE}"
set +a

# ── Service definitions ───────────────────────────────────────────────────────
# Format: "name|image_var|main_package|output_binary"
declare -A SVC_IMAGE=(
  [sentinel]="${SENTINEL_IMAGE:-TafakoriTech/sentinel:latest}"
  [persona]="${PERSONA_IMAGE:-TafakoriTech/persona:latest}"
  [herald]="${HERALD_IMAGE:-TafakoriTech/herald:latest}"
  [marshal]="${MARSHAL_IMAGE:-TafakoriTech/marshal:latest}"
  [pulse]="${PULSE_IMAGE:-TafakoriTech/pulse:latest}"
)
# Source dir (has its own go.mod — build must run from here with GOWORK=off)
declare -A SVC_DIR=(
  [sentinel]="sentinel"
  [persona]="persona"
  [herald]="herald"
  [marshal]="marshal"
  [pulse]="pulse"
)
# Package path relative to the service's own directory
declare -A SVC_MAIN=(
  [sentinel]="./cmd/sentinel"
  [persona]="./cmd/persona"
  [herald]="./cmd/herald"
  [marshal]="."
  [pulse]="./cmd/pulse"
)
# Output binary path relative to ROOT_DIR (must match COPY in each Dockerfile)
declare -A SVC_BIN=(
  [sentinel]="sentinel/bin/sentinel"
  [persona]="persona/persona"
  [herald]="herald/herald"
  [marshal]="marshal/marshal"
  [pulse]="pulse/bin/pulse"
)
declare -A SVC_DOCKERFILE=(
  [sentinel]="sentinel/Dockerfile"
  [persona]="persona/Dockerfile"
  [herald]="herald/Dockerfile"
  [marshal]="marshal/Dockerfile"
  [pulse]="pulse/Dockerfile"
)

ALL_SERVICES=(sentinel persona herald marshal pulse)

# ── Determine which services to rebuild ───────────────────────────────────────
if [ "$#" -gt 0 ]; then
  TARGETS=("$@")
  for t in "${TARGETS[@]}"; do
    [[ -v SVC_IMAGE["$t"] ]] || die "Unknown service '${t}'. Choose from: ${ALL_SERVICES[*]}"
  done
else
  TARGETS=("${ALL_SERVICES[@]}")
fi

# ── Step 1 — Git pull ─────────────────────────────────────────────────────────
info "Pulling latest changes from git..."
cd "${ROOT_DIR}"
if git rev-parse --is-inside-work-tree &>/dev/null; then
  BEFORE=$(git rev-parse --short HEAD)
  git pull --ff-only
  AFTER=$(git rev-parse --short HEAD)
  if [ "${BEFORE}" = "${AFTER}" ]; then
    warn "Already up to date (${AFTER})."
  else
    success "Updated ${BEFORE} → ${AFTER}"
  fi
else
  warn "Not a git repository — skipping git pull."
fi

# ── Step 2 — Build Go binaries ────────────────────────────────────────────────
info "Building Go binaries for: ${TARGETS[*]}"
cd "${ROOT_DIR}"

for svc in "${TARGETS[@]}"; do
  bin="${ROOT_DIR}/${SVC_BIN[$svc]}"
  pkg="${SVC_MAIN[$svc]}"
  svc_dir="${ROOT_DIR}/${SVC_DIR[$svc]}"
  info "  go build ${svc} → ${SVC_BIN[$svc]}"
  (cd "${svc_dir}" && GOWORK=off CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -trimpath -ldflags="-s -w" -o "${bin}" "${pkg}")
  success "  ${svc} binary built"
done

# ── Step 3 — Build Docker images ──────────────────────────────────────────────
info "Building Docker images..."
cd "${ROOT_DIR}"

for svc in "${TARGETS[@]}"; do
  image="${SVC_IMAGE[$svc]}"
  dockerfile="${SVC_DOCKERFILE[$svc]}"
  info "  docker build ${svc} → ${image}"
  docker build \
    --file "${dockerfile}" \
    --tag "${image}" \
    --tag "${image%%:*}:$(git rev-parse --short HEAD 2>/dev/null || echo 'local')" \
    .
  success "  ${image} built"
done

# ── Step 4 — Run migrations ───────────────────────────────────────────────────
info "Running database migrations..."
POSTGRES_CONTAINER=$(docker ps --filter "name=TafakoriTech_postgres" --format "{{.ID}}" | head -1)

if [ -z "${POSTGRES_CONTAINER}" ]; then
  warn "Postgres container not running — skipping migrations."
else
  run_migrations() {
    local db="$1"; shift
    local label="$1"; shift
    local files=("$@")
    for f in "${files[@]}"; do
      if [ -f "${ROOT_DIR}/${f}" ]; then
        info "  [${label}] ${f}"
        docker exec -i "${POSTGRES_CONTAINER}" \
          psql -U "${POSTGRES_USER}" -d "${db}" -v ON_ERROR_STOP=1 \
          < "${ROOT_DIR}/${f}"
      fi
    done
  }

  run_migrations sentinel sentinel \
    sentinel/migrations/001_init.sql \
    sentinel/migrations/002_refresh_token_rotation.sql \
    sentinel/migrations/003_mfa_devices_email.sql

  run_migrations persona persona \
    persona/migrations/001_init.sql \
    persona/migrations/002_seed.sql \
    persona/migrations/003_authz_version.sql 2>/dev/null || true

  run_migrations herald herald \
    herald/migrations/001_init.sql 2>/dev/null || true

  run_migrations marshal marshal \
    marshal/migrations/001_init.sql

  run_migrations pulse pulse \
    pulse/migrations/001_create_notifications.up.sql

  success "Migrations complete"
fi

# ── Step 5 — Redeploy the stack ───────────────────────────────────────────────
info "Redeploying stack via deploy.sh..."
cd "${INFRA_DIR}"
bash deploy.sh

# ── Step 6 — Force-update rebuilt services ────────────────────────────────────
# docker stack deploy won't restart a service if only the local image changed
# (same tag, no spec diff). --force + --image makes Swarm pull the new digest.
info "Force-updating rebuilt services..."
for svc in "${TARGETS[@]}"; do
  image="${SVC_IMAGE[$svc]}"
  swarm_svc="TafakoriTech_${svc}"
  if docker service inspect "${swarm_svc}" &>/dev/null; then
    info "  docker service update --force ${swarm_svc}"
    docker service update --force --image "${image}" "${swarm_svc}"
    success "  ${swarm_svc} updated"
  else
    warn "  ${swarm_svc} not found in swarm — skipping force-update"
  fi
done

# ── Step 7 — Wait and report ─────────────────────────────────────────────────
info "Waiting for services to stabilise (20 s)..."
sleep 20

echo ""
info "Stack status:"
docker stack services TafakoriTech 2>/dev/null || true

echo ""
success "Refresh complete."
echo -e "  Rebuilt:  ${TARGETS[*]}"
echo -e "  Commit:   $(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo 'unknown')"
echo -e "  Logs:     docker service logs -f TafakoriTech_<service>"

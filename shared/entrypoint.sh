#!/bin/sh
set -eu

# ── Postgres password → DATABASE_URL ─────────────────────────────────────────
if [ -f /run/secrets/postgres_password ]; then
  PG_PASS=$(cat /run/secrets/postgres_password)
  export DATABASE_URL="postgresql://${DATABASE_USER}:${PG_PASS}@${DATABASE_HOST:-postgres}:${DATABASE_PORT:-5432}/${DATABASE_NAME}"
fi

# ── JWT secret ────────────────────────────────────────────────────────────────
if [ -f /run/secrets/jwt_secret ]; then
  export JWT_SECRET=$(cat /run/secrets/jwt_secret)
fi

# ── Service API key (this service's own key) ──────────────────────────────────
if [ -f /run/secrets/api_key ]; then
  export API_KEY=$(cat /run/secrets/api_key)
fi

# ── Persona API key (used by sentinel to call persona) ───────────────────────
if [ -f /run/secrets/persona_api_key ]; then
  export PERSONA_API_KEY=$(cat /run/secrets/persona_api_key)
fi

# ── Pulse API key (used by sentinel to call pulse) ────────────────────────────
if [ -f /run/secrets/pulse_api_key ]; then
  export PULSE_API_KEY=$(cat /run/secrets/pulse_api_key)
fi

# ── SMTP password (used by pulse) ────────────────────────────────────────────
if [ -f /run/secrets/smtp_pass ]; then
  export SMTP_PASS=$(cat /run/secrets/smtp_pass)
fi

# ── SMS provider API keys (used by pulse) ─────────────────────────────────────
if [ -f /run/secrets/smsir_api_key ]; then
  export SMSIR_API_KEY=$(cat /run/secrets/smsir_api_key)
fi
if [ -f /run/secrets/ghasedak_api_key ]; then
  export GHASEDAK_API_KEY=$(cat /run/secrets/ghasedak_api_key)
fi

exec "$@"

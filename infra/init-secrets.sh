#!/usr/bin/env bash
set -euo pipefail

# Register all Docker Swarm secrets required by the stack.
# Run once on the Swarm manager before the first `docker stack deploy`.
#
# Usage: ./init-secrets.sh
#
# Each secret is prompted interactively. Press Ctrl+C to abort.

create_secret() {
  local name="$1"
  local prompt="$2"

  if docker secret inspect "$name" &>/dev/null; then
    echo "[skip] $name already exists — delete it first to rotate: docker secret rm $name"
    return
  fi

  printf "%s: " "$prompt"
  read -rs value
  echo
  printf '%s' "$value" | docker secret create "$name" -
  echo "[ok]   $name created"
}

echo "=== TafakoriTech — Docker Swarm Secrets Setup ==="
echo

create_secret postgres_password   "Postgres password"
create_secret jwt_secret          "JWT signing secret"
create_secret persona_api_key     "Persona service API key"
create_secret herald_api_key      "Herald service API key"
create_secret marshal_api_key     "Marshal service API key"
create_secret pulse_api_key       "Pulse service API key"
create_secret mfa_encryption_key  "MFA encryption key (32 bytes, prefix base64: for binary)"
create_secret recovery_pepper     "Recovery code pepper"
create_secret device_secret       "Device trust HMAC key"
create_secret altcha_hmac_key     "Altcha HMAC key"
create_secret smsir_api_key       "SMS.ir API key"
create_secret ghasedak_api_key    "Ghasedak API key"
create_secret smtp_pass           "SMTP password (Gmail App Password)"

echo
echo "=== All secrets registered. Run 'docker secret ls' to verify. ==="

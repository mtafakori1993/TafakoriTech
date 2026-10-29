#!/usr/bin/env bash
# Bootstrap a venv (if needed), ensure the `claude` CLI is installed, and
# launch the Commander API server.
set -euo pipefail
cd "$(dirname "$0")"

LOCAL_BIN="$HOME/.local/bin"
BASHRC="$HOME/.bashrc"

# Make sure ~/.local/bin is on PATH for this run (claude installs there).
case ":$PATH:" in
  *":$LOCAL_BIN:"*) ;;
  *) export PATH="$LOCAL_BIN:$PATH" ;;
esac

# --- ensure claude is installed --------------------------------------------
if ! command -v claude >/dev/null 2>&1; then
  echo "claude CLI not found — installing..."
  curl -fsSL https://claude.ai/install.sh | bash

  # Persist ~/.local/bin to PATH in .bashrc so claude is found next time.
  PATH_LINE='export PATH="$HOME/.local/bin:$PATH"'
  if [ -f "$BASHRC" ] && grep -qF "$PATH_LINE" "$BASHRC"; then
    echo "PATH already configured in $BASHRC"
  else
    echo "" >> "$BASHRC"
    echo "# Added by Commander: make the claude CLI available" >> "$BASHRC"
    echo "$PATH_LINE" >> "$BASHRC"
    echo "Added claude to PATH in $BASHRC"
  fi

  export PATH="$LOCAL_BIN:$PATH"
  if ! command -v claude >/dev/null 2>&1; then
    echo "ERROR: claude installation failed or is not on PATH ($LOCAL_BIN)." >&2
    exit 1
  fi
fi
echo "claude: $(command -v claude) ($(claude --version 2>/dev/null | head -1))"

# --- python environment ----------------------------------------------------
if [ ! -d .venv ]; then
  echo "Creating virtual environment..."
  python3 -m venv .venv
  .venv/bin/python -m ensurepip --upgrade >/dev/null
  .venv/bin/pip install --quiet --upgrade pip
  .venv/bin/pip install --quiet -r requirements.txt
fi

exec .venv/bin/python -m commander "$@"

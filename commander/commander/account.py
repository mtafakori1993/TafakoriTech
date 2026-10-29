"""Claude CLI authentication status, surfaced through the API.

Lets clients ask 'is Claude logged in on the server?' and get a clear
instruction to run `claude auth login` when it is not.
"""

from __future__ import annotations

import json
import subprocess

from .config import settings

LOGIN_HINT = (
    "Claude is not logged in on the server. Run `claude auth login` in the "
    "server's terminal (or type `! claude auth login` in a Claude Code session) "
    "and complete the browser sign-in, then retry."
)

# Substrings that indicate a run failed because authentication is required.
_LOGIN_SIGNALS = (
    "not logged in",
    "please run claude login",
    "claude auth login",
    "authentication_error",
    "invalid x-api-key",
    "invalid api key",
    "unauthorized",
    "oauth token has expired",
    "session expired",
)


def auth_status() -> dict:
    """Return Claude's auth status. Always includes a boolean `loggedIn`."""
    try:
        proc = subprocess.run(
            [settings.claude_bin, "auth", "status", "--json"],
            capture_output=True,
            text=True,
            timeout=20,
        )
    except FileNotFoundError:
        return {"loggedIn": False, "error": f"claude executable not found: {settings.claude_bin}"}
    except subprocess.TimeoutExpired:
        return {"loggedIn": False, "error": "timed out checking auth status"}

    out = proc.stdout.strip()
    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        # Not JSON -> treat as logged out, surface whatever was printed.
        text = (out or proc.stderr.strip())
        logged_in = "loggedin" in text.lower() and "true" in text.lower()
        data = {"loggedIn": logged_in, "raw": text}

    if not data.get("loggedIn"):
        data.setdefault("hint", LOGIN_HINT)
    return data


def looks_like_login_error(text: str | None) -> bool:
    if not text:
        return False
    low = text.lower()
    return any(sig in low for sig in _LOGIN_SIGNALS)

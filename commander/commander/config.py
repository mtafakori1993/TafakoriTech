"""Runtime configuration, sourced from environment variables."""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _split(value: str | None) -> list[str]:
    if not value:
        return []
    return [item.strip() for item in value.split(",") if item.strip()]


@dataclass
class Settings:
    # Path to the claude executable.
    claude_bin: str = field(default_factory=lambda: os.environ.get("COMMANDER_CLAUDE_BIN", "claude"))

    # Default permission mode passed to claude when a task does not override it.
    # One of: default, acceptEdits, bypassPermissions, plan
    default_permission_mode: str = field(
        default_factory=lambda: os.environ.get("COMMANDER_PERMISSION_MODE", "acceptEdits")
    )

    # Default model (empty -> let claude decide).
    default_model: str = field(default_factory=lambda: os.environ.get("COMMANDER_MODEL", ""))

    # Optional shared secret. If set, every request must send `X-API-Key: <key>`.
    api_key: str = field(default_factory=lambda: os.environ.get("COMMANDER_API_KEY", ""))

    # Restrict which directories tasks may run in. Empty -> any path allowed.
    # Comma-separated list of absolute path prefixes.
    allowed_roots: list[str] = field(
        default_factory=lambda: _split(os.environ.get("COMMANDER_ALLOWED_ROOTS"))
    )

    # How many event lines to retain per task in memory.
    max_events_per_task: int = field(
        default_factory=lambda: int(os.environ.get("COMMANDER_MAX_EVENTS", "5000"))
    )

    # Optional PostgreSQL URL for persisting terminal/shell session history.
    # Example: postgresql://user:pass@127.0.0.1:5432/commander
    db_url: str = field(default_factory=lambda: os.environ.get("COMMANDER_DB_URL", ""))


settings = Settings()

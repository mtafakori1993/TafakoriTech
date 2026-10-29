"""PostgreSQL archive for terminal and shell sessions.

Enabled only when COMMANDER_DB_URL is set. All functions are safe no-ops when
the pool is not initialised (missing env var or asyncpg not installed).
"""
from __future__ import annotations

import json
import time
from typing import Any

_pool: Any = None


async def init(db_url: str) -> None:
    global _pool
    try:
        import asyncpg  # noqa: PLC0415
    except ImportError:
        return
    _pool = await asyncpg.create_pool(db_url, min_size=1, max_size=8)
    async with _pool.acquire() as conn:
        await conn.execute("""
            CREATE TABLE IF NOT EXISTS terminal_sessions (
                id              TEXT PRIMARY KEY,
                type            TEXT NOT NULL,
                path            TEXT NOT NULL,
                model           TEXT,
                command         TEXT,
                permission_mode TEXT,
                status          TEXT NOT NULL DEFAULT 'running',
                created_at      DOUBLE PRECISION NOT NULL,
                ended_at        DOUBLE PRECISION,
                exit_code       INTEGER,
                final_screen    TEXT,
                final_ansi      TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_ts_created ON terminal_sessions (created_at DESC);
        """)


async def record_session(
    session_id: str,
    type_: str,
    path: str,
    model: str | None,
    command: list[str] | None,
    permission_mode: str | None,
    created_at: float,
) -> None:
    if not _pool:
        return
    await _pool.execute(
        """
        INSERT INTO terminal_sessions
            (id, type, path, model, command, permission_mode, created_at)
        VALUES ($1, $2, $3, $4, $5, $6, $7)
        ON CONFLICT (id) DO NOTHING
        """,
        session_id,
        type_,
        path,
        model,
        json.dumps(command) if command else None,
        permission_mode,
        created_at,
    )


async def finish_session(
    session_id: str,
    exit_code: int | None,
    screen: str,
    ansi: str,
) -> None:
    if not _pool:
        return
    await _pool.execute(
        """
        UPDATE terminal_sessions
        SET status = 'off',
            ended_at = $2,
            exit_code = $3,
            final_screen = $4,
            final_ansi = $5
        WHERE id = $1
        """,
        session_id,
        time.time(),
        exit_code,
        screen,
        ansi,
    )


async def list_sessions(limit: int = 200) -> list[dict]:
    if not _pool:
        return []
    rows = await _pool.fetch(
        """
        SELECT id, type, path, model, command, permission_mode,
               status, created_at, ended_at, exit_code
        FROM terminal_sessions
        ORDER BY created_at DESC
        LIMIT $1
        """,
        limit,
    )
    return [dict(r) for r in rows]


async def get_session(session_id: str) -> dict | None:
    if not _pool:
        return None
    row = await _pool.fetchrow(
        "SELECT * FROM terminal_sessions WHERE id = $1",
        session_id,
    )
    return dict(row) if row else None


def enabled() -> bool:
    return _pool is not None

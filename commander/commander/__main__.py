"""`python -m commander` — start the Commander API server with a QR banner."""

from __future__ import annotations

import os

import uvicorn

from .auth import print_banner, resolve_token


def main() -> None:
    host = os.environ.get("COMMANDER_HOST", "0.0.0.0")
    port = int(os.environ.get("COMMANDER_PORT", "8787"))

    resolve_token()
    print_banner(host, port)

    root_path = os.environ.get("ROOT_PATH", "")
    uvicorn.run("commander.main:app", host=host, port=port, log_level="info", root_path=root_path)


if __name__ == "__main__":
    main()

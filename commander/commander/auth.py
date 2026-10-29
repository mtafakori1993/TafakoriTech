"""Token generation and terminal display (QR code + copyable text)."""

from __future__ import annotations

import os
import secrets
from pathlib import Path

from .config import settings

TOKEN_FILE = Path(os.environ.get("COMMANDER_TOKEN_FILE", str(Path.home() / ".commander" / "token")))


def resolve_token() -> str:
    """Return the active token, generating and persisting one if needed.

    Priority: COMMANDER_API_KEY env > persisted token file > freshly generated.
    The resolved value is stored on `settings.api_key` so request auth uses it.
    """
    token = settings.api_key or _load_persisted() or _generate_and_persist()
    settings.api_key = token
    return token


def _load_persisted() -> str | None:
    try:
        value = TOKEN_FILE.read_text().strip()
        return value or None
    except OSError:
        return None


def _generate_and_persist() -> str:
    token = secrets.token_urlsafe(24)
    try:
        TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
        TOKEN_FILE.write_text(token)
        os.chmod(TOKEN_FILE, 0o600)
    except OSError:
        pass  # ephemeral token is still fine
    return token


def _local_ip() -> str:
    import socket

    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "127.0.0.1"


def print_banner(host: str, port: int) -> None:
    import os
    token = settings.api_key
    if public_host := os.environ.get("COMMANDER_PUBLIC_HOST"):
        public_port = int(os.environ.get("COMMANDER_PUBLIC_PORT", port))
        base_path = os.environ.get("ROOT_PATH", "")
        url = f"http://{public_host}:{public_port}{base_path}/?token={token}"
    else:
        display_host = _local_ip() if host in ("0.0.0.0", "::") else host
        url = f"http://{display_host}:{port}/?token={token}"

    bar = "─" * 64
    print("\n" + bar)
    print("  ⌘  COMMANDER  —  Claude-on-server API")
    print(bar)
    print("\n  Scan to open the dashboard (token embedded):\n")
    _print_qr(url)
    print("\n  Dashboard URL :", url)
    print("  API token     :", token)
    print("  Header        : X-API-Key: " + token)

    # Tell the operator immediately whether Claude needs a login.
    from .account import auth_status  # local import avoids a cycle at import time

    status = auth_status()
    if status.get("loggedIn"):
        who = status.get("email") or status.get("authMethod") or "ok"
        print(f"  Claude login  : ✓ logged in ({who})")
    else:
        print("  Claude login  : ✗ NOT logged in")
        print("                  → run:  claude auth login")

    print("\n  Keep this token secret. Override with COMMANDER_API_KEY.")
    print(bar + "\n")


def _print_qr(data: str) -> None:
    try:
        import qrcode

        qr = qrcode.QRCode(border=2)
        qr.add_data(data)
        qr.make(fit=True)
        qr.print_ascii(invert=True)
    except Exception:  # noqa: BLE001 — qrcode missing or render failure
        print("  (install 'qrcode' to show a scannable QR code here)")
        print("  " + data)

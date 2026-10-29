"""Persistent interactive Claude terminals backed by a PTY.

Unlike one-off tasks (which run `claude -p` once), a Terminal launches the
interactive `claude` TUI inside a pseudo-terminal at a chosen path and keeps it
alive. You turn it ON, feed it commands/keystrokes over time, watch the live
output, and turn it OFF.
"""

from __future__ import annotations

import asyncio
import fcntl
import os
import pty
import re
import signal
import struct
import termios
import time
import uuid
from collections import deque
from typing import AsyncIterator

import pyte

from .config import settings

# Sentinel: pushed to subscriber queues when the terminal turns off.
_EOF = object()

# Strip ANSI/OSC escape sequences so dialog text can be matched reliably.
_ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[a-zA-Z]|\x1b\][^\x07]*\x07|\x1b[()][AB0]")

# pyte color name -> base SGR foreground code (background is +10, bright is +60).
_SGR_BASE = {
    "black": 30, "red": 31, "green": 32, "brown": 33, "yellow": 33,
    "blue": 34, "magenta": 35, "cyan": 36, "white": 37,
}


def _color_codes(color: str, *, background: bool) -> str | None:
    """SGR code(s) for a pyte color name or 6-hex truecolor string."""
    if color == "default":
        return None
    bright = color.startswith("bright")
    name = color[len("bright"):] if bright else color
    base = _SGR_BASE.get(name)
    if base is not None:
        code = base + (60 if bright else 0) + (10 if background else 0)
        return str(code)
    if len(color) == 6:  # truecolor hex, e.g. "0a141e"
        try:
            r, g, b = (int(color[i:i + 2], 16) for i in (0, 2, 4))
        except ValueError:
            return None
        return f"{48 if background else 38};2;{r};{g};{b}"
    return None


def _sgr_codes(ch) -> list[str]:
    codes: list[str] = []
    if ch.bold:
        codes.append("1")
    if ch.reverse:
        codes.append("7")
    fg = _color_codes(ch.fg, background=False)
    if fg:
        codes.append(fg)
    bg = _color_codes(ch.bg, background=True)
    if bg:
        codes.append(bg)
    return codes


def _flatten(s: str) -> str:
    """ANSI-strip, drop all whitespace, lowercase — the TUI renders spaces as
    cursor moves, so words arrive jammed together (e.g. 'trustthisfolder')."""
    return re.sub(r"\s+", "", _ANSI_RE.sub("", s)).lower()


class Terminal:
    def __init__(
        self,
        path: str,
        model: str | None = None,
        permission_mode: str = "acceptEdits",
        skip_permissions: bool = False,
        auto_accept: bool = True,
        extra_args: list[str] | None = None,
        rows: int = 40,
        cols: int = 120,
        command: list[str] | None = None,
        id_prefix: str = "t_",
    ):
        self.id = id_prefix + uuid.uuid4().hex[:10]
        self.path = path
        self.model = model
        self.permission_mode = permission_mode
        self.skip_permissions = skip_permissions
        # A free-form command (e.g. a shell) launched verbatim instead of claude.
        # When set, the claude-specific first-run dialog handling is skipped.
        self.command = command
        self.auto_accept = auto_accept and command is None
        self.extra_args = extra_args or []
        self.rows = rows
        self.cols = cols

        # Auto-accept bookkeeping for the first-run TUI dialogs.
        self._recent = ""
        self._trust_sent = False
        self._bypass_sent = False

        self.status = "off"  # "on" | "off"
        self.created_at = time.time()
        self.started_at: float | None = None
        self.pid: int | None = None
        self.exit_code: int | None = None
        # Optional async callable(terminal) invoked once when the process exits.
        self._on_exit_callback = None

        self._master_fd: int | None = None
        self._proc: asyncio.subprocess.Process | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        # Ring buffer of recent output chunks for replaying to new viewers.
        self._buffer: deque[str] = deque(maxlen=400)
        self._subs: set[asyncio.Queue] = set()
        # VT emulator: the claude TUI repaints by moving the cursor, so we feed
        # the raw PTY stream through pyte to resolve it into a readable screen.
        self._screen = pyte.Screen(self.cols, self.rows)
        self._vt = pyte.Stream(self._screen)

    # -- views --------------------------------------------------------------

    def detail(self) -> dict:
        return {
            "id": self.id,
            "path": self.path,
            "model": self.model,
            "status": self.status,
            "pid": self.pid,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "exit_code": self.exit_code,
            "permission_mode": self.permission_mode,
            "skip_permissions": self.skip_permissions,
            "auto_accept": self.auto_accept,
            "command": self.command,
        }

    def snapshot(self) -> str:
        """The recent terminal output buffered so far (raw, with escapes)."""
        return "".join(self._buffer)

    def render(self) -> str:
        """The repainting TUI resolved into a plain, readable text screen."""
        lines = [line.rstrip() for line in self._screen.display]
        # Drop trailing blank lines so callers get a tight block of text.
        while lines and not lines[-1]:
            lines.pop()
        return "\n".join(lines)

    def render_ansi(self) -> str:
        """The rendered screen with each cell's real colors as SGR escapes.

        Lets clients show the TUI in its true colors instead of monochrome.
        """
        screen = self._screen
        out: list[str] = []
        for y in range(screen.lines):
            row = screen.buffer[y]
            parts: list[str] = []
            prev: tuple | None = None
            for x in range(screen.columns):
                ch = row[x]
                sig = (ch.fg, ch.bg, ch.bold, ch.reverse)
                if sig != prev:
                    codes = _sgr_codes(ch)
                    parts.append("\x1b[0m" if not codes else "\x1b[0;" + ";".join(codes) + "m")
                    prev = sig
                parts.append(ch.data or " ")
            parts.append("\x1b[0m")
            out.append("".join(parts).rstrip())
        while out and out[-1] in ("", "\x1b[0m"):
            out.pop()
        return "\n".join(out)

    # -- lifecycle (on / off) ----------------------------------------------

    async def turn_on(self) -> None:
        if self.status == "on":
            return
        self._loop = asyncio.get_running_loop()
        master_fd, slave_fd = pty.openpty()

        # Give the PTY a sensible window size so the TUI lays out correctly.
        winsize = struct.pack("HHHH", self.rows, self.cols, 0, 0)
        fcntl.ioctl(slave_fd, termios.TIOCSWINSZ, winsize)

        if self.command is not None:
            # Free terminal: run whatever was asked (e.g. an interactive shell).
            cmd = list(self.command)
        else:
            cmd = [settings.claude_bin]
            if self.skip_permissions:
                cmd.append("--dangerously-skip-permissions")
            else:
                # acceptEdits auto-approves file edits without the bypass warning
                # dialog, so the terminal is usable with only the trust prompt.
                cmd += ["--permission-mode", self.permission_mode]
            if self.model:
                cmd += ["--model", self.model]
            cmd += self.extra_args

        env = os.environ.copy()
        env["TERM"] = "xterm-256color"

        def _preexec() -> None:
            # Make the child a session leader owning this PTY as its
            # controlling terminal — interactive TUIs require that.
            # NOTE: subprocess dups the passed fds onto 0/1/2 *after* this
            # runs, so we must ioctl the slave fd itself, not fd 0.
            os.setsid()
            fcntl.ioctl(slave_fd, termios.TIOCSCTTY, 0)

        self._proc = await asyncio.create_subprocess_exec(
            *cmd,
            cwd=self.path,
            stdin=slave_fd,
            stdout=slave_fd,
            stderr=slave_fd,
            env=env,
            preexec_fn=_preexec,
            close_fds=True,
        )
        os.close(slave_fd)  # parent keeps only the master side
        self._master_fd = master_fd
        self.pid = self._proc.pid
        self.exit_code = None
        self.status = "on"
        self.started_at = time.time()

        self._loop.add_reader(master_fd, self._on_readable)
        asyncio.create_task(self._watch_exit())

    def _on_readable(self) -> None:
        assert self._master_fd is not None
        try:
            data = os.read(self._master_fd, 65536)
        except OSError:
            data = b""
        if not data:  # PTY closed (process exiting)
            if self._loop:
                self._loop.remove_reader(self._master_fd)
            return
        text = data.decode("utf-8", "replace")
        self._buffer.append(text)
        self._vt.feed(text)
        for q in list(self._subs):
            q.put_nowait(text)
        if self.auto_accept:
            self._maybe_accept_dialogs(text)

    def _maybe_accept_dialogs(self, text: str) -> None:
        """Detect the first-run TUI dialogs and answer them automatically.

        Writes are sent as raw bytes here (no shell escaping), so the key
        sequences are reliable: Enter accepts the trust prompt; Down+Enter
        selects "Yes, I accept" on the bypass-permissions warning.
        """
        if self._master_fd is None:
            return
        self._recent = (self._recent + text)[-6000:]
        flat = _flatten(self._recent)
        try:
            if not self._trust_sent and "trustthisfolder" in flat:
                self._trust_sent = True
                os.write(self._master_fd, b"\r")  # default option is "Yes, trust"
            if (
                not self._bypass_sent
                and "bypasspermissionsmode" in flat
                and "yes,iaccept" in flat
            ):
                self._bypass_sent = True
                os.write(self._master_fd, b"\x1b[B\r")  # down arrow, then Enter
        except OSError:
            pass

    async def _watch_exit(self) -> None:
        assert self._proc is not None
        self.exit_code = await self._proc.wait()
        self.status = "off"
        if self._master_fd is not None and self._loop:
            try:
                self._loop.remove_reader(self._master_fd)
            except (ValueError, OSError):
                pass
            try:
                os.close(self._master_fd)
            except OSError:
                pass
            self._master_fd = None
        for q in list(self._subs):
            q.put_nowait(_EOF)
        if self._on_exit_callback is not None:
            asyncio.create_task(self._on_exit_callback(self))

    async def turn_off(self) -> bool:
        if self.status != "on" or self.pid is None:
            return False
        try:
            os.killpg(os.getpgid(self.pid), signal.SIGTERM)
        except ProcessLookupError:
            return False
        if self._proc is not None:
            try:
                await asyncio.wait_for(self._proc.wait(), timeout=5)
            except asyncio.TimeoutError:
                try:
                    os.killpg(os.getpgid(self.pid), signal.SIGKILL)
                except ProcessLookupError:
                    pass
        self.status = "off"
        return True

    # -- I/O ----------------------------------------------------------------

    def send(self, data: str, submit: bool = True) -> None:
        """Write text to the terminal. `submit` appends Enter (carriage return)."""
        if self.status != "on" or self._master_fd is None:
            raise RuntimeError("terminal is off")
        payload = data + "\r" if submit else data
        os.write(self._master_fd, payload.encode("utf-8"))

    def resize(self, rows: int, cols: int) -> None:
        self.rows, self.cols = rows, cols
        self._screen.resize(rows, cols)
        if self._master_fd is not None:
            winsize = struct.pack("HHHH", rows, cols, 0, 0)
            fcntl.ioctl(self._master_fd, termios.TIOCSWINSZ, winsize)

    async def subscribe(self) -> AsyncIterator[str]:
        q: asyncio.Queue = asyncio.Queue()
        for chunk in list(self._buffer):  # replay what's on screen so far
            yield chunk
        if self.status != "on":
            return
        self._subs.add(q)
        try:
            while True:
                item = await q.get()
                if item is _EOF:
                    break
                yield item
        finally:
            self._subs.discard(q)


class TerminalManager:
    def __init__(self) -> None:
        self._terminals: dict[str, Terminal] = {}

    def create(self, **kwargs) -> Terminal:
        term = Terminal(**kwargs)
        self._terminals[term.id] = term
        return term

    def get(self, terminal_id: str) -> Terminal | None:
        return self._terminals.get(terminal_id)

    def list(self) -> list[Terminal]:
        return sorted(self._terminals.values(), key=lambda t: t.created_at, reverse=True)

    def remove(self, terminal_id: str) -> bool:
        return self._terminals.pop(terminal_id, None) is not None

    async def shutdown(self) -> None:
        for term in self._terminals.values():
            await term.turn_off()


terminals = TerminalManager()

# A second manager for free shell terminals (any command, not just claude).
shells = TerminalManager()


def default_shell() -> list[str]:
    """An interactive login shell to launch when no command is given."""
    shell = os.environ.get("SHELL") or "/bin/bash"
    return [shell, "-i"]

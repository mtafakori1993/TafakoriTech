"""Commander HTTP API (v1).

Run Claude Code on this server in any directory, send it commands, and watch
the output live over Server-Sent Events.

OpenAPI spec:  /openapi.json  (Swagger UI is served by the Kong API gateway)
All v1 endpoints are served under the /v1 prefix and require the API token.
"""

from __future__ import annotations

import asyncio
import json
import os
import signal
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import StreamingResponse
from fastapi.staticfiles import StaticFiles

from .account import auth_status
from .auth import resolve_token
from .config import settings
from .models import (
    CommandRequest,
    CommandResponse,
    ConversationDetail,
    ConversationSummary,
    CreateSessionRequest,
    CreateShellRequest,
    CreateTaskRequest,
    CreateTerminalRequest,
    ExecRequest,
    ExecResponse,
    Message,
    SendMessageRequest,
    SessionDetail,
    TaskDetail,
    TaskSummary,
    TerminalInput,
)
from .runner import Conversation, Session, conversations, manager, sessions
from .terminal import Terminal, default_shell, shells, terminals
from . import archive

STATIC_DIR = Path(__file__).resolve().parent.parent / "static"

API_DESCRIPTION = """
**Commander** runs Claude Code on this server in any path you choose.

* **Conversations** — unified chat API with automatic context management across messages.
* **Tasks** — fire a one-off `claude -p` run in a directory and stream its output.
* **Sessions** — a stateful, REPL-like conversation; `/model <id>` and `/clear` work here.
* **Terminals** — turn an interactive `claude` process **on/off** in a PTY and pass
  it commands/keystrokes live; every slash command (`/model`, `/clear`, …) works
  natively, no API key required.
* **Shells** — free terminals: launch any command (a shell by default) in a PTY,
  send it keystrokes, and watch the live output — or use one-off `exec` to run a
  single command and capture its output.
* **Auth** — Claude login status; tells you when to run `claude auth login`.

### Authentication
Every `/v1` endpoint requires the API token (shown as a QR code in the server
terminal on startup). Send it as the `X-API-Key` header, or — for SSE streams
that cannot set headers — as the `?token=` query parameter.
"""

OPENAPI_TAGS = [
    {"name": "Conversations", "description": "Unified chat API with automatic context across messages."},
    {"name": "Tasks", "description": "One-off Claude runs in a directory, with live streaming."},
    {"name": "Sessions", "description": "Stateful conversations. Supports /model and /clear."},
    {"name": "Terminals", "description": "Persistent interactive Claude in a PTY (on/off)."},
    {"name": "Shells", "description": "Free terminals — run any command/shell on the server in a PTY."},
    {"name": "Auth", "description": "Claude login status; tells you when to run `claude auth login`."},
    {"name": "System", "description": "Health and service metadata."},
]


@asynccontextmanager
async def lifespan(app: FastAPI):
    resolve_token()
    if settings.db_url:
        await archive.init(settings.db_url)
    yield
    await manager.shutdown()
    await terminals.shutdown()
    await shells.shutdown()


app = FastAPI(
    title="Commander API",
    version="1.0.0",
    description=API_DESCRIPTION,
    openapi_tags=OPENAPI_TAGS,
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
)


# --- auth ------------------------------------------------------------------

async def require_key(
    x_api_key: str | None = Header(default=None, description="API token"),
    token: str | None = Query(default=None, description="API token (for SSE streams)"),
) -> None:
    # Accept the token via header (X-API-Key) or, for SSE/EventSource which
    # cannot set headers, via the `?token=` query parameter.
    provided = x_api_key or token
    if settings.api_key and provided != settings.api_key:
        raise HTTPException(status_code=401, detail="invalid or missing token")


# --- validation helpers ----------------------------------------------------

def _validate_path(raw: str) -> str:
    path = os.path.abspath(os.path.expanduser(raw))
    if not os.path.isdir(path):
        raise HTTPException(status_code=400, detail=f"path is not an existing directory: {path}")
    if settings.allowed_roots:
        if not any(path == root or path.startswith(root.rstrip("/") + "/") for root in settings.allowed_roots):
            raise HTTPException(status_code=403, detail="path is outside the allowed roots")
    return path


# --- unversioned health ----------------------------------------------------

@app.get("/health", tags=["System"])
async def health() -> dict:
    status = await asyncio.to_thread(auth_status)
    return {
        "status": "ok",
        "version": app.version,
        "claude_bin": settings.claude_bin,
        "claude_logged_in": bool(status.get("loggedIn")),
    }


# All functional endpoints live under /v1 and require the token.
v1 = APIRouter(prefix="/v1", dependencies=[Depends(require_key)])


# --- auth status -----------------------------------------------------------

@v1.get("/auth/status", tags=["Auth"], summary="Is Claude logged in on the server?")
async def get_auth_status() -> dict:
    """Report Claude's login state. If logged out, includes a `hint` telling
    you to run `claude auth login`."""
    return await asyncio.to_thread(auth_status)


# --- tasks -----------------------------------------------------------------

@v1.post("/tasks", response_model=TaskSummary, tags=["Tasks"], summary="Start a task")
async def create_task(req: CreateTaskRequest) -> TaskSummary:
    req.path = _validate_path(req.path)
    task = manager.create(req)
    return task.summary()


@v1.get("/tasks", response_model=list[TaskSummary], tags=["Tasks"], summary="List tasks")
async def list_tasks() -> list[TaskSummary]:
    return [t.summary() for t in manager.list()]


@v1.get("/tasks/{task_id}", response_model=TaskDetail, tags=["Tasks"], summary="Get task + report")
async def get_task(task_id: str) -> TaskDetail:
    task = manager.get(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="task not found")
    return task.detail()


@v1.get("/tasks/{task_id}/events", tags=["Tasks"], summary="Raw buffered events")
async def get_events(task_id: str) -> list:
    task = manager.get(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="task not found")
    return task.history()


@v1.get("/tasks/{task_id}/stream", tags=["Tasks"], summary="Live event stream (SSE)")
async def stream_task(task_id: str) -> StreamingResponse:
    task = manager.get(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="task not found")

    async def gen():
        async for event in task.subscribe():
            yield f"data: {json.dumps(event)}\n\n"
        yield "event: end\ndata: {}\n\n"

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@v1.post("/tasks/{task_id}/stop", tags=["Tasks"], summary="Stop a running task")
async def stop_task(task_id: str) -> dict:
    task = manager.get(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="task not found")
    stopped = await task.stop()
    return {"stopped": stopped, "status": task.status.value}


# --- models ----------------------------------------------------------------

# --- conversations (unified chat API with automatic context) ------------------

def _conversation_summary(conv: Conversation) -> ConversationSummary:
    return ConversationSummary(
        id=conv.id,
        path=conv.path,
        model=conv.model,
        permission_mode=conv.permission_mode,
        created_at=conv.created_at,
        message_count=len(conv.messages),
    )


def _conversation_detail(conv: Conversation) -> ConversationDetail:
    messages = [
        Message(
            id=msg["id"],
            role=msg["role"],
            content=msg["content"],
            created_at=msg["created_at"],
            task_id=msg.get("task_id"),
        )
        for msg in conv.messages
    ]
    return ConversationDetail(
        id=conv.id,
        path=conv.path,
        model=conv.model,
        permission_mode=conv.permission_mode,
        created_at=conv.created_at,
        messages=messages,
        task_ids=list(conv.task_ids),
    )


@v1.post("/conversations", response_model=ConversationSummary, tags=["Conversations"], summary="Create a conversation")
async def create_conversation(req: CreateSessionRequest) -> ConversationSummary:
    """Create a new conversation with automatic context management.

    All messages sent to this conversation will automatically include context
    from previous messages in the same conversation.
    """
    path = _validate_path(req.path)
    conv = conversations.create(
        path=path,
        model=req.model or (settings.default_model or None),
        permission_mode=req.permission_mode or settings.default_permission_mode,
    )
    return _conversation_summary(conv)


@v1.get("/conversations", response_model=list[ConversationSummary], tags=["Conversations"], summary="List conversations")
async def list_conversations() -> list[ConversationSummary]:
    """List all active conversations."""
    return [_conversation_summary(c) for c in conversations.list()]


@v1.get("/conversations/{conversation_id}", response_model=ConversationDetail, tags=["Conversations"], summary="Get conversation + full message history")
async def get_conversation(conversation_id: str) -> ConversationDetail:
    """Get a conversation with its complete message history."""
    conv = conversations.get(conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="conversation not found")
    return _conversation_detail(conv)


@v1.post("/conversations/{conversation_id}/messages", response_model=TaskSummary, tags=["Conversations"], summary="Send a message")
async def send_message(conversation_id: str, req: SendMessageRequest) -> TaskSummary:
    """Send a message to a conversation.

    Claude's response will automatically include context from all previous
    messages in this conversation. The response is returned as a task that
    can be streamed or polled.
    """
    conv = conversations.get(conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="conversation not found")

    task = conv.send_message(
        req.message,
        model=req.model,
        permission_mode=req.permission_mode,
    )
    return task.summary()


@v1.get("/conversations/{conversation_id}/messages/{message_id}/stream", tags=["Conversations"], summary="Stream a message response (SSE)")
async def stream_message(conversation_id: str, message_id: str) -> StreamingResponse:
    """Stream the response to a specific message in a conversation.

    This returns a Server-Sent Events stream of the Claude response.
    Connect to this after sending a message to watch the response in real-time.
    """
    conv = conversations.get(conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="conversation not found")

    # Find the task associated with this message
    task = None
    for msg in conv.messages:
        if msg["id"] == message_id and msg["task_id"]:
            task = manager.get(msg["task_id"])
            break

    if not task:
        raise HTTPException(status_code=404, detail="message not found")

    async def gen():
        async for event in task.subscribe():
            yield f"data: {json.dumps(event)}\n\n"
        yield "event: end\ndata: {}\n\n"

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@v1.post("/conversations/{conversation_id}/clear", tags=["Conversations"], summary="Clear conversation history")
async def clear_conversation(conversation_id: str) -> ConversationSummary:
    """Clear the message history and start fresh in a conversation.

    This resets the Claude session so the next message starts a new context.
    """
    conv = conversations.get(conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="conversation not found")
    conv.clear()
    return _conversation_summary(conv)


# --- sessions (stateful, REPL-like) ----------------------------------------

def _session_detail(s: Session) -> SessionDetail:
    return SessionDetail(
        id=s.id,
        path=s.path,
        model=s.model,
        permission_mode=s.permission_mode,
        claude_session_id=s.claude_session_id,
        created_at=s.created_at,
        task_ids=list(s.task_ids),
    )


@v1.post("/sessions", response_model=SessionDetail, tags=["Sessions"], summary="Create a session")
async def create_session(req: CreateSessionRequest) -> SessionDetail:
    path = _validate_path(req.path)
    s = sessions.create(
        path=path,
        model=req.model or (settings.default_model or None),
        permission_mode=req.permission_mode or settings.default_permission_mode,
    )
    return _session_detail(s)


@v1.get("/sessions", response_model=list[SessionDetail], tags=["Sessions"], summary="List sessions")
async def list_sessions() -> list[SessionDetail]:
    return [_session_detail(s) for s in sessions.list()]


@v1.get("/sessions/{session_id}", response_model=SessionDetail, tags=["Sessions"], summary="Get a session")
async def get_session(session_id: str) -> SessionDetail:
    s = sessions.get(session_id)
    if not s:
        raise HTTPException(status_code=404, detail="session not found")
    return _session_detail(s)


@v1.post(
    "/sessions/{session_id}/commands",
    response_model=CommandResponse,
    tags=["Sessions"],
    summary="Send a command or slash command (/model, /clear)",
)
async def run_command(session_id: str, req: CommandRequest) -> CommandResponse:
    s = sessions.get(session_id)
    if not s:
        raise HTTPException(status_code=404, detail="session not found")

    text = req.command.strip()

    # /clear — drop conversation context; next command starts a new session.
    if text == "/clear":
        s.clear()
        return CommandResponse(
            kind="ack",
            message="Session cleared. The next command starts a fresh Claude session.",
            session=_session_detail(s),
        )

    # /model [name] — switch the session's model. For an interactive picker with
    # the live model list, type /model in a terminal (no API key required).
    if text == "/model" or text.startswith("/model "):
        arg = text[len("/model"):].strip()
        if not arg:
            return CommandResponse(
                kind="ack",
                message=(
                    f"Current model: {s.model or 'default'}. Switch with `/model <id>` "
                    "(e.g. opus, sonnet, haiku, fable), or open a terminal and type "
                    "/model for Claude's interactive picker."
                ),
                session=_session_detail(s),
            )
        s.model = arg
        return CommandResponse(
            kind="ack",
            message=f"Model set to '{arg}' for this session.",
            session=_session_detail(s),
        )

    if text == "/help":
        return CommandResponse(
            kind="ack",
            message="Commands: /model <id> (switch), /clear (new session). "
            "Anything else is sent to Claude as a prompt.",
            session=_session_detail(s),
        )

    # Otherwise: run it as a prompt, resuming this session's context.
    task = s.run(text)
    return CommandResponse(kind="task", task=task.summary(), session=_session_detail(s))


# --- terminals (persistent interactive claude, on/off) ---------------------

def _attach_archive(term: Terminal, type_: str) -> None:
    """Register archive hooks on a newly-created terminal/shell."""
    async def _on_exit(t: Terminal) -> None:
        await archive.finish_session(t.id, t.exit_code, t.render(), t.render_ansi())

    term._on_exit_callback = _on_exit
    asyncio.create_task(archive.record_session(
        term.id, type_, term.path, term.model,
        term.command, term.permission_mode, term.created_at,
    ))


@v1.post("/terminals", tags=["Terminals"], summary="Turn ON an interactive claude terminal")
async def create_terminal(req: CreateTerminalRequest) -> dict:
    """Launch interactive claude in a PTY at the given path."""
    path = _validate_path(req.path)
    term = terminals.create(
        path=path,
        model=req.model or (settings.default_model or None),
        permission_mode=req.permission_mode,
        skip_permissions=req.skip_permissions,
        auto_accept=req.auto_accept,
        extra_args=req.extra_args,
        rows=req.rows,
        cols=req.cols,
    )
    await term.turn_on()
    _attach_archive(term, "terminal")
    return term.detail()


@v1.get("/terminals", tags=["Terminals"], summary="List terminals")
async def list_terminals() -> list[dict]:
    return [t.detail() for t in terminals.list()]


@v1.get("/terminals/{terminal_id}", tags=["Terminals"], summary="Get terminal + screen snapshot")
async def get_terminal(terminal_id: str) -> dict:
    term = terminals.get(terminal_id)
    if not term:
        raise HTTPException(status_code=404, detail="terminal not found")
    return {
        **term.detail(),
        "snapshot": term.snapshot(),
        "text": term.render(),
        "ansi": term.render_ansi(),
    }


@v1.post("/terminals/{terminal_id}/on", tags=["Terminals"], summary="Turn ON (start/restart)")
async def terminal_on(terminal_id: str) -> dict:
    term = terminals.get(terminal_id)
    if not term:
        raise HTTPException(status_code=404, detail="terminal not found")
    await term.turn_on()
    return term.detail()


@v1.post("/terminals/{terminal_id}/off", tags=["Terminals"], summary="Turn OFF (stop)")
async def terminal_off(terminal_id: str) -> dict:
    term = terminals.get(terminal_id)
    if not term:
        raise HTTPException(status_code=404, detail="terminal not found")
    turned_off = await term.turn_off()
    return {"turned_off": turned_off, **term.detail()}


@v1.post("/terminals/{terminal_id}/input", tags=["Terminals"], summary="Send a command/keystrokes")
async def terminal_input(terminal_id: str, body: TerminalInput) -> dict:
    """Pass a command/keystrokes to the live terminal."""
    term = terminals.get(terminal_id)
    if not term:
        raise HTTPException(status_code=404, detail="terminal not found")
    try:
        term.send(body.data, submit=body.submit)
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc))
    return {"ok": True, "status": term.status}


@v1.get("/terminals/{terminal_id}/stream", tags=["Terminals"], summary="Live terminal output (SSE)")
async def terminal_stream(terminal_id: str) -> StreamingResponse:
    term = terminals.get(terminal_id)
    if not term:
        raise HTTPException(status_code=404, detail="terminal not found")

    async def gen():
        async for chunk in term.subscribe():
            payload = {"chunk": chunk, "screen": term.render(), "ansi": term.render_ansi()}
            yield f"data: {json.dumps(payload)}\n\n"
        yield "event: end\ndata: {}\n\n"

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@v1.delete("/terminals/{terminal_id}", tags=["Terminals"], summary="Turn off and remove")
async def delete_terminal(terminal_id: str) -> dict:
    term = terminals.get(terminal_id)
    if not term:
        raise HTTPException(status_code=404, detail="terminal not found")
    await term.turn_off()
    terminals.remove(terminal_id)
    return {"deleted": True}


# --- shells (free terminals: any command in a PTY) -------------------------

@v1.post("/shells", tags=["Shells"], summary="Open a free terminal (any command)")
async def create_shell(req: CreateShellRequest) -> dict:
    """Launch an interactive shell (or any command) in a PTY at the given path."""
    path = _validate_path(req.path)
    term = shells.create(
        path=path,
        command=req.command or default_shell(),
        rows=req.rows,
        cols=req.cols,
        id_prefix="sh_",
    )
    await term.turn_on()
    _attach_archive(term, "shell")
    return term.detail()


@v1.get("/shells", tags=["Shells"], summary="List free terminals")
async def list_shells() -> list[dict]:
    return [t.detail() for t in shells.list()]


@v1.get("/shells/{shell_id}", tags=["Shells"], summary="Get shell + screen snapshot")
async def get_shell(shell_id: str) -> dict:
    term = shells.get(shell_id)
    if not term:
        raise HTTPException(status_code=404, detail="shell not found")
    return {
        **term.detail(),
        "snapshot": term.snapshot(),
        "text": term.render(),
        "ansi": term.render_ansi(),
    }


@v1.post("/shells/{shell_id}/on", tags=["Shells"], summary="Turn ON (start/restart)")
async def shell_on(shell_id: str) -> dict:
    term = shells.get(shell_id)
    if not term:
        raise HTTPException(status_code=404, detail="shell not found")
    await term.turn_on()
    return term.detail()


@v1.post("/shells/{shell_id}/off", tags=["Shells"], summary="Turn OFF (stop)")
async def shell_off(shell_id: str) -> dict:
    term = shells.get(shell_id)
    if not term:
        raise HTTPException(status_code=404, detail="shell not found")
    turned_off = await term.turn_off()
    return {"turned_off": turned_off, **term.detail()}


@v1.post("/shells/{shell_id}/input", tags=["Shells"], summary="Send a command/keystrokes")
async def shell_input(shell_id: str, body: TerminalInput) -> dict:
    """Pass a command/keystrokes to the live shell."""
    term = shells.get(shell_id)
    if not term:
        raise HTTPException(status_code=404, detail="shell not found")
    try:
        term.send(body.data, submit=body.submit)
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc))
    return {"ok": True, "status": term.status}


@v1.get("/shells/{shell_id}/stream", tags=["Shells"], summary="Live shell output (SSE)")
async def shell_stream(shell_id: str) -> StreamingResponse:
    term = shells.get(shell_id)
    if not term:
        raise HTTPException(status_code=404, detail="shell not found")

    async def gen():
        async for chunk in term.subscribe():
            payload = {"chunk": chunk, "screen": term.render(), "ansi": term.render_ansi()}
            yield f"data: {json.dumps(payload)}\n\n"
        yield "event: end\ndata: {}\n\n"

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@v1.delete("/shells/{shell_id}", tags=["Shells"], summary="Turn off and remove")
async def delete_shell(shell_id: str) -> dict:
    term = shells.get(shell_id)
    if not term:
        raise HTTPException(status_code=404, detail="shell not found")
    await term.turn_off()
    shells.remove(shell_id)
    return {"deleted": True}


@v1.post("/exec", response_model=ExecResponse, tags=["Shells"], summary="Run one command, capture output")
async def exec_command(req: ExecRequest) -> ExecResponse:
    """Run a single shell command on the server and return its captured output.

    Unlike a shell terminal, this is a one-off: it waits for the command to
    finish (or time out) and returns stdout, stderr and the exit code.
    """
    path = _validate_path(req.path)
    started = asyncio.get_event_loop().time()
    # New session => the command and any children share a process group we can
    # kill as a unit on timeout (otherwise a grandchild keeps the pipe open).
    proc = await asyncio.create_subprocess_shell(
        req.command,
        cwd=path,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        start_new_session=True,
    )
    timed_out = False
    timeout = req.timeout if (req.timeout and req.timeout > 0) else None
    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        timed_out = True
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            proc.kill()
        out, err = await proc.communicate()
    return ExecResponse(
        command=req.command,
        path=path,
        exit_code=proc.returncode,
        stdout=out.decode("utf-8", "replace"),
        stderr=err.decode("utf-8", "replace"),
        timed_out=timed_out,
        duration=asyncio.get_event_loop().time() - started,
    )


# --- archive ------------------------------------------------------------------

@v1.get("/archive", tags=["Archive"], summary="List archived terminal/shell sessions")
async def list_archive() -> list[dict]:
    """Returns saved sessions from PostgreSQL. Empty list when DB is not configured."""
    return await archive.list_sessions()


@v1.get("/archive/status", tags=["Archive"], summary="Is PostgreSQL archive enabled?")
async def archive_status() -> dict:
    return {"enabled": archive.enabled()}


@v1.get("/archive/{session_id}", tags=["Archive"], summary="Get an archived session with its final output")
async def get_archived_session(session_id: str) -> dict:
    s = await archive.get_session(session_id)
    if not s:
        raise HTTPException(status_code=404, detail="archived session not found")
    return s


app.include_router(v1)


# Dashboard served at "/" (no auth on static assets themselves).
if STATIC_DIR.is_dir():
    app.mount("/", StaticFiles(directory=str(STATIC_DIR), html=True), name="static")

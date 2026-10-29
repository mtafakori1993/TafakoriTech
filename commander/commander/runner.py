"""Spawns and supervises `claude` subprocesses, broadcasting live output."""

from __future__ import annotations

import asyncio
import json
import os
import time
import uuid
from collections import deque
from typing import Any, AsyncIterator, Callable

from .account import LOGIN_HINT, looks_like_login_error
from .config import settings
from .models import CreateTaskRequest, TaskDetail, TaskStatus, TaskSummary

# Sentinel pushed onto subscriber queues when a task finishes.
_DONE = object()


class Task:
    """A single Claude run plus its live event stream and buffered history."""

    def __init__(self, req: CreateTaskRequest, on_session: Callable[[str], None] | None = None):
        self.id = uuid.uuid4().hex[:12]
        self._on_session = on_session
        self.prompt = req.prompt
        self.path = req.path
        self.model = req.model or (settings.default_model or None)
        self.permission_mode = req.permission_mode or settings.default_permission_mode
        self.session_id = req.session_id
        self.resume_id = req.session_id

        self.status: TaskStatus = TaskStatus.running
        self.created_at = time.time()
        self.ended_at: float | None = None
        self.exit_code: int | None = None
        self.error: str | None = None
        self.needs_login = False
        self.result: dict[str, Any] | None = None
        self._stderr_parts: list[str] = []

        # Buffered history so late subscribers can replay the run so far.
        self._history: deque[dict[str, Any]] = deque(maxlen=settings.max_events_per_task)
        self._report_parts: list[str] = []
        self._subscribers: set[asyncio.Queue] = set()
        self._proc: asyncio.subprocess.Process | None = None
        self._task: asyncio.Task | None = None

    # -- public views -------------------------------------------------------

    def summary(self) -> TaskSummary:
        return TaskSummary(
            id=self.id,
            status=self.status,
            path=self.path,
            prompt=self.prompt,
            model=self.model,
            session_id=self.session_id,
            created_at=self.created_at,
            ended_at=self.ended_at,
            exit_code=self.exit_code,
        )

    def detail(self) -> TaskDetail:
        return TaskDetail(
            **self.summary().model_dump(),
            report="".join(self._report_parts),
            result=self.result,
            error=self.error,
            needs_login=self.needs_login,
        )

    def history(self) -> list[dict[str, Any]]:
        return list(self._history)

    # -- event plumbing -----------------------------------------------------

    def _emit(self, event: dict[str, Any]) -> None:
        self._history.append(event)
        for q in list(self._subscribers):
            q.put_nowait(event)

    async def subscribe(self) -> AsyncIterator[dict[str, Any]]:
        """Yield buffered history, then live events until the task ends."""
        q: asyncio.Queue = asyncio.Queue()
        # Replay what already happened.
        for event in list(self._history):
            yield event
        if self.status != TaskStatus.running:
            return
        self._subscribers.add(q)
        try:
            while True:
                item = await q.get()
                if item is _DONE:
                    break
                yield item
        finally:
            self._subscribers.discard(q)

    # -- lifecycle ----------------------------------------------------------

    def start(self) -> None:
        self._task = asyncio.create_task(self._run())

    async def _run(self) -> None:
        cmd = [
            settings.claude_bin,
            "-p",
            self.prompt,
            "--output-format",
            "stream-json",
            "--verbose",
            "--include-partial-messages",
            "--permission-mode",
            self.permission_mode,
        ]
        if self.model:
            cmd += ["--model", self.model]
        if self.resume_id:
            cmd += ["--resume", self.resume_id]

        self._emit({"type": "commander", "event": "started", "command": cmd, "path": self.path})

        try:
            self._proc = await asyncio.create_subprocess_exec(
                *cmd,
                cwd=self.path,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=os.environ.copy(),
            )
        except FileNotFoundError:
            self._fail(f"claude executable not found: {settings.claude_bin}")
            return
        except NotADirectoryError:
            self._fail(f"path is not a directory: {self.path}")
            return
        except Exception as exc:  # noqa: BLE001
            self._fail(f"failed to start: {exc}")
            return

        stderr_task = asyncio.create_task(self._drain_stderr(self._proc.stderr))
        assert self._proc.stdout is not None
        try:
            async for raw in self._proc.stdout:
                line = raw.decode("utf-8", "replace").strip()
                if not line:
                    continue
                self._handle_line(line)
        except Exception as exc:  # noqa: BLE001
            self.error = f"stream error: {exc}"

        rc = await self._proc.wait()
        await stderr_task
        self.exit_code = rc

        if self.status == TaskStatus.stopped:
            pass  # already terminal
        elif rc == 0:
            self.status = TaskStatus.completed
        else:
            self.status = TaskStatus.failed
            if not self.error:
                self.error = f"claude exited with code {rc}"

        # If the run failed because Claude isn't authenticated, say so clearly.
        if self.status == TaskStatus.failed:
            haystack = "\n".join(
                self._stderr_parts
                + self._report_parts
                + [json.dumps(self.result) if self.result else ""]
            )
            if looks_like_login_error(haystack):
                self.needs_login = True
                self.error = LOGIN_HINT

        self.ended_at = time.time()
        self._emit(
            {
                "type": "commander",
                "event": "ended",
                "status": self.status.value,
                "exit_code": rc,
                "needs_login": self.needs_login,
            }
        )
        self._close_subscribers()

    async def _drain_stderr(self, stream: asyncio.StreamReader | None) -> None:
        if stream is None:
            return
        async for raw in stream:
            text = raw.decode("utf-8", "replace").rstrip()
            if text:
                self._stderr_parts.append(text)
                self._emit({"type": "stderr", "text": text})

    def _handle_line(self, line: str) -> None:
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            self._emit({"type": "stdout", "text": line})
            return

        # Capture the session id Claude assigns, so it can be resumed later.
        sid = event.get("session_id")
        if sid and sid != self.session_id:
            self.session_id = sid
            if self._on_session:
                self._on_session(sid)

        # Accumulate assistant text into the human-readable report.
        if event.get("type") == "assistant":
            for block in event.get("message", {}).get("content", []):
                if block.get("type") == "text" and block.get("text"):
                    self._report_parts.append(block["text"])
        elif event.get("type") == "result":
            self.result = event
            if isinstance(event.get("result"), str) and not self._report_parts:
                self._report_parts.append(event["result"])

        self._emit(event)

    def _fail(self, message: str) -> None:
        self.status = TaskStatus.failed
        self.error = message
        self.ended_at = time.time()
        self._emit({"type": "commander", "event": "error", "message": message})
        self._close_subscribers()

    def _close_subscribers(self) -> None:
        for q in list(self._subscribers):
            q.put_nowait(_DONE)

    async def stop(self) -> bool:
        if self.status != TaskStatus.running or self._proc is None:
            return False
        self.status = TaskStatus.stopped
        try:
            self._proc.terminate()
        except ProcessLookupError:
            return False
        try:
            await asyncio.wait_for(self._proc.wait(), timeout=5)
        except asyncio.TimeoutError:
            self._proc.kill()
        return True


class TaskManager:
    def __init__(self) -> None:
        self._tasks: dict[str, Task] = {}

    def create(
        self, req: CreateTaskRequest, on_session: Callable[[str], None] | None = None
    ) -> Task:
        task = Task(req, on_session=on_session)
        self._tasks[task.id] = task
        task.start()
        return task

    def get(self, task_id: str) -> Task | None:
        return self._tasks.get(task_id)

    def list(self) -> list[Task]:
        return sorted(self._tasks.values(), key=lambda t: t.created_at, reverse=True)

    async def shutdown(self) -> None:
        for task in self._tasks.values():
            await task.stop()


manager = TaskManager()


class Session:
    """A persistent, REPL-like conversation with Claude in a fixed directory.

    Holds the current model and the underlying Claude session id so successive
    commands keep context. `/model` and `/clear` mutate this state.
    """

    def __init__(self, path: str, model: str | None, permission_mode: str):
        self.id = "s_" + uuid.uuid4().hex[:10]
        self.path = path
        self.model = model
        self.permission_mode = permission_mode
        # The underlying claude session id used for --resume. None => fresh.
        self.claude_session_id: str | None = None
        self.created_at = time.time()
        self.task_ids: list[str] = []

    def _capture_session(self, sid: str) -> None:
        # Keep the latest underlying session id so the next command resumes it.
        self.claude_session_id = sid

    def run(self, prompt: str) -> Task:
        req = CreateTaskRequest(
            prompt=prompt,
            path=self.path,
            model=self.model,
            permission_mode=self.permission_mode,
            session_id=self.claude_session_id,
        )
        task = manager.create(req, on_session=self._capture_session)
        self.task_ids.append(task.id)
        return task

    def clear(self) -> None:
        """Forget the conversation; the next command starts a new Claude session."""
        self.claude_session_id = None


class SessionManager:
    def __init__(self) -> None:
        self._sessions: dict[str, Session] = {}

    def create(self, path: str, model: str | None, permission_mode: str) -> Session:
        session = Session(path, model, permission_mode)
        self._sessions[session.id] = session
        return session

    def get(self, session_id: str) -> Session | None:
        return self._sessions.get(session_id)

    def list(self) -> list[Session]:
        return sorted(self._sessions.values(), key=lambda s: s.created_at, reverse=True)


sessions = SessionManager()


class Conversation:
    """A conversation is an enhanced session that tracks full message history.

    Each message (user prompt) and response are stored with metadata.
    Context is automatically built from all previous messages.
    """

    def __init__(self, path: str, model: str | None, permission_mode: str):
        self.id = "conv_" + uuid.uuid4().hex[:10]
        self.path = path
        self.model = model
        self.permission_mode = permission_mode
        self.claude_session_id: str | None = None
        self.created_at = time.time()
        # Message history: list of {"role": "user"|"assistant", "content": str, "id": str, "created_at": float, "task_id": str}
        self.messages: list[dict[str, Any]] = []
        self.task_ids: list[str] = []

    def add_user_message(self, content: str) -> dict[str, Any]:
        """Record a user message in the conversation."""
        msg = {
            "id": "msg_" + uuid.uuid4().hex[:10],
            "role": "user",
            "content": content,
            "created_at": time.time(),
            "task_id": None,
        }
        self.messages.append(msg)
        return msg

    def add_assistant_message(self, content: str, task_id: str) -> dict[str, Any]:
        """Record an assistant (Claude) response in the conversation."""
        msg = {
            "id": "msg_" + uuid.uuid4().hex[:10],
            "role": "assistant",
            "content": content,
            "created_at": time.time(),
            "task_id": task_id,
        }
        self.messages.append(msg)
        return msg

    def build_context_prompt(self, new_message: str) -> str:
        """Build a context-aware prompt including all previous messages."""
        if not self.messages:
            return new_message

        # Build a narrative of the conversation so far
        context_lines = []
        for msg in self.messages:
            if msg["role"] == "user":
                context_lines.append(f"Previous user request: {msg['content']}")
            else:
                context_lines.append(f"Your previous response: {msg['content'][:500]}...")  # Truncate for readability

        context_lines.append(f"\nNew user request: {new_message}")
        return "\n\n".join(context_lines)

    def send_message(self, content: str, model: str | None = None, permission_mode: str | None = None) -> Task:
        """Send a message and get Claude's response, with full conversation context."""
        # Add user message to history
        user_msg = self.add_user_message(content)

        # Build prompt with context
        prompt = self.build_context_prompt(content)

        # Create task
        req = CreateTaskRequest(
            prompt=prompt,
            path=self.path,
            model=model or self.model,
            permission_mode=permission_mode or self.permission_mode,
            session_id=self.claude_session_id,
        )
        task = manager.create(req, on_session=self._capture_session)
        self.task_ids.append(task.id)

        # Store task_id in the user message
        user_msg["task_id"] = task.id

        return task

    def _capture_session(self, sid: str) -> None:
        """Keep the latest underlying session id so the next command resumes it."""
        self.claude_session_id = sid

    def clear(self) -> None:
        """Clear conversation history and start fresh."""
        self.messages.clear()
        self.claude_session_id = None


class ConversationManager:
    """Manages all active conversations."""

    def __init__(self) -> None:
        self._conversations: dict[str, Conversation] = {}

    def create(self, path: str, model: str | None, permission_mode: str) -> Conversation:
        conv = Conversation(path, model, permission_mode)
        self._conversations[conv.id] = conv
        return conv

    def get(self, conv_id: str) -> Conversation | None:
        return self._conversations.get(conv_id)

    def list(self) -> list[Conversation]:
        return sorted(self._conversations.values(), key=lambda c: c.created_at, reverse=True)


conversations = ConversationManager()

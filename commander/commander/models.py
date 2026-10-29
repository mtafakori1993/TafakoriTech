"""Pydantic request/response models."""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class TaskStatus(str, Enum):
    running = "running"
    completed = "completed"
    failed = "failed"
    stopped = "stopped"


class CreateTaskRequest(BaseModel):
    # The prompt / command to give Claude.
    prompt: str = Field(..., min_length=1, description="The instruction to run.")
    # Working directory Claude runs in. Must exist on the server.
    path: str = Field(..., description="Absolute working directory for the run.")
    # Optional model override (e.g. 'claude-opus-4-8', 'sonnet').
    model: str | None = None
    # Permission mode: default | acceptEdits | bypassPermissions | plan
    permission_mode: str | None = None
    # Resume an existing Claude session to continue the same conversation/context.
    session_id: str | None = None


class TaskSummary(BaseModel):
    id: str
    status: TaskStatus
    path: str
    prompt: str
    model: str | None = None
    session_id: str | None = None
    created_at: float
    ended_at: float | None = None
    exit_code: int | None = None


class TaskDetail(TaskSummary):
    # Concatenated assistant text — the human-readable report.
    report: str = ""
    # The final result event from Claude, if any.
    result: dict[str, Any] | None = None
    error: str | None = None
    # True when the run failed because Claude needs `claude auth login`.
    needs_login: bool = False


class CreateSessionRequest(BaseModel):
    path: str = Field(..., description="Absolute working directory for the session.")
    model: str | None = None
    permission_mode: str | None = None


class SessionDetail(BaseModel):
    id: str
    path: str
    model: str | None = None
    permission_mode: str
    claude_session_id: str | None = None
    created_at: float
    task_ids: list[str] = Field(default_factory=list)


class CommandRequest(BaseModel):
    # A prompt, or a slash command: "/model", "/model <name>", "/clear", "/help".
    command: str = Field(..., min_length=1)


class CommandResponse(BaseModel):
    # "task" => a Claude run was started; "ack" => a slash command was handled.
    kind: str
    message: str | None = None
    task: TaskSummary | None = None
    session: SessionDetail


class Message(BaseModel):
    """A single message in a conversation (user or assistant)."""
    id: str
    role: str  # "user" or "assistant"
    content: str
    created_at: float
    task_id: str | None = None  # The task that processed/generated this message


class ConversationDetail(BaseModel):
    """A conversation with its full message history."""
    id: str
    path: str
    model: str | None = None
    permission_mode: str
    created_at: float
    messages: list[Message] = Field(default_factory=list)
    task_ids: list[str] = Field(default_factory=list)


class ConversationSummary(BaseModel):
    """Summary of a conversation (without full message history)."""
    id: str
    path: str
    model: str | None = None
    permission_mode: str
    created_at: float
    message_count: int = 0


class SendMessageRequest(BaseModel):
    """Send a message in a conversation."""
    message: str = Field(..., min_length=1, description="The message to send")
    model: str | None = Field(None, description="Override the conversation's model")
    permission_mode: str | None = Field(None, description="Override the conversation's permission mode")


class CreateTerminalRequest(BaseModel):
    path: str = Field(..., description="Absolute working directory to launch claude in.")
    model: str | None = None
    # Permission mode for the interactive session (default acceptEdits => no
    # bypass warning dialog, file edits auto-approved).
    permission_mode: str = "acceptEdits"
    # Use --dangerously-skip-permissions instead (adds a one-time accept dialog,
    # handled by auto_accept). Off by default.
    skip_permissions: bool = False
    # Auto-answer the first-run trust/bypass dialogs so the terminal is ready.
    auto_accept: bool = True
    extra_args: list[str] = Field(default_factory=list)
    rows: int = 40
    cols: int = 120


class TerminalInput(BaseModel):
    data: str = Field(..., description="Text/keystrokes to send to the terminal.")
    # Append Enter (carriage return) so the line is submitted.
    submit: bool = True


class CreateShellRequest(BaseModel):
    """A free terminal: an interactive shell (or any command) in a PTY."""

    path: str = Field(..., description="Absolute working directory to launch the shell in.")
    # Command to run. Empty -> the server's interactive login shell ($SHELL).
    command: list[str] = Field(
        default_factory=list,
        description="Argv to launch (e.g. ['bash', '-i']). Empty uses the default shell.",
    )
    rows: int = 40
    cols: int = 120


class ExecRequest(BaseModel):
    """Run a single command on the server and capture its output."""

    command: str = Field(..., min_length=1, description="Shell command line to execute.")
    path: str = Field(..., description="Absolute working directory to run the command in.")
    # Kill the command after this many seconds. 0/None -> no timeout.
    timeout: float | None = Field(default=60, description="Max seconds before the command is killed.")


class ExecResponse(BaseModel):
    command: str
    path: str
    exit_code: int | None = None
    stdout: str = ""
    stderr: str = ""
    timed_out: bool = False
    duration: float = 0.0

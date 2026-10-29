package ir.tafakoritech.adminapplication.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus {
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("stopped") STOPPED,
}

@Serializable
data class TaskSummary(
    val id: String,
    val status: TaskStatus,
    val path: String,
    val prompt: String,
    val model: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("created_at") val createdAt: Double,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
)

@Serializable
data class TaskDetail(
    val id: String,
    val status: TaskStatus,
    val path: String,
    val prompt: String,
    val model: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("created_at") val createdAt: Double,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val report: String = "",
    val error: String? = null,
    @SerialName("needs_login") val needsLogin: Boolean = false,
)

@Serializable
data class SessionDetail(
    val id: String,
    val path: String,
    val model: String? = null,
    @SerialName("permission_mode") val permissionMode: String = "default",
    @SerialName("claude_session_id") val claudeSessionId: String? = null,
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("task_ids") val taskIds: List<String> = emptyList(),
)

@Serializable
data class CommandResponse(
    val kind: String,
    val message: String? = null,
    val task: TaskSummary? = null,
    val session: SessionDetail,
)

@Serializable
data class CreateTaskRequest(
    val prompt: String,
    val path: String,
    val model: String? = null,
    @SerialName("permission_mode") val permissionMode: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class CreateSessionRequest(
    val path: String,
    val model: String? = null,
    @SerialName("permission_mode") val permissionMode: String? = null,
)

@Serializable
data class CreateTerminalRequest(
    val path: String,
    val model: String? = null,
    @SerialName("permission_mode") val permissionMode: String = "acceptEdits",
    @SerialName("skip_permissions") val skipPermissions: Boolean = false,
    @SerialName("auto_accept") val autoAccept: Boolean = true,
    val rows: Int = 40,
    val cols: Int = 120,
)

/** Opens a free terminal: any command (the server's `$SHELL` by default) in a PTY. */
@Serializable
data class CreateShellRequest(
    val path: String,
    val command: List<String>? = null,
    val rows: Int = 40,
    val cols: Int = 120,
)

@Serializable
data class CommandRequest(val command: String)

@Serializable
data class TerminalInput(val data: String, val submit: Boolean = true)

/** Auth status is loosely typed on the server; we read the fields we care about. */
@Serializable
data class AuthStatus(
    val loggedIn: Boolean = false,
    val hint: String? = null,
    val status: String? = null,
)

/**
 * A terminal (or free shell — same PTY shape) as returned by the API. The server
 * types it as a free-form object, so we surface the handful of keys the UI needs
 * and tolerate the rest.
 */
@Serializable
data class TerminalInfo(
    val id: String,
    val path: String = "",
    val running: Boolean = false,
    val status: String? = null,
    val model: String? = null,
    /** For free shells: the command running in the PTY as array (server `$SHELL` if unset). */
    val command: List<String>? = null,
    val screen: String? = null,
    val snapshot: String? = null,
    /** Server-rendered, escape-free screen (via pyte). Preferred for plain display. */
    val text: String? = null,
    /** Server-rendered screen carrying real colors as ANSI/SGR escapes. */
    val ansi: String? = null,
) {
    /** The most readable screen text the server gave us, whichever field it used. */
    val screenText: String get() = text ?: screen ?: snapshot ?: ""

    /** Check if terminal/shell is running. Prefers explicit status field. */
    val isRunning: Boolean get() = status == "on" || running

    /** Display-friendly command string (joins array with spaces). */
    val commandDisplay: String get() = command?.joinToString(" ") ?: "\$SHELL"
}

/** One frame from the terminal SSE stream: a raw [chunk] plus rendered screens. */
@Serializable
data class TerminalStreamEvent(
    val chunk: String? = null,
    val screen: String? = null,
    /** Rendered screen with real colors as ANSI/SGR escapes. */
    val ansi: String? = null,
)

@Serializable
data class ExecRequest(
    val command: String,
    val path: String,
    val timeout: Double? = 60.0,
)

@Serializable
data class ExecResponse(
    val command: String,
    val path: String,
    @SerialName("exit_code") val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("timed_out") val timedOut: Boolean = false,
    val duration: Double = 0.0,
)

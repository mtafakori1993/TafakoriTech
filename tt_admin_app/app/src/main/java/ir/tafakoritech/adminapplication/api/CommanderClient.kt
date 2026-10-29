package ir.tafakoritech.adminapplication.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Raised when the server replies with a non-2xx status. */
class ApiException(val code: Int, message: String) : IOException("HTTP $code: $message")

/**
 * Talks to a single Commander server. The API token is sent as the `X-API-Key`
 * header for normal calls and as a `?token=` query param for SSE streams (which
 * cannot set headers).
 */
class CommanderClient(
    baseUrl: String,
    private val token: String,
) {
    private val base = baseUrl.trim().trimEnd('/') + "/commander"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // SSE streams stay open indefinitely; disable read timeout for them via a
        // dedicated client below.
        .build()

    private val streamHttp = http.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    private val jsonMedia = "application/json".toMediaType()

    // ---- request plumbing -------------------------------------------------

    private fun request(method: String, path: String, body: String? = null): Request {
        val builder = Request.Builder()
            .url("$base$path")
            .header("X-API-Key", token)
            .header("Accept", "application/json")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonMedia))
            else -> error("Unsupported method $method")
        }
        return builder.build()
    }

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response: Response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, text.take(500))
            text
        }
    }

    private suspend inline fun <reified T> get(path: String): T =
        json.decodeFromString(call(request("GET", path)))

    private suspend inline fun <reified T> post(path: String, body: String? = null): T =
        json.decodeFromString(call(request("POST", path, body)))

    private fun streamUrl(path: String): String {
        val url = "$base$path".toHttpUrl().newBuilder()
            .addQueryParameter("token", token)
            .build()
        return url.toString()
    }

    // ---- system / auth ----------------------------------------------------

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        http.newCall(request("GET", "/health")).execute().use { it.isSuccessful }
    }

    suspend fun authStatus(): AuthStatus = get("/v1/auth/status")

    // ---- tasks ------------------------------------------------------------

    suspend fun listTasks(): List<TaskSummary> = get("/v1/tasks")

    suspend fun createTask(req: CreateTaskRequest): TaskSummary =
        post("/v1/tasks", json.encodeToString(req))

    suspend fun getTask(id: String): TaskDetail = get("/v1/tasks/$id")

    suspend fun stopTask(id: String) {
        call(request("POST", "/v1/tasks/$id/stop"))
    }

    fun streamTask(id: String, listener: SseListener): EventSource =
        openStream("/v1/tasks/$id/stream", listener)

    // ---- sessions ---------------------------------------------------------

    suspend fun listSessions(): List<SessionDetail> = get("/v1/sessions")

    suspend fun createSession(req: CreateSessionRequest): SessionDetail =
        post("/v1/sessions", json.encodeToString(req))

    suspend fun getSession(id: String): SessionDetail = get("/v1/sessions/$id")

    suspend fun runCommand(id: String, command: String): CommandResponse =
        post("/v1/sessions/$id/commands", json.encodeToString(CommandRequest(command)))

    // ---- terminals --------------------------------------------------------

    suspend fun listTerminals(): List<TerminalInfo> = get("/v1/terminals")

    suspend fun createTerminal(req: CreateTerminalRequest): TerminalInfo =
        post("/v1/terminals", json.encodeToString(req))

    suspend fun getTerminal(id: String): TerminalInfo = get("/v1/terminals/$id")

    suspend fun terminalOn(id: String) {
        call(request("POST", "/v1/terminals/$id/on"))
    }

    suspend fun terminalOff(id: String) {
        call(request("POST", "/v1/terminals/$id/off"))
    }

    suspend fun deleteTerminal(id: String) {
        call(request("DELETE", "/v1/terminals/$id"))
    }

    suspend fun terminalInput(id: String, data: String, submit: Boolean = true) {
        call(request("POST", "/v1/terminals/$id/input", json.encodeToString(TerminalInput(data, submit))))
    }

    fun streamTerminal(id: String, listener: SseListener): EventSource =
        openStream("/v1/terminals/$id/stream", listener)

    // ---- shells (free terminals) -------------------------------------------

    suspend fun listShells(): List<TerminalInfo> = get("/v1/shells")

    suspend fun createShell(req: CreateShellRequest): TerminalInfo =
        post("/v1/shells", json.encodeToString(req))

    suspend fun getShell(id: String): TerminalInfo = get("/v1/shells/$id")

    suspend fun shellOn(id: String) {
        call(request("POST", "/v1/shells/$id/on"))
    }

    suspend fun shellOff(id: String) {
        call(request("POST", "/v1/shells/$id/off"))
    }

    suspend fun deleteShell(id: String) {
        call(request("DELETE", "/v1/shells/$id"))
    }

    suspend fun shellInput(id: String, data: String, submit: Boolean = true) {
        call(request("POST", "/v1/shells/$id/input", json.encodeToString(TerminalInput(data, submit))))
    }

    fun streamShell(id: String, listener: SseListener): EventSource =
        openStream("/v1/shells/$id/stream", listener)

    // ---- exec (one-shot command) -------------------------------------------

    suspend fun exec(req: ExecRequest): ExecResponse =
        post("/v1/exec", json.encodeToString(req))

    // ---- SSE --------------------------------------------------------------

    private fun openStream(path: String, listener: SseListener): EventSource {
        val request = Request.Builder()
            .url(streamUrl(path))
            .header("Accept", "text/event-stream")
            .build()
        return EventSources.createFactory(streamHttp)
            .newEventSource(request, object : EventSourceListener() {
                override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                    listener.onData(data)
                }

                override fun onClosed(source: EventSource) = listener.onClosed()

                override fun onFailure(source: EventSource, t: Throwable?, response: Response?) {
                    listener.onError(t?.message ?: response?.message ?: "stream failed")
                }
            })
    }

    interface SseListener {
        fun onData(data: String)
        fun onClosed() {}
        fun onError(message: String) {}
    }
}

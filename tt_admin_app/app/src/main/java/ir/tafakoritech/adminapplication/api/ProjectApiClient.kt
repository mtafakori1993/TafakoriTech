package ir.tafakoritech.adminapplication.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProjectApiClient(
    baseUrl: String,
    private val accessToken: String? = null,
) {
    // Kong routes the marshal service at /marshal (strip_path: true), so the
    // gateway forwards /marshal/api/v1/... → marshal:8090/api/v1/...
    // Kong also injects the service X-API-Key automatically via request-transformer.
    private val base = baseUrl.trim().trimEnd('/') + "/marshal"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    private fun request(method: String, path: String, body: String? = null): Request {
        val builder = Request.Builder()
            .url("$base$path")
            .header("Accept", "application/json")
        if (!accessToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $accessToken")
        }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonMedia))
            "PUT" -> builder.put((body ?: "{}").toRequestBody(jsonMedia))
            else -> error("Unsupported method $method")
        }
        return builder.build()
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }

    suspend fun listProjects(): List<ProjectItem> =
        json.decodeFromString(call(request("GET", "/api/v1/projects")))

    suspend fun getProject(id: Long): ProjectItem =
        json.decodeFromString(call(request("GET", "/api/v1/projects/$id")))

    suspend fun createProject(name: String, description: String, color: String): ProjectItem =
        json.decodeFromString(
            call(request("POST", "/api/v1/projects",
                json.encodeToString(CreateProjectRequest(name, description, color))))
        )

    suspend fun updateProject(id: Long, name: String, description: String, color: String): ProjectItem =
        json.decodeFromString(
            call(request("PUT", "/api/v1/projects/$id",
                json.encodeToString(UpdateProjectRequest(name, description, color))))
        )

    suspend fun deleteProject(id: Long) {
        call(request("DELETE", "/api/v1/projects/$id"))
    }

    /** Returns true if the backend is reachable (health endpoint, no auth). */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$base/health").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}

package ir.tafakoritech.adminapplication.api

import ir.tafakoritech.adminapplication.BuildConfig
import ir.tafakoritech.adminapplication.auth.TokenAuthenticator
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

object PersonaClient {
    private val base = BuildConfig.GATEWAY_BASE_URL.trim().trimEnd('/') + "/persona"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .authenticator(TokenAuthenticator())
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val jsonMedia = "application/json".toMediaType()

    private fun req(method: String, path: String, token: String, body: String? = null): Request {
        val builder = Request.Builder()
            .url("$base$path")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
        when (method) {
            "GET"    -> builder.get()
            "DELETE" -> builder.delete()
            "POST"   -> builder.post((body ?: "{}").toRequestBody(jsonMedia))
            "PUT"    -> builder.put((body ?: "{}").toRequestBody(jsonMedia))
        }
        return builder.build()
    }

    private suspend fun call(r: Request): String = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }

    private suspend inline fun <reified T> get(path: String, token: String): T =
        json.decodeFromString(call(req("GET", path, token)))

    private suspend inline fun <reified T> post(path: String, token: String, body: String? = null): T =
        json.decodeFromString(call(req("POST", path, token, body)))

    private suspend inline fun <reified T> put(path: String, token: String, body: String): T =
        json.decodeFromString(call(req("PUT", path, token, body)))

    private suspend fun delete(path: String, token: String) {
        call(req("DELETE", path, token))
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    suspend fun listUsers(token: String, cursor: String? = null, search: String = ""): PersonaListResponse<PersonaUser> =
        get("/api/v1/users?limit=20${if (cursor != null) "&cursor=$cursor" else ""}${if (search.isNotBlank()) "&search=$search" else ""}", token)

    suspend fun createUser(req: CreateUserRequest, token: String): PersonaUser =
        post("/api/v1/users", token, json.encodeToString(req))

    suspend fun updateUser(id: String, req: UpdateUserRequest, token: String): PersonaUser =
        put("/api/v1/users/$id", token, json.encodeToString(req))

    suspend fun deleteUser(id: String, token: String) = delete("/api/v1/users/$id", token)

    suspend fun listUserRoles(id: String, token: String): PersonaItemsResponse<PersonaRole> =
        get("/api/v1/users/$id/roles", token)

    suspend fun assignUserRole(id: String, roleId: String, token: String) {
        call(req("POST", "/api/v1/users/$id/roles", token, json.encodeToString(AssignRoleRequest(roleId))))
    }

    suspend fun removeUserRole(id: String, roleId: String, token: String) =
        delete("/api/v1/users/$id/roles/$roleId", token)

    suspend fun listUserTeams(id: String, token: String): PersonaItemsResponse<PersonaTeam> =
        get("/api/v1/users/$id/teams", token)

    // ── Roles ─────────────────────────────────────────────────────────────────

    suspend fun listRoles(token: String, cursor: String? = null, search: String = ""): PersonaListResponse<PersonaRole> =
        get("/api/v1/roles?limit=50${if (cursor != null) "&cursor=$cursor" else ""}${if (search.isNotBlank()) "&search=$search" else ""}", token)

    suspend fun createRole(req: CreateRoleRequest, token: String): PersonaRole =
        post("/api/v1/roles", token, json.encodeToString(req))

    suspend fun updateRole(id: String, req: UpdateRoleRequest, token: String): PersonaRole =
        put("/api/v1/roles/$id", token, json.encodeToString(req))

    suspend fun deleteRole(id: String, token: String) = delete("/api/v1/roles/$id", token)

    suspend fun listRolePermissions(id: String, token: String): PersonaItemsResponse<PersonaPermission> =
        get("/api/v1/roles/$id/permissions", token)

    suspend fun assignRolePermission(id: String, permissionId: String, token: String) {
        call(req("POST", "/api/v1/roles/$id/permissions", token, json.encodeToString(AssignPermissionRequest(permissionId))))
    }

    suspend fun removeRolePermission(id: String, permissionId: String, token: String) =
        delete("/api/v1/roles/$id/permissions/$permissionId", token)

    // ── Teams ─────────────────────────────────────────────────────────────────

    suspend fun listTeams(token: String, cursor: String? = null, search: String = ""): PersonaListResponse<PersonaTeam> =
        get("/api/v1/teams?limit=50${if (cursor != null) "&cursor=$cursor" else ""}${if (search.isNotBlank()) "&search=$search" else ""}", token)

    suspend fun createTeam(req: CreateTeamRequest, token: String): PersonaTeam =
        post("/api/v1/teams", token, json.encodeToString(req))

    suspend fun updateTeam(id: String, req: UpdateTeamRequest, token: String): PersonaTeam =
        put("/api/v1/teams/$id", token, json.encodeToString(req))

    suspend fun deleteTeam(id: String, token: String) = delete("/api/v1/teams/$id", token)

    suspend fun listTeamUsers(id: String, token: String): PersonaItemsResponse<PersonaUser> =
        get("/api/v1/teams/$id/users", token)

    suspend fun addTeamUser(id: String, userId: String, token: String) {
        call(req("POST", "/api/v1/teams/$id/users", token, json.encodeToString(AddUserRequest(userId))))
    }

    suspend fun removeTeamUser(id: String, userId: String, token: String) =
        delete("/api/v1/teams/$id/users/$userId", token)

    // ── Permissions ───────────────────────────────────────────────────────────

    suspend fun listPermissions(token: String, cursor: String? = null, search: String = ""): PersonaListResponse<PersonaPermission> =
        get("/api/v1/permissions?limit=50${if (cursor != null) "&cursor=$cursor" else ""}${if (search.isNotBlank()) "&search=$search" else ""}", token)

    suspend fun createPermission(req: CreatePermissionRequest, token: String): PersonaPermission =
        post("/api/v1/permissions", token, json.encodeToString(req))

    suspend fun updatePermission(id: String, name: String, description: String, token: String): PersonaPermission =
        put("/api/v1/permissions/$id", token,
            json.encodeToString(mapOf("name" to name, "description" to description)))

    suspend fun deletePermission(id: String, token: String) = delete("/api/v1/permissions/$id", token)
}

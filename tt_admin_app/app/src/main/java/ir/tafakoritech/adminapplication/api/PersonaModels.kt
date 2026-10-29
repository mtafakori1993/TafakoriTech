package ir.tafakoritech.adminapplication.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Pagination ────────────────────────────────────────────────────────────────

@Serializable
data class PersonaListResponse<T>(
    val items: List<T> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    val total: Long = 0,
)

@Serializable
data class PersonaItemsResponse<T>(
    val items: List<T> = emptyList(),
)

// ── Entities ──────────────────────────────────────────────────────────────────

@Serializable
data class PersonaUser(
    val id: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val email: String,
    val phone: String? = null,
    val status: String = "active",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("authz_version") val authzVersion: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    val fullName get() = "$firstName $lastName".trim()
}

@Serializable
data class PersonaRole(
    val id: String,
    val name: String,
    val slug: String,
    val description: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class PersonaTeam(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class PersonaPermission(
    val id: String,
    val name: String,
    val slug: String,
    val resource: String,
    val action: String,
    val description: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

// ── Requests ──────────────────────────────────────────────────────────────────

@Serializable
data class CreateUserRequest(
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val email: String,
    val phone: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val status: String = "active",
)

@Serializable
data class UpdateUserRequest(
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val phone: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val status: String? = null,
)

@Serializable
data class CreateRoleRequest(val name: String, val slug: String, val description: String = "")

@Serializable
data class UpdateRoleRequest(val name: String, val description: String = "")

@Serializable
data class CreateTeamRequest(val name: String, val description: String = "")

@Serializable
data class UpdateTeamRequest(val name: String, val description: String = "")

@Serializable
data class CreatePermissionRequest(
    val name: String,
    val slug: String,
    val resource: String,
    val action: String,
    val description: String = "",
)

@Serializable
data class AssignRoleRequest(@SerialName("role_id") val roleId: String)

@Serializable
data class AssignPermissionRequest(@SerialName("permission_id") val permissionId: String)

@Serializable
data class AddUserRequest(@SerialName("user_id") val userId: String)

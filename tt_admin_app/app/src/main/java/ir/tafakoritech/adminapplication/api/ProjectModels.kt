package ir.tafakoritech.adminapplication.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProjectItem(
    val id: Long,
    val name: String,
    val description: String = "",
    val color: String = "#6750A4",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val description: String,
    val color: String,
)

@Serializable
data class UpdateProjectRequest(
    val name: String,
    val description: String,
    val color: String,
)

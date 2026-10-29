package ir.tafakoritech.adminapplication.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HeraldContentResponse(
    val id: Long = 0,
    val type: String = "",
    val title: String = "",
    val slug: String = "",
    val body: String = "",
    val excerpt: String = "",
    val status: String = "draft",
    @SerialName("seo_title") val seoTitle: String = "",
    @SerialName("seo_description") val seoDescription: String = "",
    @SerialName("author_id") val authorId: Long = 0,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class HeraldPageResponse(
    val id: Long = 0,
    val content: HeraldContentResponse = HeraldContentResponse(),
    val route: String = "",
    val template: String = "",
    @SerialName("is_system") val isSystem: Boolean = false,
)

@Serializable
data class HeraldTagResponse(
    val id: Long = 0,
    val name: String = "",
    val slug: String = "",
)

@Serializable
data class HeraldCategoryResponse(
    val id: Long = 0,
    val name: String = "",
    val slug: String = "",
    val description: String = "",
)

@Serializable
data class HeraldPostResponse(
    val id: Long = 0,
    @SerialName("content_id") val contentId: Long = 0,
    val content: HeraldContentResponse = HeraldContentResponse(),
    @SerialName("category_id") val categoryId: Long? = null,
    val category: HeraldCategoryResponse? = null,
    val tags: List<HeraldTagResponse> = emptyList(),
)

@Serializable
data class HeraldArticleResponse(
    val id: Long = 0,
    @SerialName("content_id") val contentId: Long = 0,
    val content: HeraldContentResponse = HeraldContentResponse(),
    @SerialName("read_time") val readTime: Int = 0,
    val tags: List<HeraldTagResponse> = emptyList(),
)

// Herald uses OffsetPage shape: { data, total, page, limit, total_pages }
@Serializable
data class HeraldPage<T>(
    val data: List<T> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val limit: Int = 20,
    @SerialName("total_pages") val totalPages: Int = 1,
)

// ── Request models ────────────────────────────────────────────────────────────

@Serializable
data class CreatePageRequest(
    val title: String,
    val route: String,
    val slug: String = "",
    val body: String = "",
    val excerpt: String = "",
    val template: String = "default",
    @SerialName("is_system") val isSystem: Boolean = false,
    @SerialName("seo_title") val seoTitle: String = "",
    @SerialName("seo_description") val seoDescription: String = "",
)

@Serializable
data class UpdatePageRequest(
    val title: String,
    val route: String,
    val body: String = "",
    val excerpt: String = "",
    val template: String = "default",
    @SerialName("seo_title") val seoTitle: String = "",
    @SerialName("seo_description") val seoDescription: String = "",
)

@Serializable
data class CreatePostRequest(
    val title: String,
    val slug: String = "",
    val body: String = "",
    val excerpt: String = "",
    @SerialName("category_id") val categoryId: Long? = null,
    val tags: List<String> = emptyList(),
    @SerialName("seo_title") val seoTitle: String = "",
    @SerialName("seo_description") val seoDescription: String = "",
)

@Serializable
data class UpdatePostRequest(
    val title: String,
    val body: String = "",
    val excerpt: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("seo_title") val seoTitle: String = "",
    @SerialName("seo_description") val seoDescription: String = "",
)

@Serializable
data class CreateArticleRequest(
    val title: String,
    val slug: String = "",
    val body: String = "",
    val excerpt: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("seo_title") val seoTitle: String = "",
    @SerialName("seo_description") val seoDescription: String = "",
)

@Serializable
data class UpdateArticleRequest(
    val title: String,
    val body: String = "",
    val excerpt: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("seo_title") val seoTitle: String = "",
    @SerialName("seo_description") val seoDescription: String = "",
)

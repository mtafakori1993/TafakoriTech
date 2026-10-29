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

object HeraldClient {
    private val base = BuildConfig.GATEWAY_BASE_URL.trim().trimEnd('/') + "/herald"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .authenticator(TokenAuthenticator())
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val jsonMedia = "application/json".toMediaType()

    private fun req(method: String, path: String, token: String? = null, body: String? = null): Request {
        val builder = Request.Builder()
            .url("$base$path")
            .header("Accept", "application/json")
            .header("X-API-Key", BuildConfig.HERALD_API_KEY)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
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

    // ── Public (no auth) ──────────────────────────────────────────────────────

    suspend fun getPageBySlug(slug: String): HeraldPageResponse =
        json.decodeFromString(call(req("GET", "/api/v1/pages/$slug")))

    suspend fun listPosts(page: Int = 1, limit: Int = 20): HeraldPage<HeraldPostResponse> =
        json.decodeFromString(call(req("GET", "/api/v1/posts?page=$page&limit=$limit")))

    suspend fun getPostBySlug(slug: String): HeraldPostResponse =
        json.decodeFromString(call(req("GET", "/api/v1/posts/$slug")))

    suspend fun listArticles(page: Int = 1, limit: Int = 20): HeraldPage<HeraldArticleResponse> =
        json.decodeFromString(call(req("GET", "/api/v1/articles?page=$page&limit=$limit")))

    suspend fun getArticleBySlug(slug: String): HeraldArticleResponse =
        json.decodeFromString(call(req("GET", "/api/v1/articles/$slug")))

    suspend fun search(q: String, page: Int = 1): HeraldPage<HeraldContentResponse> =
        json.decodeFromString(call(req("GET", "/api/v1/search?q=${q.trim()}&page=$page")))

    // ── Authenticated ─────────────────────────────────────────────────────────

    suspend fun listPages(page: Int = 1, limit: Int = 20, token: String): HeraldPage<HeraldPageResponse> =
        json.decodeFromString(call(req("GET", "/api/v1/pages?page=$page&limit=$limit", token)))

    suspend fun createPage(body: CreatePageRequest, token: String): HeraldPageResponse =
        json.decodeFromString(call(req("POST", "/api/v1/pages", token, json.encodeToString(body))))

    suspend fun updatePage(id: Long, body: UpdatePageRequest, token: String): HeraldPageResponse =
        json.decodeFromString(call(req("PUT", "/api/v1/pages/$id", token, json.encodeToString(body))))

    suspend fun deletePage(id: Long, token: String) {
        call(req("DELETE", "/api/v1/pages/$id", token))
    }

    suspend fun listAllPosts(page: Int = 1, limit: Int = 20, token: String): HeraldPage<HeraldPostResponse> =
        json.decodeFromString(call(req("GET", "/api/v1/admin/posts?page=$page&limit=$limit", token)))

    suspend fun createPost(body: CreatePostRequest, token: String): HeraldPostResponse =
        json.decodeFromString(call(req("POST", "/api/v1/posts", token, json.encodeToString(body))))

    suspend fun updatePost(id: Long, body: UpdatePostRequest, token: String): HeraldPostResponse =
        json.decodeFromString(call(req("PUT", "/api/v1/posts/$id", token, json.encodeToString(body))))

    suspend fun deletePost(id: Long, token: String) {
        call(req("DELETE", "/api/v1/posts/$id", token))
    }

    suspend fun listAllArticles(page: Int = 1, limit: Int = 20, token: String): HeraldPage<HeraldArticleResponse> =
        json.decodeFromString(call(req("GET", "/api/v1/admin/articles?page=$page&limit=$limit", token)))

    suspend fun createArticle(body: CreateArticleRequest, token: String): HeraldArticleResponse =
        json.decodeFromString(call(req("POST", "/api/v1/articles", token, json.encodeToString(body))))

    suspend fun updateArticle(id: Long, body: UpdateArticleRequest, token: String): HeraldArticleResponse =
        json.decodeFromString(call(req("PUT", "/api/v1/articles/$id", token, json.encodeToString(body))))

    suspend fun deleteArticle(id: Long, token: String) {
        call(req("DELETE", "/api/v1/articles/$id", token))
    }

    suspend fun publishContent(id: Long, token: String) {
        call(req("POST", "/api/v1/publish/$id", token))
    }

    suspend fun unpublishContent(id: Long, token: String) {
        call(req("POST", "/api/v1/unpublish/$id", token))
    }
}

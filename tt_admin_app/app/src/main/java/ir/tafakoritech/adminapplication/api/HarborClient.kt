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

object HarborClient {
    private val base = BuildConfig.GATEWAY_BASE_URL.trim().trimEnd('/') + "/harbor"

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

    // ── Leads ─────────────────────────────────────────────────────────────────

    suspend fun listLeads(token: String, cursor: String? = null, limit: Int = 20): CursorPage<Lead> =
        get("/api/v1/leads?limit=$limit${if (cursor != null) "&cursor=$cursor" else ""}", token)

    suspend fun getLead(id: String, token: String): Lead =
        get("/api/v1/leads/$id", token)

    suspend fun createLead(req: CreateLeadRequest, token: String): Lead =
        post("/api/v1/leads", token, json.encodeToString(req))

    suspend fun updateLead(id: String, req: UpdateLeadRequest, token: String): Lead =
        put("/api/v1/leads/$id", token, json.encodeToString(req))

    suspend fun deleteLead(id: String, token: String) =
        delete("/api/v1/leads/$id", token)

    suspend fun searchLeads(q: String, token: String, cursor: String? = null): CursorPage<Lead> =
        get("/api/v1/leads/search?q=${q.trim()}${if (cursor != null) "&cursor=$cursor" else ""}", token)

    suspend fun recordContact(id: String, note: String, token: String) {
        call(req("POST", "/api/v1/leads/$id/contact", token,
            json.encodeToString(RecordContactRequest(note))))
    }

    suspend fun convertLead(id: String, token: String) {
        call(req("POST", "/api/v1/leads/$id/convert", token))
    }

    suspend fun getStatistics(token: String): LeadStatistics =
        get("/api/v1/leads/statistics", token)

    // ── Admin — Contact Channels ──────────────────────────────────────────────

    suspend fun listContactChannels(token: String): OffsetPage<ContactChannel> =
        get("/admin/v1/contact-channels?limit=100", token)

    suspend fun createContactChannel(name: String, description: String, token: String): ContactChannel =
        post("/admin/v1/contact-channels", token,
            json.encodeToString(CreateRefRequest(name, description)))

    // ── Admin — Lead Sources ──────────────────────────────────────────────────

    suspend fun listLeadSources(token: String): OffsetPage<LeadSource> =
        get("/admin/v1/lead-sources?limit=100", token)

    suspend fun createLeadSource(name: String, description: String, token: String): LeadSource =
        post("/admin/v1/lead-sources", token,
            json.encodeToString(CreateRefRequest(name, description)))

    // ── Admin — Ad Platforms ──────────────────────────────────────────────────

    suspend fun listAdPlatforms(token: String): OffsetPage<AdPlatform> =
        get("/admin/v1/ad-platforms?limit=100", token)

    suspend fun createAdPlatform(name: String, description: String, token: String): AdPlatform =
        post("/admin/v1/ad-platforms", token,
            json.encodeToString(CreateRefRequest(name, description)))

    // ── Admin — Ad Types ──────────────────────────────────────────────────────

    suspend fun listAdTypes(token: String): OffsetPage<AdType> =
        get("/admin/v1/ad-types?limit=100", token)

    suspend fun createAdType(name: String, description: String, platformId: String?, token: String): AdType =
        post("/admin/v1/ad-types", token,
            json.encodeToString(CreateAdTypeRequest(name, description, platformId)))

    // ── Admin — Campaigns ────────────────────────────────────────────────────

    suspend fun listCampaigns(token: String): OffsetPage<Campaign> =
        get("/admin/v1/campaigns?limit=100", token)

    suspend fun createCampaign(name: String, description: String, platformId: String?, sourceId: String?, token: String): Campaign =
        post("/admin/v1/campaigns", token,
            json.encodeToString(CreateCampaignRequest(name, description, platformId, sourceId)))
}

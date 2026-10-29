package ir.tafakoritech.adminapplication.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Pagination envelopes ──────────────────────────────────────────────────────

@Serializable
data class CursorPage<T>(
    val data: List<T> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    val limit: Int = 20,
)

@Serializable
data class OffsetPage<T>(
    val data: List<T> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val limit: Int = 20,
    @SerialName("total_pages") val totalPages: Int = 1,
)

// ── Lead ──────────────────────────────────────────────────────────────────────

@Serializable
data class Lead(
    val id: String,
    @SerialName("full_name") val fullName: String,
    val phone: String = "",
    @SerialName("initial_idea") val initialIdea: String = "",
    val notes: String = "",
    val status: String = "new",
    @SerialName("assigned_to") val assignedTo: String? = null,
    @SerialName("last_contact_at") val lastContactAt: String? = null,
    @SerialName("contact_channel_id") val contactChannelId: String? = null,
    @SerialName("lead_source_id") val leadSourceId: String? = null,
    @SerialName("ad_platform_id") val adPlatformId: String? = null,
    @SerialName("ad_type_id") val adTypeId: String? = null,
    @SerialName("campaign_id") val campaignId: String? = null,
    @SerialName("first_touch_source_id") val firstTouchSourceId: String? = null,
    @SerialName("last_touch_source_id") val lastTouchSourceId: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class CreateLeadRequest(
    @SerialName("full_name") val fullName: String,
    val phone: String,
    @SerialName("initial_idea") val initialIdea: String = "",
    val notes: String = "",
    @SerialName("contact_channel_id") val contactChannelId: String? = null,
    @SerialName("lead_source_id") val leadSourceId: String? = null,
    @SerialName("ad_platform_id") val adPlatformId: String? = null,
    @SerialName("ad_type_id") val adTypeId: String? = null,
    @SerialName("campaign_id") val campaignId: String? = null,
)

@Serializable
data class UpdateLeadRequest(
    @SerialName("full_name") val fullName: String? = null,
    val phone: String? = null,
    @SerialName("initial_idea") val initialIdea: String? = null,
    val notes: String? = null,
    val status: String? = null,
    @SerialName("assigned_to") val assignedTo: String? = null,
    @SerialName("contact_channel_id") val contactChannelId: String? = null,
    @SerialName("lead_source_id") val leadSourceId: String? = null,
    @SerialName("ad_platform_id") val adPlatformId: String? = null,
    @SerialName("ad_type_id") val adTypeId: String? = null,
    @SerialName("campaign_id") val campaignId: String? = null,
)

@Serializable
data class RecordContactRequest(val note: String = "")

// ── Statistics ────────────────────────────────────────────────────────────────

@Serializable
data class LeadStatistics(
    val total: Long = 0,
    val converted: Long = 0,
    @SerialName("conversion_rate") val conversionRate: Double = 0.0,
    @SerialName("by_source") val bySource: List<GroupStat> = emptyList(),
    @SerialName("by_platform") val byPlatform: List<GroupStat> = emptyList(),
    @SerialName("by_campaign") val byCampaign: List<CampaignStat> = emptyList(),
    @SerialName("daily_volume") val dailyVolume: List<DailyStat> = emptyList(),
)

@Serializable
data class GroupStat(
    val id: String? = null,
    val name: String = "",
    val count: Long = 0,
)

@Serializable
data class CampaignStat(
    val id: String? = null,
    val name: String = "",
    val total: Long = 0,
    val converted: Long = 0,
    @SerialName("conversion_rate") val conversionRate: Double = 0.0,
)

@Serializable
data class DailyStat(
    val date: String = "",
    val count: Long = 0,
)

// ── Admin reference entities ──────────────────────────────────────────────────

@Serializable
data class ContactChannel(
    val id: String,
    val name: String,
    val description: String = "",
    val active: Boolean = true,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class LeadSource(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class AdPlatform(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class AdType(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("platform_id") val platformId: String? = null,
    val platform: AdPlatform? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class Campaign(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("platform_id") val platformId: String? = null,
    val platform: AdPlatform? = null,
    @SerialName("source_id") val sourceId: String? = null,
    val source: LeadSource? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val active: Boolean = true,
    @SerialName("created_at") val createdAt: String = "",
)

// ── Admin create requests ─────────────────────────────────────────────────────

@Serializable
data class CreateRefRequest(val name: String, val description: String = "")

@Serializable
data class CreateAdTypeRequest(
    val name: String,
    val description: String = "",
    @SerialName("platform_id") val platformId: String? = null,
)

@Serializable
data class CreateCampaignRequest(
    val name: String,
    val description: String = "",
    @SerialName("platform_id") val platformId: String? = null,
    @SerialName("source_id") val sourceId: String? = null,
    val active: Boolean = true,
)

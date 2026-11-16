package vn.servio.partner.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ScheduleType { NOW, SCHEDULED }

@Serializable
enum class ServiceRequestStatus { DRAFT, OPEN, MATCHED, EXPIRED, CANCELLED, REJECTED_BY_MODERATION }

@Serializable
enum class QuoteStatus { PENDING, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED }

/** What a partner may know about the customer before an order: short name, rating, order count. */
@Serializable
data class CustomerBrief(
    val displayName: String,
    val avatarUrl: String? = null,
    val rating: Double? = null,
    val totalOrders: Int = 0,
)

/**
 * One post in GET /feed (#46) and the NewPost event on /hubs/feed (FeedItemDto). The partner never sees the
 * exact address or phone before ACCEPTED, only an area label and the distance (spec 6.3 #38).
 */
@Serializable
data class FeedItem(
    val requestId: String,
    val title: String,
    val categoryName: String,
    val areaLabel: String,
    val distanceKm: Double,
    val budgetMin: Long? = null,
    val budgetMax: Long? = null,
    val quoteCount: Int,
    val code: String = "",
    val hasQuoted: Boolean = false,
    val imageCount: Int = 0,
    val thumbnailUrl: String? = null,
    val publishedAt: String? = null,
    val descriptionPreview: String = "",
    val categoryId: String = "",
    val scheduleType: ScheduleType = ScheduleType.NOW,
    val scheduledStartAt: String? = null,
    val scheduledEndAt: String? = null,
    val customer: CustomerBrief? = null,
    val expiresAt: String? = null,
)

/** PS-08 choices. Empty [categoryIds] = every approved skill; [maxDistanceKm] null = the profile radius. */
data class FeedFilter(
    val categoryIds: Set<String> = emptySet(),
    val maxDistanceKm: Int? = null,
    val sort: FeedSort = FeedSort.DISTANCE,
) {
    val isDefault: Boolean get() = this == FeedFilter()
}

enum class FeedSort { DISTANCE, NEWEST }

/** #38 for a partner (PS-10): area + distance only, own quote and whether a quote can be sent. */
@Serializable
data class PartnerRequestView(
    val id: String,
    val code: String,
    val status: ServiceRequestStatus,
    val revision: Int,
    val categoryId: String,
    val categoryName: String,
    val title: String,
    val description: String,
    val images: List<String> = emptyList(),
    val areaLabel: String,
    val distanceKm: Double? = null,
    val scheduleType: ScheduleType,
    val scheduledStartAt: String? = null,
    val scheduledEndAt: String? = null,
    val budgetMin: Long? = null,
    val budgetMax: Long? = null,
    val requireExperienceYears: Int? = null,
    val requireCertificate: Boolean = false,
    val requireMinRating: Double? = null,
    val quoteCount: Int = 0,
    val customer: CustomerBrief,
    val ownQuote: PartnerQuote? = null,
    val canQuote: Boolean = false,
    val publishedAt: String? = null,
    val expiresAt: String? = null,
)

/** A quote as its partner sees it (#50 response, #53, own quote in #38). */
@Serializable
data class PartnerQuote(
    val id: String,
    val serviceRequestId: String,
    val requestCode: String = "",
    val requestTitle: String,
    val categoryName: String,
    val requestStatus: ServiceRequestStatus,
    val amount: Long,
    val estimatedDurationMinutes: Int,
    val availableFrom: String,
    val estimatedEndAt: String? = null,
    val note: String? = null,
    val status: QuoteStatus,
    val conversationId: String? = null,
    val createdAt: String,
)

/** #50 POST /service-requests/{id}/quotes. [requestRevision] = revision seen on #38, else 409. */
@Serializable
data class SubmitQuoteRequest(
    val amount: Long,
    val estimatedDurationMinutes: Int,
    val availableFrom: String,
    val note: String? = null,
    val requestRevision: Int,
)

@Serializable
data class WithdrawQuoteRequest(val reason: String? = null)

/** #25 POST /partners/me/online-status */
@Serializable
data class OnlineStatusRequest(val isOnline: Boolean, val latitude: Double? = null, val longitude: Double? = null)

@Serializable
data class OnlineStatus(val isOnline: Boolean)

/** #26 POST /partners/me/heartbeat */
@Serializable
data class HeartbeatRequest(val latitude: Double, val longitude: Double)

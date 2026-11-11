package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/**
 * GET /service-requests/{id} for the owner (#38), same fields as the backend's RequestDetailDto (coordinates and
 * addressId left out). Quotes are not nested: they come from #42. The server sends no broadcastCount.
 */
@Serializable
data class ServiceRequestDetail(
    val id: String,
    val code: String,
    val status: ServiceRequestStatus,
    /** Sent back when accepting a quote (#43) so an edited request is not accepted with old quotes. */
    val revision: Int,
    val categoryName: String,
    val title: String,
    val description: String,
    val images: List<String> = emptyList(),
    val addressSnapshot: String,
    val scheduleType: ScheduleType,
    val scheduledStartAt: String? = null,
    val scheduledEndAt: String? = null,
    val budgetMin: Long? = null,
    val budgetMax: Long? = null,
    val quoteCount: Int = 0,
    val expiresAt: String? = null,
    val cancelReason: String? = null,
    val createdAt: String,
) {
    /** #40 accepts OPEN and DRAFT only. */
    val canCancel: Boolean get() = status == ServiceRequestStatus.OPEN || status == ServiceRequestStatus.DRAFT
}

/** Body of POST /service-requests/{id}/cancel (#40). */
@Serializable
data class CancelRequestBody(val reason: String?) {
    companion object {
        /** StringLength(500) on the server. */
        const val MAX_REASON_LENGTH = 500
    }
}

@Serializable
enum class QuoteStatus { PENDING, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED }

/** `sort` of #42: TIME = earliest availableFrom, PRICE = cheapest, RATING = best rated (no reviews last). */
enum class QuoteSort { TIME, PRICE, RATING }

/** One row of GET /service-requests/{id}/quotes (#42): PENDING and ACCEPTED quotes of the current revision. */
@Serializable
data class CustomerQuote(
    val id: String,
    val serviceRequestId: String,
    val amount: Long,
    val estimatedDurationMinutes: Int,
    val availableFrom: String,
    val estimatedEndAt: String,
    val note: String? = null,
    val status: QuoteStatus,
    /** Base64; sent back when accepting (#43). */
    val rowVersion: String,
    /** Chat with this partner about this request (KH-4). */
    val conversationId: String? = null,
    val partner: QuotePartner,
    val createdAt: String,
)

/** The server sends no skill names here: only the years of experience in the request's category. */
@Serializable
data class QuotePartner(
    val id: String,
    val fullName: String,
    val avatarUrl: String? = null,
    val averageRating: Double? = null,
    val totalReviews: Int = 0,
    val completedOrders: Int = 0,
    val yearsOfExperience: Int = 0,
    val distanceKm: Double? = null,
)

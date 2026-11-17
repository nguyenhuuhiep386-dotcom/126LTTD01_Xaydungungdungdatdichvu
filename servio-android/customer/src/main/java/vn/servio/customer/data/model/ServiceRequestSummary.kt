package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/** Request status codes of spec 5.2.3 (JSON uses the enum names). */
@Serializable
enum class ServiceRequestStatus { DRAFT, OPEN, MATCHED, EXPIRED, CANCELLED, REJECTED_BY_MODERATION }

/**
 * One row of GET /service-requests/me (#37), same fields as the backend's RequestSummaryDto.
 * The server does not send broadcastCount or revision yet (spec 6 lists them), so they are not declared here.
 * [quoteCount] counts pending quotes of the current revision only.
 */
@Serializable
data class ServiceRequestSummary(
    val id: String,
    val code: String,
    val title: String,
    val categoryId: String = "",
    val categoryName: String,
    val status: ServiceRequestStatus,
    val quoteCount: Int,
    val addressSnapshot: String,
    val scheduleType: ScheduleType = ScheduleType.NOW,
    val scheduledStartAt: String? = null,
    val expiresAt: String? = null,
    val thumbnailUrl: String? = null,
    val createdAt: String,
)

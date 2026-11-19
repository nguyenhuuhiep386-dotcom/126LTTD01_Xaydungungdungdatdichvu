package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/** Request status codes of spec 5.2.3 (JSON uses the enum names). */
@Serializable
enum class ServiceRequestStatus { DRAFT, OPEN, MATCHED, EXPIRED, CANCELLED, REJECTED_BY_MODERATION }

/**
 * One row of GET /service-requests/me (#37), same fields as the backend's RequestSummaryDto.
 * [scheduledEndAt]: end of the time window set by the server (NOW: the expiry). [broadcastCount]: partners the post was
 * pushed to when published (0 on posts made before the server counted them). revision is not used here.
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
    val broadcastCount: Int = 0,
    val addressSnapshot: String,
    val scheduleType: ScheduleType = ScheduleType.NOW,
    val scheduledStartAt: String? = null,
    val scheduledEndAt: String? = null,
    val expiresAt: String? = null,
    val thumbnailUrl: String? = null,
    val createdAt: String,
)

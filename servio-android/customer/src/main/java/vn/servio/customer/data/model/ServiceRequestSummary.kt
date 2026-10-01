package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/** Request status codes of spec 5.2.3 (JSON uses the enum names). */
@Serializable
enum class ServiceRequestStatus { DRAFT, OPEN, MATCHED, EXPIRED, CANCELLED, REJECTED_BY_MODERATION }

/** One row of GET /service-requests/me (#37). Field names follow the API contract on Swagger. */
@Serializable
data class ServiceRequestSummary(
    val id: String,
    val code: String,
    val title: String,
    val categoryName: String,
    val status: ServiceRequestStatus,
    val quoteCount: Int,
    val addressSnapshot: String,
    val createdAt: String,
)

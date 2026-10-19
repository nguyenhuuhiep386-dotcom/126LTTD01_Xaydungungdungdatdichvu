package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/** NOW = within the next 2 hours; SCHEDULED = a 2-hour window chosen by the customer (F-REQ-05). */
@Serializable
enum class ScheduleType { NOW, SCHEDULED }

/**
 * Body of POST /service-requests (#36), contract of BE-2 (course scope of spec 6.3.1): one partner, POST_AND_QUOTE only,
 * so bookingType / scheduledEndAt / allowMultiplePartners / maxPartners are not sent. The server sets the window end.
 */
@Serializable
data class CreateServiceRequestRequest(
    val serviceCategoryId: String,
    val title: String,
    val description: String,
    val addressId: String,
    val images: List<RequestImageInput>,
    val scheduleType: ScheduleType,
    /** ISO-8601 with offset; only for SCHEDULED, within [now + 30 minutes, now + 30 days]. */
    val scheduledStartAt: String? = null,
    /** Both null ("để người làm báo giá") or both set with min ≤ max. */
    val budgetMin: Long? = null,
    val budgetMax: Long? = null,
    val requireExperienceYears: Int? = null,
    val requireCertificate: Boolean = false,
    val requireMinRating: Double? = null,
    /** 3..20 km; null lets the server use its default (10 km). */
    val searchRadiusKm: Int? = null,
)

/** One image of #36: a url returned by POST /files with purpose POST_IMAGE. */
@Serializable
data class RequestImageInput(val url: String)

/** Fields of the #36 response (RequestDetailDto) used by the app after posting. */
@Serializable
data class CreatedRequest(
    val id: String,
    val code: String,
    val status: ServiceRequestStatus,
    val expiresAt: String? = null,
)

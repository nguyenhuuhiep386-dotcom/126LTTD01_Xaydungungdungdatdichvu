package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/** #71 body: the rowVersion of the #63 the customer looked at; a changed order gives 409 RESOURCE_VERSION_CONFLICT. */
@Serializable
data class ConfirmCompletionBody(val expectedRowVersion: String)

/** #71 answer. Cash only in the course scope: [paymentRequired] is always false. */
@Serializable
data class ConfirmCompletionResult(
    val orderId: String = "",
    val status: OrderStatus = OrderStatus.UNKNOWN,
    val paymentRequired: Boolean = false,
    val totalAmount: Long = 0,
    val customerPayableTotal: Long = 0,
)

/**
 * One row of GET /orders/{id}/timeline (#64). CS-23 only reads the partner's note of COMPLETED_BY_PARTNER (#70 keeps
 * it there and nowhere else). [changedByType] stays a string (CUSTOMER, PARTNER, SYSTEM, ADMIN).
 */
@Serializable
data class TimelineEntry(
    val status: OrderStatus = OrderStatus.UNKNOWN,
    val assignmentId: String? = null,
    val changedByType: String? = null,
    val note: String? = null,
    val createdAt: String = "",
)

/**
 * #95 body. Optional criteria stay null when not chosen (the app's Json does not encode defaults, so they are left
 * out). No tags and no photos: the server stores none.
 */
@Serializable
data class ReviewBody(
    val assignmentId: String,
    val rating: Int,
    val punctualityRating: Int? = null,
    val qualityRating: Int? = null,
    val attitudeRating: Int? = null,
    val priceRating: Int? = null,
    val comment: String? = null,
)

/** The part of #95's ReviewDto the app reads. */
@Serializable
data class SubmittedReview(
    val id: String,
    val orderId: String = "",
    val assignmentId: String = "",
    val rating: Int = 0,
)

/** One row of GET /reviews/pending (#100): an assignment the customer can still review until [deadlineAt]. */
@Serializable
data class PendingReview(
    val orderId: String,
    val assignmentId: String,
    val orderCode: String = "",
    val revieweeName: String = "",
    val completedAt: String = "",
    val deadlineAt: String = "",
)

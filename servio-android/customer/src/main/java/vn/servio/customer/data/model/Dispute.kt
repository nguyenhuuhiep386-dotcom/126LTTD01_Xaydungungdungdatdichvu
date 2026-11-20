package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/**
 * #146–#148 DisputeDto. [reason]: WORK_QUALITY, NO_SHOW, OVERCHARGE, DAMAGE, BEHAVIOR, PAYMENT, UNPAID_CASH, OTHER.
 * [status]: OPEN, IN_REVIEW (still being handled), RESOLVED, REJECTED (closed, [resolutionNote] = the admin's decision).
 * [slaDueAt]: support answers by then. Times are ISO-8601 with offset; enums are kept as text so a new value does not
 * break the screen.
 */
@Serializable
data class Dispute(
    val id: String,
    val code: String = "",
    val orderId: String = "",
    val reason: String = "",
    val description: String = "",
    val evidenceUrls: List<String> = emptyList(),
    val status: String = "",
    val raisedByType: String? = null,
    val resolutionNote: String? = null,
    val slaDueAt: String? = null,
    val resolvedAt: String? = null,
    val createdAt: String = "",
) {
    val isOpen: Boolean get() = status == STATUS_OPEN || status == STATUS_IN_REVIEW
    val isClosed: Boolean get() = status == STATUS_RESOLVED || status == STATUS_REJECTED

    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_IN_REVIEW = "IN_REVIEW"
        const val STATUS_RESOLVED = "RESOLVED"
        const val STATUS_REJECTED = "REJECTED"
        const val RAISED_BY_CUSTOMER = "CUSTOMER"
    }
}

/** #146 body. [evidenceUrls]: up to 5 files uploaded with purpose DISPUTE_EVIDENCE. */
@Serializable
data class CreateDisputeBody(
    val reason: String,
    val description: String,
    val evidenceUrls: List<String> = emptyList(),
)

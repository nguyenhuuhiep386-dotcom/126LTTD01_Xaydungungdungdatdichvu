package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/**
 * #43 body, course scope: exactly one quote, cash only. [quoteVersions] maps the quote id to its rowVersion from #42;
 * a changed quote gives 409 RESOURCE_VERSION_CONFLICT. No request revision (the server checks it itself), no promotion,
 * no carry-over fee. [paymentMethod] has no default on purpose: the app's Json does not encode defaults.
 */
@Serializable
data class AcceptQuotesBody(
    val quoteIds: List<String>,
    val quoteVersions: Map<String, String>,
    val paymentMethod: String,
) {
    companion object {
        const val CASH = "CASH"

        fun one(quoteId: String, rowVersion: String) =
            AcceptQuotesBody(listOf(quoteId), mapOf(quoteId to rowVersion), CASH)
    }
}

/** The part of #43's answer (the order detail of #63) CS-18 needs to open the order. */
@Serializable
data class CreatedOrder(
    val id: String,
    val code: String,
)

/**
 * Order and assignment status codes of spec 2.5.1 (the 12 codes, JSON uses the names). [UNKNOWN] is what an
 * unexpected value becomes (the app's Json coerces it to the property default), so a new server status never crashes.
 */
@Serializable
enum class OrderStatus {
    PENDING, ACCEPTED, ON_THE_WAY, ARRIVED, IN_PROGRESS, COMPLETED_BY_PARTNER, AWAITING_PAYMENT, COMPLETED, CANCELLED,
    DISPUTED, REFUNDED, SEARCHING, UNKNOWN,
}

/** Who cancelled an order (#63 cancelledBy). An unexpected value becomes null. */
@Serializable
enum class OrderActor { CUSTOMER, PARTNER, SYSTEM, ADMIN }

/** `status` filter of #62, one per CS-19 tab. ACTIVE includes DISPUTED and the steps waiting for the customer. */
enum class OrderListFilter { ACTIVE, COMPLETED, CANCELLED }

/**
 * The other side of an order (OrderPartyDto). For the customer it is the partner: [partnerProfileId] opens #27,
 * [phoneNumber] is only sent from ACCEPTED until the work is done (F-ORD-06), [rating] comes without a review count.
 */
@Serializable
data class OrderParty(
    val partnerProfileId: String? = null,
    val displayName: String = "",
    val avatarUrl: String? = null,
    val rating: Double? = null,
    val phoneNumber: String? = null,
)

/** One row of GET /orders/me (#62), as the backend's OrderSummaryDto. */
@Serializable
data class OrderSummary(
    val id: String,
    val code: String,
    val status: OrderStatus = OrderStatus.UNKNOWN,
    val serviceRequestId: String = "",
    val title: String = "",
    val categoryName: String = "",
    val scheduledStartAt: String? = null,
    val totalAmount: Long = 0,
    val counterpart: OrderParty = OrderParty(),
    val assignmentId: String = "",
    val assignmentStatus: OrderStatus = OrderStatus.UNKNOWN,
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** One partner's part of an order (#63 assignments[]). Times are null until the step happened. */
@Serializable
data class OrderAssignment(
    val id: String,
    val status: OrderStatus = OrderStatus.UNKNOWN,
    val isPrimary: Boolean = false,
    val partner: OrderParty = OrderParty(),
    val amount: Long = 0,
    val extraChargeTotal: Long = 0,
    val acceptanceDeadlineAt: String? = null,
    val reservedStartAt: String? = null,
    val reservedEndAt: String? = null,
    val acceptedAt: String? = null,
    val onTheWayAt: String? = null,
    val arrivedAt: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val cancelledAt: String? = null,
    val cancelReason: String? = null,
)

/** Money of the order (#63 pricing). */
@Serializable
data class OrderPricing(
    val subTotal: Long = 0,
    val extraChargeTotal: Long = 0,
    val totalAmount: Long = 0,
)

/**
 * GET /orders/{id} (#63) for the customer: the whole order. [rowVersion] is what #72 expects (through #151).
 * [paymentMethod] stays a string ("CASH"); the screen names the values it knows.
 */
@Serializable
data class OrderDetail(
    val id: String,
    val code: String,
    val status: OrderStatus = OrderStatus.UNKNOWN,
    val paymentMethod: String? = null,
    val serviceRequestId: String = "",
    val title: String = "",
    val description: String = "",
    val categoryName: String = "",
    val address: String = "",
    /** Where the job is (#63, the request's address); CS-21 puts the address marker here. Null: no marker. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val scheduledStartAt: String? = null,
    val pricing: OrderPricing = OrderPricing(),
    val assignments: List<OrderAssignment> = emptyList(),
    val conversationId: String? = null,
    val acceptedAt: String? = null,
    val startedAt: String? = null,
    val completedByPartnerAt: String? = null,
    val autoConfirmAt: String? = null,
    val completedAt: String? = null,
    val cancelledAt: String? = null,
    val cancelledBy: OrderActor? = null,
    val cancelReason: String? = null,
    /** Every extra charge of the visible assignments, oldest first (#63). */
    val extraCharges: List<ExtraCharge> = emptyList(),
    val rowVersion: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    /** The part used for contact and the partner row (spec 2.5.1: exactly one IsPrimary); else the first one. */
    val primary: OrderAssignment? get() = assignments.firstOrNull { it.isPrimary } ?: assignments.firstOrNull()
}

/** Status of an extra charge (ExtraChargeStatus). An unexpected value becomes [UNKNOWN]. */
@Serializable
enum class ExtraChargeStatus { PENDING, APPROVED, REJECTED, UNKNOWN }

/**
 * One extra charge the partner asked for (#63 extraCharges[], answer of #74). The server has only [description] (no
 * separate title) and at most one evidence photo, without a caption.
 */
@Serializable
data class ExtraCharge(
    val id: String,
    val orderId: String = "",
    val assignmentId: String = "",
    val description: String = "",
    val amount: Long = 0,
    val evidenceImageUrl: String? = null,
    val status: ExtraChargeStatus = ExtraChargeStatus.UNKNOWN,
    val responseNote: String? = null,
    val respondedAt: String? = null,
    val createdAt: String = "",
)

/** #74 body. [approved] has no default on purpose (the app's Json does not encode defaults); no note is sent. */
@Serializable
data class RespondExtraChargeBody(val approved: Boolean)

/** Fee of one assignment in #151, by its current status. */
@Serializable
data class AssignmentFee(
    val assignmentId: String,
    val status: OrderStatus = OrderStatus.UNKNOWN,
    val fee: Long = 0,
)

/** GET /orders/{id}/cancel-preview (#151): valid until [expiresAt]; send [rowVersion] to #72. */
@Serializable
data class CancelPreview(
    val fee: Long,
    val assignmentFees: List<AssignmentFee> = emptyList(),
    val rowVersion: String,
    val expiresAt: String,
)

/** #72 body. [reason] is the customer's choice, sent as shown (3–500 characters on the server). */
@Serializable
data class CancelOrderBody(
    val reason: String,
    val expectedRowVersion: String,
)

/** #72 answer. [cancellationFee] is what the customer now owes for this order (0 when free). */
@Serializable
data class CancelResult(
    val orderId: String,
    val status: OrderStatus = OrderStatus.UNKNOWN,
    val cancellationFee: Long = 0,
)

/** GET /wallet/me (#87) for the customer; only [maxDebt] is used: unpaid cancellation fees above it block posting. */
@Serializable
data class CustomerWallet(
    val debtBalance: Long = 0,
    val pendingPayment: Long = 0,
    val maxDebt: Long? = null,
)

/** Last fix of the partner (#76 location). [capturedAt] is the time of the GPS fix, never in the future. */
@Serializable
data class TrackingLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Int? = null,
    val capturedAt: String,
)

/**
 * One assignment of GET /orders/{id}/tracking (#76), also the payload of PartnerLocationUpdated. [location] is only
 * sent while the partner is ON_THE_WAY; [distanceKm] and [etaMinutes] come with it. [isStale] is the server's view at
 * the time it answered; the screens decide again from [TrackingLocation.capturedAt] (see TrackingUi).
 */
@Serializable
data class Tracking(
    val orderId: String,
    val assignmentId: String,
    val partnerProfileId: String = "",
    val assignmentStatus: OrderStatus = OrderStatus.UNKNOWN,
    val location: TrackingLocation? = null,
    val distanceKm: Double? = null,
    val etaMinutes: Int? = null,
    val isStale: Boolean = false,
)

/** Status of a "Thợ báo đã đến" request. An unexpected value becomes [UNKNOWN]. */
@Serializable
enum class ArrivalConfirmationStatus { PENDING, CONFIRMED, DENIED, EXPIRED, CANCELLED, UNKNOWN }

/** Payload of ArrivalConfirmationRequested and answer of #179: the customer answers until [expiresAt]. */
@Serializable
data class ArrivalConfirmation(
    val orderId: String,
    val assignmentId: String,
    val requestId: String,
    val status: ArrivalConfirmationStatus = ArrivalConfirmationStatus.UNKNOWN,
    val expiresAt: String,
)

/** #179 body. [confirmed] has no default on purpose: the app's Json does not encode defaults. */
@Serializable
data class RespondArrivalBody(val confirmed: Boolean)

/** The part of OrderStatusChanged the arrival requests need (OrderStatusChangedDto). */
@Serializable
data class OrderStatusChange(
    val orderId: String = "",
    val assignmentId: String = "",
    val status: OrderStatus = OrderStatus.UNKNOWN,
    val assignmentStatus: OrderStatus = OrderStatus.UNKNOWN,
)

package vn.servio.partner.data.model

import kotlinx.serialization.Serializable

// Orders for the partner (BE-4 → BE-6, spec 6 #62–#77, #152, #178, #180). Rarely shown enums stay String.

@Serializable
enum class OrderStatus {
    PENDING, ACCEPTED, ON_THE_WAY, ARRIVED, IN_PROGRESS, COMPLETED_BY_PARTNER, AWAITING_PAYMENT, COMPLETED, CANCELLED, DISPUTED,
}

@Serializable
enum class ExtraChargeStatus { PENDING, APPROVED, REJECTED }

/** The other side of the order. [phoneNumber] only from ACCEPTED until the work is done (F-ORD-06). */
@Serializable
data class OrderParty(
    val partnerProfileId: String? = null,
    val displayName: String,
    val avatarUrl: String? = null,
    val rating: Double? = null,
    val phoneNumber: String? = null,
)

/** Row of #62 (PS-13 tabs). */
@Serializable
data class OrderSummary(
    val id: String,
    val code: String,
    val status: OrderStatus,
    val serviceRequestId: String,
    val title: String,
    val categoryName: String,
    val scheduledStartAt: String? = null,
    val totalAmount: Long,
    val earning: Long? = null,
    val counterpart: OrderParty,
    val assignmentId: String,
    val assignmentStatus: OrderStatus,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class OrderPricing(
    val subTotal: Long,
    val extraChargeTotal: Long = 0,
    val totalAmount: Long,
    val commissionRate: Double? = null,
    val commissionAmount: Long? = null,
    val partnerEarning: Long? = null,
)

@Serializable
data class Assignment(
    val id: String,
    val status: OrderStatus,
    val isPrimary: Boolean = true,
    val partner: OrderParty,
    val amount: Long,
    val extraChargeTotal: Long = 0,
    val acceptanceDeadlineAt: String,
    val reservedStartAt: String? = null,
    val reservedEndAt: String? = null,
    val acceptedAt: String? = null,
    val onTheWayAt: String? = null,
    val arrivedAt: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val cancelledAt: String? = null,
    val cancelReason: String? = null,
    val beforeImages: List<String> = emptyList(),
    val afterImages: List<String> = emptyList(),
)

@Serializable
data class ExtraCharge(
    val id: String,
    val orderId: String,
    val assignmentId: String,
    val description: String,
    val amount: Long,
    val evidenceImageUrl: String? = null,
    val status: ExtraChargeStatus,
    val responseNote: String? = null,
    val respondedAt: String? = null,
    val createdAt: String,
)

/** #63. The partner sees only their own assignment and, until they accept, only the area (no exact position). */
@Serializable
data class OrderDetail(
    val id: String,
    val code: String,
    val status: OrderStatus,
    val paymentMethod: String = "CASH",
    val paymentStatus: String? = null,
    val serviceRequestId: String,
    val title: String,
    val description: String = "",
    val serviceCategoryId: String = "",
    val categoryName: String,
    val address: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val scheduledStartAt: String? = null,
    val pricing: OrderPricing,
    val customer: OrderParty,
    val assignments: List<Assignment> = emptyList(),
    val extraCharges: List<ExtraCharge> = emptyList(),
    val conversationId: String? = null,
    val acceptedAt: String? = null,
    val startedAt: String? = null,
    val completedByPartnerAt: String? = null,
    val autoConfirmAt: String? = null,
    val completedAt: String? = null,
    val cancelledAt: String? = null,
    val cancelledBy: String? = null,
    val cancelReason: String? = null,
    val rowVersion: String = "",
    val createdAt: String,
    val updatedAt: String,
) {
    /** This partner's part of the order (course scope: one partner per order). */
    val assignment: Assignment? get() = assignments.firstOrNull()
}

/** #64 row. */
@Serializable
data class TimelineEntry(
    val status: OrderStatus,
    val fromStatus: OrderStatus? = null,
    val assignmentId: String? = null,
    val changedByType: String,
    val note: String? = null,
    val createdAt: String,
)

/** Result of #65/#67/#69/#70/#152 and payload of OrderStatusChanged on /hubs/orders. */
@Serializable
data class OrderStatusChanged(
    val orderId: String,
    val assignmentId: String,
    val status: OrderStatus,
    val assignmentStatus: OrderStatus,
    val changedAt: String,
)

/** Hub QuoteAccepted (to the partner): the customer picked a quote; the order waits for #65. */
@Serializable
data class QuoteAcceptedEvent(val quoteId: String, val orderId: String? = null)

@Serializable
data class RejectAssignmentRequest(val reason: String)

@Serializable
data class PartnerCancelRequest(val reason: String)

/** #67 */
@Serializable
data class StartMovingRequest(val latitude: Double? = null, val longitude: Double? = null, val accuracy: Int? = null)

/** #68. [capturedAt] = time of the GPS fix; [isMock] from Location.isMock(). */
@Serializable
data class ArriveRequest(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Int,
    val capturedAt: String,
    val isMock: Boolean,
)

@Serializable
data class ArriveResult(
    val orderId: String,
    val assignmentId: String,
    val status: OrderStatus,
    val assignmentStatus: OrderStatus,
    val distanceMeters: Int,
    val changedAt: String,
)

/** #178 result and hub ArrivalConfirmationResolved. status: PENDING, CONFIRMED, DENIED or EXPIRED. */
@Serializable
data class ArrivalConfirmation(
    val orderId: String,
    val assignmentId: String,
    val requestId: String,
    val status: String,
    val expiresAt: String,
)

/** #69 start-work: 1–10 BEFORE photo URLs (POST /files ORDER_IMAGE). */
@Serializable
data class StartWorkRequest(val beforeImages: List<String>)

/** #70 complete: 1–10 AFTER photo URLs and an optional note for the customer. */
@Serializable
data class CompleteWorkRequest(val afterImages: List<String>, val note: String? = null)

/** #73 extra charge: 1.000–100.000.000 đ, the customer approves it in the app (#74). */
@Serializable
data class CreateExtraChargeRequest(
    val assignmentId: String,
    val description: String,
    val amount: Long,
    val evidenceImageUrl: String? = null,
)

/** #180 the customer is not there (after arriving by GPS, waiting and messaging). */
@Serializable
data class CustomerNoShowRequest(val note: String? = null)

/** Result of #180 (only the new status is used). */
@Serializable
data class CancelResult(val orderId: String, val status: OrderStatus, val cancellationFee: Long = 0)

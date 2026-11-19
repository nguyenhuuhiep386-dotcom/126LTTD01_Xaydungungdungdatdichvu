package vn.servio.customer.ui.completion

import vn.servio.customer.data.model.ExtraCharge
import vn.servio.customer.data.model.ExtraChargeStatus
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.TimelineEntry
import vn.servio.customer.ui.order.OrderTexts

/** What CS-23 shows, decided from #63 and #64 only (pure, unit tested). */
object ConfirmCompletionUi {

    /** CS-23 only makes sense while the partner's "done" waits for the customer. */
    fun isWaiting(order: OrderDetail): Boolean = order.status == OrderStatus.COMPLETED_BY_PARTNER

    /** Second sentence of the card: with the time of autoConfirmAt, or without a time when it is missing or unreadable. */
    sealed interface Deadline {
        data class At(val autoConfirmAt: String) : Deadline

        data object Unknown : Deadline
    }

    fun deadline(order: OrderDetail): Deadline =
        order.autoConfirmAt?.takeIf { OrderTexts.instant(it) != null }?.let { Deadline.At(it) } ?: Deadline.Unknown

    /** One photo of "Ảnh trước và sau": [before] → "Trước", else "Sau". */
    data class Photo(val url: String, val before: Boolean)

    /** Every before photo, then every after photo, of all the visible assignments (primary first). */
    fun photos(order: OrderDetail): List<Photo> {
        val parts = listOfNotNull(order.primary) + order.assignments.filter { it != order.primary }
        val before = parts.flatMap { it.beforeImages }.filter { it.isNotBlank() }.map { Photo(it, before = true) }
        val after = parts.flatMap { it.afterImages }.filter { it.isNotBlank() }.map { Photo(it, before = false) }
        return before + after
    }

    /** The partner's note of #70: the latest COMPLETED_BY_PARTNER row of #64, trimmed; null when there is none. */
    fun partnerNote(timeline: List<TimelineEntry>?): String? =
        timeline.orEmpty()
            .filter { it.status == OrderStatus.COMPLETED_BY_PARTNER }
            .maxByOrNull { OrderTexts.instant(it.createdAt) ?: java.time.Instant.MIN }
            ?.note?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * "Thanh toán": "Giá báo" = subTotal, one line per APPROVED charge (rejected and pending ones are not paid), and
     * "Tổng tiền mặt trả đối tác" = totalAmount as the server computed it.
     */
    data class Payment(val quoted: Long, val approved: List<ExtraCharge>, val total: Long)

    fun payment(order: OrderDetail): Payment = Payment(
        quoted = order.pricing.subTotal,
        approved = order.extraCharges
            .filter { it.status == ExtraChargeStatus.APPROVED }
            .sortedWith(compareBy(nullsLast()) { OrderTexts.instant(it.createdAt) }),
        total = order.pricing.totalAmount,
    )

    /** What a failed #71 means. */
    enum class Failure {
        /** RESOURCE_VERSION_CONFLICT: reload #63 and ask to check the amount again. */
        CHANGED,

        /** INVALID_STATUS_TRANSITION: no longer waiting (confirmed automatically, …): leave CS-23. */
        NOT_WAITING,

        /** ORDER_DISPUTED. */
        DISPUTED,

        /** Network and the rest: stay, with the server's message. */
        OTHER,
    }

    fun failure(code: String): Failure = when (code) {
        "RESOURCE_VERSION_CONFLICT" -> Failure.CHANGED
        "INVALID_STATUS_TRANSITION" -> Failure.NOT_WAITING
        "ORDER_DISPUTED" -> Failure.DISPUTED
        else -> Failure.OTHER
    }
}

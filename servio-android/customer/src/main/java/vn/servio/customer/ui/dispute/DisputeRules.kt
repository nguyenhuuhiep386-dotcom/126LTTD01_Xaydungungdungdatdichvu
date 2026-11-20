package vn.servio.customer.ui.dispute

import vn.servio.customer.data.model.Dispute
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.ui.order.OrderTexts
import vn.servio.customer.ui.request.ContactInfoGuard
import java.time.Instant
import java.time.ZoneId

/** Pure rules of CS-33 and of the dispute lines of CS-20 / CS-23 (#146, #147). */
object DisputeRules {

    /** The 4 chips of Figma 18:3333, in their order, and the #146 reason each one sends. */
    enum class Reason(val apiValue: String) {
        WORK_QUALITY("WORK_QUALITY"),
        OVERCHARGE("OVERCHARGE"),
        BEHAVIOR("BEHAVIOR"),
        OTHER("OTHER"),
    }

    const val DESCRIPTION_MIN = 10

    /** Figma counts to 1000; the server would take 2000. */
    const val DESCRIPTION_MAX = 1000

    const val MAX_IMAGES = 5

    enum class DescriptionError { EMPTY, TOO_SHORT, TOO_LONG, CONTACT_INFO }

    /** Checked on the trimmed text, as the server does. Null when it can be sent. */
    fun checkDescription(text: String): DescriptionError? {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> DescriptionError.EMPTY
            trimmed.length < DESCRIPTION_MIN -> DescriptionError.TOO_SHORT
            trimmed.length > DESCRIPTION_MAX -> DescriptionError.TOO_LONG
            ContactInfoGuard.containsContactInfo(trimmed) -> DescriptionError.CONTACT_INFO
            else -> null
        }
    }

    /** #146 accepts these order statuses (DISPUTED answers 409, the others 422). */
    val OPENABLE: Set<OrderStatus> = setOf(
        OrderStatus.ACCEPTED, OrderStatus.ON_THE_WAY, OrderStatus.ARRIVED, OrderStatus.IN_PROGRESS,
        OrderStatus.COMPLETED_BY_PARTNER, OrderStatus.COMPLETED,
    )

    /**
     * "Khiếu nại" in the CS-20 menu. Before completion (no completedAt) there is no time limit; after it, until
     * completedAt + [windowHours] (disputeWindowHours of /app-config). Without the config ([windowHours] null) or with
     * an unreadable completedAt a completed order shows nothing: the window cannot be checked. The server decides in
     * the end (409/422 come back as its message).
     */
    fun canOpen(order: OrderDetail, windowHours: Int?, now: Instant = Instant.now()): Boolean {
        if (order.status !in OPENABLE) return false
        val completedAt = order.completedAt?.takeIf { it.isNotBlank() } ?: return true
        val hours = windowHours ?: return false
        val completed = OrderTexts.instant(completedAt) ?: return false
        return !now.isAfter(completed.plusSeconds(hours * 3600L))
    }

    /** CS-20 DISPUTED: the newest case still being handled (#147 is newest first). */
    fun openCase(disputes: List<Dispute>): Dispute? = disputes.firstOrNull { it.isOpen }

    /**
     * CS-20 not DISPUTED: the newest closed case the customer opened, for "Kết quả khiếu nại". Cases the partner opened
     * (UNPAID_CASH report) are skipped: their outcome already shows as the order status.
     */
    fun closedCase(disputes: List<Dispute>): Dispute? =
        disputes.firstOrNull { it.isClosed && it.raisedByType == Dispute.RAISED_BY_CUSTOMER }

    /** Second line of the DISPUTED banner: "Mã DS… · phản hồi trước [due]" or, without a readable slaDueAt, "Mã DS…". */
    data class BannerLine(val code: String, val due: String?)

    fun bannerLine(dispute: Dispute, weekdays: List<String>, zone: ZoneId = ZoneId.systemDefault()): BannerLine? {
        val code = dispute.code.trim().takeIf { it.isNotEmpty() } ?: return null
        return BannerLine(code, OrderTexts.moment(dispute.slaDueAt, weekdays, zone))
    }

    /** "Kết quả khiếu nại DS…: [note]"; [note] null = "đã xử lý" (the admin left it empty). */
    data class ResultLine(val code: String, val note: String?)

    fun resultLine(dispute: Dispute): ResultLine? {
        val code = dispute.code.trim().takeIf { it.isNotEmpty() } ?: return null
        return ResultLine(code, dispute.resolutionNote?.trim()?.takeIf { it.isNotEmpty() })
    }
}

package vn.servio.customer.ui.requests

import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary

/** The chip on a CS-14 card: the quote count while the request is open, otherwise its status (RequestStatusChip). */
sealed interface RequestChip {
    /** Figma: neutral chip, dark text, clock. */
    data object WaitingForQuotes : RequestChip

    /** Figma: pale yellow chip, accent text, quote icon. */
    data class Quotes(val count: Int) : RequestChip
    data class Status(val status: ServiceRequestStatus) : RequestChip

    companion object {
        fun of(status: ServiceRequestStatus, quoteCount: Int): RequestChip = when {
            status != ServiceRequestStatus.OPEN -> Status(status)
            quoteCount > 0 -> Quotes(quoteCount)
            else -> WaitingForQuotes
        }
    }
}

/** Last line of a CS-14 card (Figma 18:1417). */
sealed interface RequestFooter {
    /** An open request without quotes yet: "Đã gửi tới N người làm phù hợp" (#37 broadcastCount). */
    data class SentTo(val partners: Int) : RequestFooter

    /** An open request otherwise (quotes came, or broadcastCount 0 as on posts made before it existed): the countdown. */
    data object Expiry : RequestFooter

    /** Matched, expired, cancelled … : no last line. */
    data object None : RequestFooter
}

/** One CS-14 card: the #37 row and the name of its level-1 category group once the category tree has answered. */
data class RequestCard(val request: ServiceRequestSummary, val categoryGroup: String?) {

    /** "Điện lạnh" in "SR-1024 · Điện lạnh"; the level-2 name until (or unless) the tree is known. */
    val categoryLabel: String get() = categoryGroup ?: request.categoryName

    val chip: RequestChip get() = RequestChip.of(request.status, request.quoteCount)

    val footer: RequestFooter get() = when {
        request.status != ServiceRequestStatus.OPEN -> RequestFooter.None
        request.quoteCount == 0 && request.broadcastCount > 0 -> RequestFooter.SentTo(request.broadcastCount)
        else -> RequestFooter.Expiry
    }

    /** End of the time window as the server set it (#37); null: the card shows the start only. */
    val scheduledEndAt: String? get() = request.scheduledEndAt
}

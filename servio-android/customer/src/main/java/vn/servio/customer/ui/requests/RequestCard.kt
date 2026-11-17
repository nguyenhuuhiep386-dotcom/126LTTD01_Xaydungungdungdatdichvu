package vn.servio.customer.ui.requests

import vn.servio.customer.data.model.ScheduleType
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.ui.request.ScheduleSlots
import java.time.OffsetDateTime

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

/** One CS-14 card: the #37 row and the name of its level-1 category group once the category tree has answered. */
data class RequestCard(val request: ServiceRequestSummary, val categoryGroup: String?) {

    /** "Điện lạnh" in "SR-1024 · Điện lạnh"; the level-2 name until (or unless) the tree is known. */
    val categoryLabel: String get() = categoryGroup ?: request.categoryName

    val chip: RequestChip get() = RequestChip.of(request.status, request.quoteCount)

    /** The countdown is only for a request still taking quotes. */
    val showsExpiry: Boolean get() = request.status == ServiceRequestStatus.OPEN

    /**
     * #37 has no scheduledEndAt (CS-15 has it from #38). The server sets it to start + the 2-hour window, and for a
     * NOW request the expiry is that same end, so the card can show the same line as CS-15.
     */
    val scheduledEndAt: String? get() = scheduledEnd(request)

    companion object {
        fun scheduledEnd(request: ServiceRequestSummary): String? = when (request.scheduleType) {
            ScheduleType.NOW -> request.expiresAt
            ScheduleType.SCHEDULED -> request.scheduledStartAt
                ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                ?.plusHours(ScheduleSlots.WINDOW_HOURS)
                ?.toString()
        }
    }
}

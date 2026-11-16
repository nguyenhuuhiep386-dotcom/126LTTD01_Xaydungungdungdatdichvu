package vn.servio.customer.ui.confirmquote

/** What CS-18 does with an error of #43. Plain data, so it can be unit tested. */
sealed interface ConfirmFailure {

    /** The quote changed or left PENDING: reload, then show [dialog] only if it can still be booked (new price). */
    data class Changed(val dialog: Dialog) : ConfirmFailure

    /** Booking this quote is not possible any more: [dialog], then back to CS-15. */
    data class Leave(val dialog: Dialog) : ConfirmFailure

    /** Network or unexpected: snackbar with "Thử lại" on CS-18. */
    data class Retry(val message: String) : ConfirmFailure

    /**
     * [kind] picks the title and button; [message] is the server's text, except where the screen has its own wording
     * ([DialogKind.CHANGED], [DialogKind.ALREADY_MATCHED], [DialogKind.NOT_FOUND] carry an empty message).
     */
    data class Dialog(val kind: DialogKind, val message: String)

    enum class DialogKind {
        /** "Báo giá vừa thay đổi": stays on CS-18 with the new price. */
        CHANGED,

        /** The request already has a partner: back to CS-15. */
        ALREADY_MATCHED,

        /** This partner cannot take it (not eligible, full, schedule clash): "Chọn báo giá khác" back to CS-15. */
        PARTNER_UNAVAILABLE,

        /** The request expired or is no longer open: back to CS-15 (server message says which). */
        REQUEST_CLOSED,

        /** Request or quote not found: back to CS-15. */
        NOT_FOUND,
    }

    companion object {
        const val RESOURCE_VERSION_CONFLICT = "RESOURCE_VERSION_CONFLICT"
        const val QUOTE_ALREADY_ACCEPTED = "QUOTE_ALREADY_ACCEPTED"
        const val PARTNER_NOT_ELIGIBLE = "PARTNER_NOT_ELIGIBLE"
        const val PARTNER_CAPACITY_EXCEEDED = "PARTNER_CAPACITY_EXCEEDED"
        const val SCHEDULE_CONFLICT = "SCHEDULE_CONFLICT"
        const val REQUEST_EXPIRED = "REQUEST_EXPIRED"
        const val REQUEST_NOT_OPEN = "REQUEST_NOT_OPEN"
        const val NOT_FOUND = "NOT_FOUND"

        fun of(code: String, message: String): ConfirmFailure = when (code) {
            RESOURCE_VERSION_CONFLICT -> Changed(Dialog(DialogKind.CHANGED, ""))
            QUOTE_ALREADY_ACCEPTED -> Leave(Dialog(DialogKind.ALREADY_MATCHED, ""))
            PARTNER_NOT_ELIGIBLE, PARTNER_CAPACITY_EXCEEDED, SCHEDULE_CONFLICT ->
                Leave(Dialog(DialogKind.PARTNER_UNAVAILABLE, message))
            REQUEST_EXPIRED, REQUEST_NOT_OPEN -> Leave(Dialog(DialogKind.REQUEST_CLOSED, message))
            NOT_FOUND -> Leave(Dialog(DialogKind.NOT_FOUND, ""))
            // NETWORK_ERROR, VALIDATION_ERROR, UNKNOWN and anything new: stay, the customer can try again.
            else -> Retry(message)
        }
    }
}

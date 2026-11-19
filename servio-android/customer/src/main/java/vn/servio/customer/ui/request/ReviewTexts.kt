package vn.servio.customer.ui.request

import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.OrderSummary
import vn.servio.customer.data.model.ScheduleType
import vn.servio.customer.ui.request.CreateRequestViewModel.Step
import java.time.LocalDate

/**
 * CS-11 summary lines as plain data, so they can be unit tested; [CreateRequestReviewFragment] turns them into text.
 */
object ReviewTexts {

    /** "Càng sớm càng tốt · trong vòng {windowHours} giờ" (#107) or "Thứ Bảy 17/10 · 14:00 – 16:00". */
    sealed interface TimeLine {
        data object Now : TimeLine
        data class Scheduled(val date: LocalDate, val startHour: Int) : TimeLine
    }

    /** Null while "Hẹn lịch" has no slot yet (the post is blocked on CS-09 then). */
    fun timeLine(draft: RequestDraft): TimeLine? = when (draft.scheduleType) {
        ScheduleType.NOW -> TimeLine.Now
        ScheduleType.SCHEDULED -> {
            val date = draft.scheduledDate
            val hour = draft.scheduledHour
            if (date != null && hour != null) TimeLine.Scheduled(date, hour) else null
        }
    }

    /** Index into `create_request_weekday_full` (Thứ Hai … Chủ Nhật). */
    fun weekdayIndex(date: LocalDate): Int = date.dayOfWeek.value - 1

    /** "300.000 – 500.000" (the screen adds " đ"); null for "Để người làm báo giá". */
    fun budgetRange(draft: RequestDraft): String? {
        val min = draft.budgetMinToSend ?: return null
        val max = draft.budgetMaxToSend ?: return null
        return "${MoneyInput.format(min)} – ${MoneyInput.format(max)}"
    }

    enum class Requirement { EXPERIENCE, CERTIFICATE, HIGH_RATING }

    /** In the CS-10 order; the certificate follows [certificate] (ticked and locked by the service). */
    fun requirements(draft: RequestDraft, certificate: CertificateState): List<Requirement> = buildList {
        if (draft.requireExperience) add(Requirement.EXPERIENCE)
        if (certificate.checked) add(Requirement.CERTIFICATE)
        if (draft.requireHighRating) add(Requirement.HIGH_RATING)
    }

    /** "Từ 3 năm kinh nghiệm · Có chứng chỉ nghề · bán kính 10 km"; [none] when nothing is required. */
    fun requirementsLine(labels: List<String>, none: String, radius: String): String =
        (labels.ifEmpty { listOf(none) } + radius).joinToString(SEPARATOR)

    enum class ExpiryNote { NOW_WINDOW, SCHEDULED_DAY_OR_START }

    /**
     * F-REQ-11, as the server sets ExpiresAt: NOW ends with its window (posting.windowHours), SCHEDULED at
     * min(posting.expireHours, start).
     */
    fun expiryNote(type: ScheduleType): ExpiryNote = when (type) {
        ScheduleType.NOW -> ExpiryNote.NOW_WINDOW
        ScheduleType.SCHEDULED -> ExpiryNote.SCHEDULED_DAY_OR_START
    }

    const val SEPARATOR = " · "
}

/** What CS-11 does with an error of #36. */
sealed interface PostFailure {
    /** Back to [step] (its errors shown), with [notice] in a snackbar; [serverMessage] is used for [StepNotice.SERVER]. */
    data class BackToStep(val step: Step, val notice: StepNotice, val serverMessage: String) : PostFailure

    /**
     * "Đóng", plus "Xem phí huỷ" for [DialogKind.OUTSTANDING_DEBT] and "Xem đơn" for [DialogKind.UNPAID_ORDER];
     * [message] is the server's, with its details (the fee owed, the order code). [orderId]: the order "Xem đơn" opens
     * (see [unpaidOrderId]); null opens CS-19.
     */
    data class Dialog(val kind: DialogKind, val message: String, val orderId: String? = null) : PostFailure

    /** Snackbar on CS-11; "Đăng yêu cầu" can be tapped again (same Idempotency-Key). */
    data class Message(val message: String) : PostFailure

    /**
     * CONSENT_REQUIRED: the terms of the current version are not agreed (withdrawn, or the policy changed). The consent
     * screen of :core opens over CS-11 and comes back to it with the draft untouched; nothing is posted by itself.
     */
    data object NeedsConsent : PostFailure

    enum class StepNotice { CONTACT_INFO, IMAGES_NOT_READY, SERVER }

    /**
     * [OUTSTANDING_DEBT]: unpaid cancellation fees above the limit. [UNPAID_ORDER]: an order the admin confirmed as
     * unpaid (AWAITING_PAYMENT, #177) still waits for the customer to pay the partner.
     */
    enum class DialogKind { PROFILE_INCOMPLETE, OUTSTANDING_DEBT, UNPAID_ORDER, POSTING_RESTRICTED }

    companion object {
        const val CONTACT_INFO_NOT_ALLOWED = "CONTACT_INFO_NOT_ALLOWED"
        const val FILE_NOT_READY = "FILE_NOT_READY"
        const val PROFILE_INCOMPLETE = "PROFILE_INCOMPLETE"
        const val OUTSTANDING_DEBT = "OUTSTANDING_DEBT"
        const val UNPAID_ORDER = "UNPAID_ORDER"
        const val POSTING_RESTRICTED = "POSTING_RESTRICTED"
        const val CONSENT_REQUIRED = "CONSENT_REQUIRED"
        const val VALIDATION_ERROR = "VALIDATION_ERROR"

        fun of(code: String, field: String?, message: String): PostFailure = when (code) {
            CONTACT_INFO_NOT_ALLOWED -> BackToStep(Step.DESCRIBE, StepNotice.CONTACT_INFO, message)
            FILE_NOT_READY -> BackToStep(Step.DESCRIBE, StepNotice.IMAGES_NOT_READY, message)
            PROFILE_INCOMPLETE -> Dialog(DialogKind.PROFILE_INCOMPLETE, message)
            OUTSTANDING_DEBT -> Dialog(DialogKind.OUTSTANDING_DEBT, message)
            UNPAID_ORDER -> Dialog(DialogKind.UNPAID_ORDER, message)
            POSTING_RESTRICTED -> Dialog(DialogKind.POSTING_RESTRICTED, message)
            CONSENT_REQUIRED -> NeedsConsent
            VALIDATION_ERROR -> stepOf(field)?.let { BackToStep(it, StepNotice.SERVER, message) } ?: Message(message)
            // NETWORK_ERROR (timeouts included), UNKNOWN and the rest: stay on CS-11.
            else -> Message(message)
        }

        /**
         * Where "Xem đơn" of UNPAID_ORDER goes: the id of the only AWAITING_PAYMENT order among [activeOrders] (#62
         * status=ACTIVE), so CS-20 opens on it. Null — CS-19 "Đang diễn ra" — when there is none, more than one, or
         * [complete] is false (more pages than the one read: another one may be there).
         */
        fun unpaidOrderId(activeOrders: List<OrderSummary>, complete: Boolean): String? {
            val unpaid = activeOrders.filter { it.status == OrderStatus.AWAITING_PAYMENT }
            return unpaid.singleOrNull()?.id?.takeIf { complete }
        }

        /** Maps a server field name of #36 to the step that edits it. */
        fun stepOf(field: String?): Step? = when {
            field == null -> null
            field == "title" || field == "description" || field == "serviceCategoryId" || field.startsWith("images") -> Step.DESCRIBE
            field == "addressId" || field == "scheduledStartAt" || field == "scheduleType" -> Step.ADDRESS_TIME
            field.startsWith("budget") || field.startsWith("require") || field == "searchRadiusKm" -> Step.BUDGET
            else -> null
        }
    }
}

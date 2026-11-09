package vn.servio.customer.ui.request

import android.net.Uri
import vn.servio.core.data.model.Category
import vn.servio.customer.data.model.Address
import vn.servio.customer.data.model.ScheduleType
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Everything typed on CS-08 → CS-10, shown again on CS-11 and sent as #36. */
data class RequestDraft(
    val title: String = "",
    val description: String = "",
    val images: List<DraftImage> = emptyList(),
    val address: Address? = null,
    val scheduleType: ScheduleType = ScheduleType.NOW,
    val scheduledDate: LocalDate? = null,
    /** Start hour of the chosen 2-hour slot, one of [ScheduleSlots.START_HOURS]. */
    val scheduledHour: Int? = null,
    /** "Để người làm báo giá" (the default): the budget boxes keep their numbers but nothing is sent. */
    val quoteByPartner: Boolean = true,
    /** CS-10 "Từ" / "Đến" as typed, null when empty. */
    val budgetMin: Long? = null,
    val budgetMax: Long? = null,
    val requireExperience: Boolean = false,
    /** The customer's own choice; a category that requires a certificate overrides it (see [certificateState]). */
    val requireCertificate: Boolean = false,
    val requireHighRating: Boolean = false,
    val searchRadiusKm: Int = DEFAULT_RADIUS_KM,
) {
    val hasBudget: Boolean get() = !quoteByPartner && budgetMin != null && budgetMax != null

    /** #36 budget: both null with "Để người làm báo giá". */
    val budgetMinToSend: Long? get() = if (hasBudget) budgetMin else null
    val budgetMaxToSend: Long? get() = if (hasBudget) budgetMax else null

    companion object {
        const val TITLE_MIN = 5
        const val TITLE_MAX = 150
        const val DESCRIPTION_MIN = 20
        const val DESCRIPTION_MAX = 2000
        const val MAX_IMAGES = 6

        // CS-10 choices from Figma: "Có từ 3 năm kinh nghiệm", "Đánh giá từ 4,5 sao", radius chips.
        const val EXPERIENCE_YEARS = 3
        const val MIN_RATING = 4.5
        const val DEFAULT_RADIUS_KM = 10
        val RADIUS_OPTIONS_KM = listOf(5, 10, 20)
    }
}

/** A picked photo. It is uploaded right after picking; [url] is set once POST /files succeeds. */
data class DraftImage(
    val localId: Long,
    val uri: Uri,
    val status: Status,
    val url: String? = null,
    val errorMessage: String? = null,
) {
    enum class Status { UPLOADING, FAILED, UPLOADED }
}

/** The chosen level-2 service and its level-1 group ("Sửa máy lạnh" · "Điện lạnh"). */
data class CategoryChoice(val service: Category, val group: Category)

/** CS-10 "Có chứng chỉ nghề": ticked and locked when the service requires a certificate. */
data class CertificateState(val checked: Boolean, val locked: Boolean)

fun certificateState(categoryRequires: Boolean, userChoice: Boolean): CertificateState =
    if (categoryRequires) CertificateState(checked = true, locked = true) else CertificateState(userChoice, locked = false)

/** CS-10 reference price of a service, shown only when both ends are known. */
data class PriceRange(val min: Long, val max: Long)

fun referenceRange(category: Category): PriceRange? {
    val min = category.referencePriceMin ?: return null
    val max = category.referencePriceMax ?: return null
    return PriceRange(min, max)
}

/**
 * CS-10 money boxes: digits only, grouped with "." (300.000), at most [MAX_DIGITS] digits, no leading zero.
 * Locale-independent so the format matches Figma whatever the device language.
 */
object MoneyInput {
    const val MAX_DIGITS = 9

    fun format(amount: Long): String = amount.toString().reversed().chunked(3).joinToString(".").reversed()

    /** The digits of [text] as a number; null when it has none. */
    fun parse(text: CharSequence): Long? = normalize(digitsOf(text)).takeIf { it.isNotEmpty() }?.toLongOrNull()

    data class Edit(val text: String, val cursor: Int)

    /**
     * The box text after an edit: [previous] is the text before it, [edited] what the field now holds and [cursor]
     * where its cursor is. Returns null when the edit would go over [MAX_DIGITS] digits: the caller restores
     * [previous]. The cursor keeps the same number of digits in front of it, so editing mid-number does not jump to
     * the end. Backspace over a "." removes the digit in front of it, as the dot alone would come straight back.
     */
    fun reformat(previous: CharSequence, edited: CharSequence, cursor: Int): Edit? {
        var text: CharSequence = edited
        var at = cursor.coerceIn(0, edited.length)
        if (edited.length < previous.length && digitsOf(edited) == digitsOf(previous)) {
            val digitIndex = (at - 1 downTo 0).firstOrNull { edited[it] in '0'..'9' }
            if (digitIndex != null) {
                text = StringBuilder(edited).deleteCharAt(digitIndex)
                at = digitIndex
            }
        }
        val digits = digitsOf(text)
        if (digits.length > MAX_DIGITS) return null
        val before = digitsOf(text.subSequence(0, at))
        val normalized = normalize(digits)
        val dropped = digits.length - normalized.length
        val digitsBefore = (before.length - dropped).coerceIn(0, normalized.length)
        val formatted = normalized.toLongOrNull()?.let(::format).orEmpty()
        return Edit(formatted, cursorAfter(formatted, digitsBefore))
    }

    private fun digitsOf(text: CharSequence) = text.filter { it in '0'..'9' }.toString()

    /** "000300" → "300"; a lone "0" stays. */
    private fun normalize(digits: String) = digits.trimStart('0').ifEmpty { if (digits.isEmpty()) "" else "0" }

    private fun cursorAfter(formatted: String, digits: Int): Int {
        if (digits == 0) return 0
        var seen = 0
        formatted.forEachIndexed { index, char ->
            if (char != '.') seen++
            if (seen == digits) return index + 1
        }
        return formatted.length
    }
}

/**
 * CS-09 time slots: 2-hour windows starting at [START_HOURS], Vietnam time. A slot can be booked when it starts
 * at least 30 minutes from now and at most 30 days from now — the same window the server checks for #36.
 */
object ScheduleSlots {
    val ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
    val START_HOURS = listOf(8, 10, 14, 16, 18)
    const val WINDOW_HOURS = 2L
    const val MAX_DAYS_AHEAD = 30L
    private val MIN_LEAD: Duration = Duration.ofMinutes(30)

    fun startOf(date: LocalDate, hour: Int): ZonedDateTime = date.atTime(hour, 0).atZone(ZONE)

    fun isAvailable(date: LocalDate, hour: Int, now: ZonedDateTime): Boolean {
        val start = startOf(date, hour)
        return !start.isBefore(now.plus(MIN_LEAD)) && !start.isAfter(now.plusDays(MAX_DAYS_AHEAD))
    }

    /** Today through today + 30 days; a day is only selectable when at least one of its slots is. */
    fun days(now: ZonedDateTime): List<LocalDate> {
        val today = now.withZoneSameInstant(ZONE).toLocalDate()
        return (0..MAX_DAYS_AHEAD).map { today.plusDays(it) }
    }

    fun hasAvailableSlot(date: LocalDate, now: ZonedDateTime): Boolean = START_HOURS.any { isAvailable(date, it, now) }

    /** Today, or tomorrow once today's last slot is too close. */
    fun firstAvailableDay(now: ZonedDateTime): LocalDate? = days(now).firstOrNull { hasAvailableSlot(it, now) }
}

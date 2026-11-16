package vn.servio.customer.ui.requestdetail

import android.content.Context
import vn.servio.customer.R
import vn.servio.customer.data.model.QuotePartner
import vn.servio.customer.ui.request.MoneyInput
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CS-15/CS-16 texts as plain data, so they can be unit tested; [QuoteFormat] turns them into strings.
 * `weekdays` is the `weekday_short` array: Monday first ("T2" … "T7", "CN").
 */
object QuoteTexts {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
    private val VIETNAMESE = Locale("vi", "VN")

    private fun parse(iso: String?, zone: ZoneId): ZonedDateTime? =
        iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }

    /** "T7 17/10". */
    private fun day(at: ZonedDateTime, weekdays: List<String>): String =
        "${weekdays[at.dayOfWeek.value - 1]} ${DAY.format(at)}"

    /** Absolute moment in the phone's zone: "14:00, T7 17/10". Null when [iso] is missing or unreadable. */
    fun moment(iso: String?, weekdays: List<String>, zone: ZoneId = ZoneId.systemDefault()): String? =
        parse(iso, zone)?.let { "${TIME.format(it)}, ${day(it, weekdays)}" }

    /** "T7 17/10 · 14:00–16:00" (the day is the start's). */
    fun window(startIso: String?, endIso: String?, weekdays: List<String>, zone: ZoneId = ZoneId.systemDefault()): String? {
        val start = parse(startIso, zone) ?: return null
        val end = parse(endIso, zone) ?: return null
        return "${day(start, weekdays)} · ${TIME.format(start)}–${TIME.format(end)}"
    }

    data class Duration(val hours: Int, val minutes: Int)

    /** Up to two hours stays in minutes ("90 phút", "120 phút", as in Figma); longer jobs are split into hours and minutes. */
    fun duration(totalMinutes: Int): Duration =
        if (totalMinutes <= 120) Duration(0, totalMinutes) else Duration(totalMinutes / 60, totalMinutes % 60)

    /** How long until a request expires. */
    sealed interface Remaining {
        data class Hours(val hours: Int) : Remaining
        data class Minutes(val minutes: Int) : Remaining
        data object Past : Remaining
    }

    /** Whole hours left ("22 giờ"), minutes in the last hour (at least 1); null when [expiresAtIso] is unreadable. */
    fun remaining(expiresAtIso: String?, now: Instant = Instant.now()): Remaining? {
        val expiresAt = expiresAtIso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return null
        val seconds = java.time.Duration.between(now, expiresAt).seconds
        return when {
            seconds <= 0 -> Remaining.Past
            seconds >= 3600 -> Remaining.Hours((seconds / 3600).toInt())
            else -> Remaining.Minutes(maxOf(1, (seconds / 60).toInt()))
        }
    }

    /** "4,8"; null when the partner has no review yet (the screen then says so instead of showing 0,0). */
    fun rating(average: Double?, totalReviews: Int): String? =
        if (average == null || totalReviews <= 0) null else String.format(VIETNAMESE, "%.1f", average)

    /** "Trần Văn Phúc" → "VP" (the last two words); one word gives one letter. Shown when there is no avatar. */
    fun initials(fullName: String): String =
        fullName.trim().split(' ').filter { it.isNotEmpty() }.takeLast(2).joinToString("") { it.take(1) }.uppercase(VIETNAMESE)
}

/** Strings shared by the quote card (CS-15) and the quote sheet (CS-16). */
object QuoteFormat {

    fun weekdays(context: Context): List<String> = context.resources.getStringArray(R.array.weekday_short).toList()

    fun money(context: Context, amount: Long): String = context.getString(R.string.quote_price, MoneyInput.format(amount))

    /** "4,8" or null when there is no review. */
    fun score(partner: QuotePartner): String? = QuoteTexts.rating(partner.averageRating, partner.totalReviews)

    fun moment(context: Context, iso: String): String = QuoteTexts.moment(iso, weekdays(context)) ?: iso

    /** "90 phút", "120 phút", "3 giờ", "2 giờ 30 phút". */
    fun duration(context: Context, totalMinutes: Int): String {
        val (hours, minutes) = QuoteTexts.duration(totalMinutes)
        return when {
            hours == 0 -> context.getString(R.string.quote_duration_minutes, minutes)
            minutes == 0 -> context.getString(R.string.quote_duration_hours, hours)
            else -> context.getString(R.string.quote_duration_hours_minutes, hours, minutes)
        }
    }

    /** "Hết hạn sau 22 giờ" / "Hết hạn sau 15 phút" / "Đã hết hạn"; null when the time is unreadable. */
    fun expiry(context: Context, expiresAtIso: String?): String? = when (val left = QuoteTexts.remaining(expiresAtIso)) {
        is QuoteTexts.Remaining.Hours -> context.getString(R.string.request_detail_expires_hours, left.hours)
        is QuoteTexts.Remaining.Minutes -> context.getString(R.string.request_detail_expires_minutes, left.minutes)
        QuoteTexts.Remaining.Past -> context.getString(R.string.request_detail_expired)
        null -> null
    }
}

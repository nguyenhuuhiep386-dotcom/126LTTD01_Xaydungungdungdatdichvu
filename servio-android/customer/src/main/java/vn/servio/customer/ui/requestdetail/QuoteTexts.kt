package vn.servio.customer.ui.requestdetail

import android.content.Context
import vn.servio.customer.R
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.QuotePartner
import vn.servio.customer.ui.request.MoneyInput
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** CS-15/CS-16 texts as plain data, so they can be unit tested; [QuoteFormat] turns them into strings. */
object QuoteTexts {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
    private val VIETNAMESE = Locale("vi", "VN")

    private fun parse(iso: String?, zone: ZoneId): ZonedDateTime? =
        iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }

    /** Absolute moment in the phone's zone: "14:30 · 11/11". Null when [iso] is missing or unreadable. */
    fun moment(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
        parse(iso, zone)?.let { "${TIME.format(it)} · ${DAY.format(it)}" }

    /** "14:00 – 16:00 · 17/10" (the day is the start's). */
    fun window(startIso: String?, endIso: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        val start = parse(startIso, zone) ?: return null
        val end = parse(endIso, zone) ?: return null
        return "${TIME.format(start)} – ${TIME.format(end)} · ${DAY.format(start)}"
    }

    data class Duration(val hours: Int, val minutes: Int)

    fun duration(totalMinutes: Int): Duration = Duration(totalMinutes / 60, totalMinutes % 60)

    /** "4,8"; null when the partner has no review yet (the screen then says so instead of showing 0,0). */
    fun rating(average: Double?, totalReviews: Int): String? =
        if (average == null || totalReviews <= 0) null else String.format(VIETNAMESE, "%.1f", average)

    /** "1,2" (km). */
    fun distance(km: Double?): String? = km?.let { String.format(VIETNAMESE, "%.1f", it) }

    /** First letter of the given name ("Nguyễn Văn An" → "A"), shown when there is no avatar. */
    fun initial(fullName: String): String =
        fullName.trim().substringAfterLast(' ').take(1).uppercase(VIETNAMESE)
}

/** Strings shared by the quote card (CS-15) and the quote sheet (CS-16). */
object QuoteFormat {

    fun price(context: Context, amount: Long): String = context.getString(R.string.quote_price, MoneyInput.format(amount))

    fun rating(context: Context, partner: QuotePartner): String =
        QuoteTexts.rating(partner.averageRating, partner.totalReviews)
            ?.let { context.getString(R.string.quote_rating, it, partner.totalReviews) }
            ?: context.getString(R.string.quote_no_reviews)

    /** "5 năm kinh nghiệm · 12 đơn hoàn thành": the server sends no skill names, so none are shown. */
    fun stats(context: Context, partner: QuotePartner): String = listOf(
        context.getString(R.string.quote_experience, partner.yearsOfExperience),
        context.getString(R.string.quote_completed, partner.completedOrders),
    ).joinToString(" · ")

    fun available(context: Context, quote: CustomerQuote): String =
        context.getString(R.string.quote_available, QuoteTexts.moment(quote.availableFrom) ?: quote.availableFrom)

    fun duration(context: Context, totalMinutes: Int): String {
        val (hours, minutes) = QuoteTexts.duration(totalMinutes)
        return when {
            hours == 0 -> context.getString(R.string.quote_duration_minutes, minutes)
            minutes == 0 -> context.getString(R.string.quote_duration_hours, hours)
            else -> context.getString(R.string.quote_duration_hours_minutes, hours, minutes)
        }
    }
}

package vn.servio.customer.ui.order

import vn.servio.customer.ui.requestdetail.QuoteTexts
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Dates and numbers of the order screens (CS-19, CS-20, CS-20c). Everything comes from the server's fields. */
object OrderTexts {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

    private fun parse(iso: String?, zone: ZoneId): ZonedDateTime? =
        iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }

    private fun instant(iso: String?): Instant? = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    /** No-break space: keeps "100.000" with "đ" and "T5" with "19/11" on one line in the sentences these screens build. */
    val NBSP: Char = 0xA0.toChar()

    /** "100.000 đ" with its space made a no-break space (the amount itself comes from QuoteFormat, unchanged). */
    fun keepTogether(text: String): String = text.replace(' ', NBSP)

    /** "T5 19/11" joined by a no-break space. */
    private fun day(at: ZonedDateTime, weekdays: List<String>): String = "${weekdays[at.dayOfWeek.value - 1]}$NBSP${DAY.format(at)}"

    /** CS-19 card: "T7 17/10 · 14:00" in the phone's zone. Null when [iso] is missing or unreadable. */
    fun dayTime(iso: String?, weekdays: List<String>, zone: ZoneId = ZoneId.systemDefault()): String? =
        parse(iso, zone)?.let { "${day(it, weekdays)} · ${TIME.format(it)}" }

    /** "14:00". */
    fun time(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? = parse(iso, zone)?.let { TIME.format(it) }

    /** "14:00, T7 17/10", the moment format of CS-15 and CS-16, with the weekday and the date kept together. */
    fun moment(iso: String?, weekdays: List<String>, zone: ZoneId = ZoneId.systemDefault()): String? =
        parse(iso, zone)?.let { "${TIME.format(it)}, ${day(it, weekdays)}" }

    /**
     * Minutes the partner was given to accept: acceptanceDeadlineAt − createdAt of the order, to the nearest minute
     * (both are written in the same transaction, a few milliseconds apart). Null when a time is missing or not after.
     */
    fun confirmWindowMinutes(createdAtIso: String?, deadlineIso: String?): Int? {
        val created = instant(createdAtIso) ?: return null
        val deadline = instant(deadlineIso) ?: return null
        val seconds = Duration.between(created, deadline).seconds
        if (seconds <= 0) return null
        return ((seconds + 30) / 60).toInt().coerceAtLeast(1)
    }

    /** Whole seconds from [now] until [iso]; negative once past, null when unreadable. */
    fun secondsUntil(iso: String?, now: Instant = Instant.now()): Long? =
        instant(iso)?.let { Duration.between(now, it).seconds }

    /** Countdown "08:42" (minutes may pass 59: "75:00"); never below "00:00". */
    fun countdown(seconds: Long): String {
        val left = seconds.coerceAtLeast(0)
        return "%02d:%02d".format(left / 60, left % 60)
    }

    /** True when [iso] is at or before [now] (an unreadable time counts as past, so the preview is fetched again). */
    fun isPast(iso: String?, now: Instant = Instant.now()): Boolean = instant(iso)?.let { !it.isAfter(now) } ?: true

    /**
     * Partner rating of CS-20: [score] "4,8" or null ("Chưa có đánh giá"); [reviewCount] only when #27 answered.
     * #63 gives the score, #27 the number of reviews: when #27 failed the score stays alone, no invented count.
     */
    data class Rating(val score: String?, val reviewCount: Int?)

    fun rating(average: Double?, reviewCount: Int?): Rating =
        if (reviewCount == null) {
            Rating(QuoteTexts.rating(average, 1), null)
        } else {
            val score = QuoteTexts.rating(average, reviewCount)
            Rating(score, reviewCount.takeIf { score != null })
        }
}

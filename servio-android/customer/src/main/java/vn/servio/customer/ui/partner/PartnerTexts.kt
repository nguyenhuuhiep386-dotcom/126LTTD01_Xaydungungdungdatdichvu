package vn.servio.customer.ui.partner

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** CS-17 texts as plain data, so they can be unit tested. The rating itself uses QuoteTexts.rating, as on CS-15/16/18. */
object PartnerTexts {
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

    /** Review date in the phone's zone: "02/10"; null when unreadable. */
    fun reviewDate(iso: String, zone: ZoneId = ZoneId.systemDefault()): String? =
        runCatching { DAY.format(OffsetDateTime.parse(iso).atZoneSameInstant(zone)) }.getOrNull()

    /** How many of the 5 stars are filled (the server allows 1–5; anything else is clamped). */
    fun filledStars(rating: Int): Int = rating.coerceIn(0, MAX_STARS)

    const val MAX_STARS = 5

    /** Figure of the "kinh nghiệm" cell: "6 năm", or "< 1 năm" for 0 (or an unexpected negative) years. */
    sealed interface Experience {
        data class Years(val years: Int) : Experience
        data object LessThanOneYear : Experience

        /**
         * "5 năm" / "< 1 năm" held together by no-break spaces, so a narrow line never leaves "năm" alone at the start
         * of the next one (CS-15 card, CS-17 cell). [unit] = "năm", [lessThanOne] = "< 1", both from strings.xml.
         */
        fun text(unit: String, lessThanOne: String): String = when (this) {
            is Years -> "$years$NBSP$unit"
            LessThanOneYear -> "${lessThanOne.replace(' ', NBSP)}$NBSP$unit"
        }
    }

    const val NBSP: Char = ' '

    fun experience(years: Int): Experience = if (years >= 1) Experience.Years(years) else Experience.LessThanOneYear

    /** Reviews shown on CS-17: one page, no "load more" (plan approved 11/2026). */
    const val REVIEW_PAGE_SIZE = 10
}

package vn.servio.customer.ui.requestdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class QuoteTextsTest {

    private val vietnam = ZoneId.of("Asia/Ho_Chi_Minh")

    @Test
    fun momentIsAbsoluteInThePhoneZone() {
        // The server sends UTC with 7 fraction digits.
        assertEquals("23:50 · 11/11", QuoteTexts.moment("2026-11-11T16:50:04.5691198+00:00", vietnam))
        // Past midnight locally: the day changes too.
        assertEquals("00:30 · 12/11", QuoteTexts.moment("2026-11-11T17:30:00+00:00", vietnam))
        assertEquals("09:05 · 01/01", QuoteTexts.moment("2027-01-01T09:05:00+07:00", vietnam))
    }

    @Test
    fun momentIsNullWhenMissingOrUnreadable() {
        assertNull(QuoteTexts.moment(null, vietnam))
        assertNull(QuoteTexts.moment("", vietnam))
        assertNull(QuoteTexts.moment("11/11/2026", vietnam))
    }

    @Test
    fun windowShowsBothHoursAndTheDay() {
        assertEquals(
            "14:00 – 16:00 · 17/10",
            QuoteTexts.window("2026-10-17T07:00:00+00:00", "2026-10-17T09:00:00+00:00", vietnam),
        )
        assertNull(QuoteTexts.window(null, "2026-10-17T09:00:00+00:00", vietnam))
        assertNull(QuoteTexts.window("2026-10-17T07:00:00+00:00", null, vietnam))
    }

    @Test
    fun durationSplitsHoursAndMinutes() {
        assertEquals(QuoteTexts.Duration(0, 45), QuoteTexts.duration(45))
        assertEquals(QuoteTexts.Duration(1, 0), QuoteTexts.duration(60))
        assertEquals(QuoteTexts.Duration(1, 30), QuoteTexts.duration(90))
        assertEquals(QuoteTexts.Duration(24, 0), QuoteTexts.duration(1440))
    }

    @Test
    fun ratingIsHiddenWithoutReviews() {
        assertNull(QuoteTexts.rating(null, 0))
        assertNull(QuoteTexts.rating(0.0, 0))
        assertNull(QuoteTexts.rating(null, 5))
        assertEquals("4,8", QuoteTexts.rating(4.8, 12))
        assertEquals("5,0", QuoteTexts.rating(5.0, 1))
    }

    @Test
    fun distanceUsesACommaAndOneDecimal() {
        assertNull(QuoteTexts.distance(null))
        assertEquals("1,2", QuoteTexts.distance(1.2))
        assertEquals("0,1", QuoteTexts.distance(0.07))
    }

    @Test
    fun initialIsTheFirstLetterOfTheGivenName() {
        assertEquals("A", QuoteTexts.initial("Nguyễn Văn An"))
        assertEquals("M", QuoteTexts.initial("Tho Mot"))
        assertEquals("Đ", QuoteTexts.initial(" đạt "))
        assertEquals("", QuoteTexts.initial(""))
    }
}

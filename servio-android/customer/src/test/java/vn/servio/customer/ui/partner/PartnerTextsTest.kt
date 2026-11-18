package vn.servio.customer.ui.partner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.customer.ui.requestdetail.QuoteTexts
import java.time.ZoneId

class PartnerTextsTest {

    private val vietnam = ZoneId.of("Asia/Ho_Chi_Minh")

    @Test
    fun `review date is dd MM in the phone zone`() {
        assertEquals("02/10", PartnerTexts.reviewDate("2026-10-02T08:00:00+07:00", vietnam))
        // 18:09 UTC on 17/11 is already 18/11 in Vietnam.
        assertEquals("18/11", PartnerTexts.reviewDate("2026-11-17T18:09:43.3909362+00:00", vietnam))
        assertNull(PartnerTexts.reviewDate("not a date", vietnam))
    }

    @Test
    fun `stars follow the rating and stay within 0 to 5`() {
        assertEquals(4, PartnerTexts.filledStars(4))
        assertEquals(5, PartnerTexts.filledStars(5))
        assertEquals(5, PartnerTexts.filledStars(9))
        assertEquals(0, PartnerTexts.filledStars(-1))
    }

    @Test
    fun `experience under one year is its own case`() {
        assertEquals(PartnerTexts.Experience.Years(6), PartnerTexts.experience(6))
        assertEquals(PartnerTexts.Experience.Years(1), PartnerTexts.experience(1))
        assertEquals(PartnerTexts.Experience.LessThanOneYear, PartnerTexts.experience(0))
        assertEquals(PartnerTexts.Experience.LessThanOneYear, PartnerTexts.experience(-1))
    }

    @Test
    fun `score uses a comma like the quote card and is null without reviews`() {
        assertEquals("4,5", QuoteTexts.rating(4.5, 2))
        assertEquals("4,8", QuoteTexts.rating(4.75, 32))
        assertNull(QuoteTexts.rating(null, 0))
        assertNull(QuoteTexts.rating(0.0, 0))
    }

    @Test
    fun `reviewer initials come from the shortened name`() {
        assertEquals("LN", QuoteTexts.initials("Lan N."))
        assertEquals("TH", QuoteTexts.initials("Thị Hồng"))
    }
}

package vn.servio.customer.ui.requestdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.customer.ui.requestdetail.QuoteTexts.Remaining
import java.time.Instant
import java.time.ZoneId

class QuoteTextsTest {

    private val vietnam = ZoneId.of("Asia/Ho_Chi_Minh")
    private val weekdays = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

    @Test
    fun momentIsAbsoluteInThePhoneZone() {
        // The server sends UTC with 7 fraction digits. 17/10/2026 is a Saturday.
        assertEquals("14:00, T7 17/10", QuoteTexts.moment("2026-10-17T07:00:00.0000000+00:00", weekdays, vietnam))
        // Past midnight locally: the day and the weekday change too (11/11/2026 is a Wednesday).
        assertEquals("23:50, T4 11/11", QuoteTexts.moment("2026-11-11T16:50:04.5691198+00:00", weekdays, vietnam))
        assertEquals("00:30, T5 12/11", QuoteTexts.moment("2026-11-11T17:30:00+00:00", weekdays, vietnam))
        assertEquals("09:05, CN 18/10", QuoteTexts.moment("2026-10-18T09:05:00+07:00", weekdays, vietnam))
    }

    @Test
    fun momentWithFullWeekdaysAsOnCs18() {
        // CS-18 passes the CS-11 array `create_request_weekday_full` (Monday first, as ReviewTexts.weekdayIndex).
        val full = listOf("Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật")
        assertEquals("14:00, Thứ Bảy 17/10", QuoteTexts.moment("2026-10-17T07:00:00+00:00", full, vietnam))
        assertEquals("09:05, Chủ Nhật 18/10", QuoteTexts.moment("2026-10-18T09:05:00+07:00", full, vietnam))
    }

    @Test
    fun momentIsNullWhenMissingOrUnreadable() {
        assertNull(QuoteTexts.moment(null, weekdays, vietnam))
        assertNull(QuoteTexts.moment("", weekdays, vietnam))
        assertNull(QuoteTexts.moment("11/11/2026", weekdays, vietnam))
    }

    @Test
    fun windowShowsTheDayThenBothHours() {
        assertEquals(
            "T7 17/10 · 14:00–16:00",
            QuoteTexts.window("2026-10-17T07:00:00+00:00", "2026-10-17T09:00:00+00:00", weekdays, vietnam),
        )
        assertNull(QuoteTexts.window(null, "2026-10-17T09:00:00+00:00", weekdays, vietnam))
        assertNull(QuoteTexts.window("2026-10-17T07:00:00+00:00", null, weekdays, vietnam))
    }

    @Test
    fun durationStaysInMinutesUpToTwoHours() {
        assertEquals(QuoteTexts.Duration(0, 45), QuoteTexts.duration(45))
        assertEquals(QuoteTexts.Duration(0, 60), QuoteTexts.duration(60))
        assertEquals(QuoteTexts.Duration(0, 90), QuoteTexts.duration(90))
        assertEquals(QuoteTexts.Duration(0, 120), QuoteTexts.duration(120))
        assertEquals(QuoteTexts.Duration(2, 1), QuoteTexts.duration(121))
        assertEquals(QuoteTexts.Duration(3, 0), QuoteTexts.duration(180))
        assertEquals(QuoteTexts.Duration(2, 30), QuoteTexts.duration(150))
        assertEquals(QuoteTexts.Duration(24, 0), QuoteTexts.duration(1440))
    }

    @Test
    fun remainingCountsWholeHoursThenMinutes() {
        val now = Instant.parse("2026-10-16T07:00:00Z")
        assertEquals(Remaining.Hours(22), QuoteTexts.remaining("2026-10-17T05:59:00+00:00", now))
        assertEquals(Remaining.Hours(1), QuoteTexts.remaining("2026-10-16T08:00:00+00:00", now))
        assertEquals(Remaining.Minutes(59), QuoteTexts.remaining("2026-10-16T07:59:30+00:00", now))
        assertEquals(Remaining.Minutes(1), QuoteTexts.remaining("2026-10-16T07:00:20+00:00", now))
        assertEquals(Remaining.Past, QuoteTexts.remaining("2026-10-16T07:00:00+00:00", now))
        assertEquals(Remaining.Past, QuoteTexts.remaining("2026-10-15T07:00:00+00:00", now))
        // Offsets are compared as instants.
        assertEquals(Remaining.Hours(2), QuoteTexts.remaining("2026-10-16T16:00:00+07:00", now))
        assertNull(QuoteTexts.remaining(null, now))
        assertNull(QuoteTexts.remaining("soon", now))
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
    fun initialsAreTheLastTwoWords() {
        assertEquals("VP", QuoteTexts.initials("Trần Văn Phúc"))
        assertEquals("QH", QuoteTexts.initials("Lê Quang Huy"))
        assertEquals("TM", QuoteTexts.initials("Tho Mot"))
        assertEquals("Đ", QuoteTexts.initials(" đạt "))
        assertEquals("AB", QuoteTexts.initials("an   bình"))
        assertEquals("", QuoteTexts.initials(""))
    }
}

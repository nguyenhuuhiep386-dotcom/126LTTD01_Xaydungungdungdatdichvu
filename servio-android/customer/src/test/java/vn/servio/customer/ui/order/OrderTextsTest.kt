package vn.servio.customer.ui.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class OrderTextsTest {

    private val weekdays = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
    private val vietnam = ZoneId.of("Asia/Ho_Chi_Minh")

    @Test
    fun `card schedule reads weekday, day and time in the phone's zone`() {
        // 17/10/2026 is a Saturday; 07:00Z is 14:00 in Vietnam.
        assertEquals("T7${OrderTexts.NBSP}17/10 · 14:00", OrderTexts.dayTime("2026-10-17T07:00:00Z", weekdays, vietnam))
        assertEquals("CN${OrderTexts.NBSP}18/10 · 09:05", OrderTexts.dayTime("2026-10-18T09:05:00+07:00", weekdays, vietnam))
    }

    @Test
    fun `missing or unreadable times give null, not a made-up date`() {
        assertNull(OrderTexts.dayTime(null, weekdays, vietnam))
        assertNull(OrderTexts.dayTime("hôm nay", weekdays, vietnam))
        assertNull(OrderTexts.time(null, vietnam))
    }

    @Test
    fun `time and moment`() {
        assertEquals("13:40", OrderTexts.time("2026-10-17T06:40:12Z", vietnam))
        assertEquals("14:00, T7${OrderTexts.NBSP}17/10", OrderTexts.moment("2026-10-17T07:00:00Z", weekdays, vietnam))
    }

    @Test
    fun `money keeps the amount and the currency together`() {
        assertEquals("100.000${OrderTexts.NBSP}đ", OrderTexts.keepTogether("100.000 đ"))
        assertEquals("20.000${OrderTexts.NBSP}đ", OrderTexts.keepTogether("20.000${OrderTexts.NBSP}đ"))
    }

    @Test
    fun `countdown is mm ss and never negative`() {
        assertEquals("08:42", OrderTexts.countdown(8 * 60 + 42))
        assertEquals("00:05", OrderTexts.countdown(5))
        assertEquals("00:00", OrderTexts.countdown(0))
        assertEquals("00:00", OrderTexts.countdown(-12))
        assertEquals("75:00", OrderTexts.countdown(75 * 60))
    }

    @Test
    fun `confirm window is the deadline minus the creation, to the nearest minute`() {
        // Written in one transaction a few milliseconds apart.
        assertEquals(10, OrderTexts.confirmWindowMinutes("2026-11-18T03:00:00.0123456Z", "2026-11-18T03:10:00.0187654Z"))
        assertEquals(15, OrderTexts.confirmWindowMinutes("2026-11-18T10:00:00+07:00", "2026-11-18T03:15:00Z"))
        assertEquals(7, OrderTexts.confirmWindowMinutes("2026-11-18T03:00:00Z", "2026-11-18T03:06:40Z"))
    }

    @Test
    fun `confirm window is unknown without both times or when the deadline is not after`() {
        assertNull(OrderTexts.confirmWindowMinutes(null, "2026-11-18T03:10:00Z"))
        assertNull(OrderTexts.confirmWindowMinutes("2026-11-18T03:00:00Z", null))
        assertNull(OrderTexts.confirmWindowMinutes("", "2026-11-18T03:10:00Z"))
        assertNull(OrderTexts.confirmWindowMinutes("2026-11-18T03:10:00Z", "2026-11-18T03:00:00Z"))
    }

    @Test
    fun `seconds until and past`() {
        val now = Instant.parse("2026-11-18T03:00:00Z")
        assertEquals(522L, OrderTexts.secondsUntil("2026-11-18T03:08:42Z", now))
        assertEquals(-3L, OrderTexts.secondsUntil("2026-11-18T02:59:57Z", now))
        assertNull(OrderTexts.secondsUntil(null, now))
        assertFalse(OrderTexts.isPast("2026-11-18T03:00:30Z", now))
        assertTrue(OrderTexts.isPast("2026-11-18T03:00:00Z", now))
        assertTrue(OrderTexts.isPast("không đọc được", now))
    }

    @Test
    fun `rating shows the count of 27, or the score alone when 27 failed`() {
        assertEquals(OrderTexts.Rating("4,8", 32), OrderTexts.rating(4.8, 32))
        assertEquals(OrderTexts.Rating("4,8", null), OrderTexts.rating(4.8, null))
        assertEquals(OrderTexts.Rating(null, null), OrderTexts.rating(null, null))
        assertEquals(OrderTexts.Rating(null, null), OrderTexts.rating(null, 0))
        // No reviews according to #27: "Chưa có đánh giá", never "0,0".
        assertEquals(OrderTexts.Rating(null, null), OrderTexts.rating(0.0, 0))
    }
}

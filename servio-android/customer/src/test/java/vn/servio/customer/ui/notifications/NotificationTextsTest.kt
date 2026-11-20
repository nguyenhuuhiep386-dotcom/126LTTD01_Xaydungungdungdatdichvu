package vn.servio.customer.ui.notifications

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.customer.data.model.AppNotification
import vn.servio.customer.ui.notifications.NotificationTexts.Day
import vn.servio.customer.ui.notifications.NotificationTexts.Icon
import vn.servio.customer.ui.notifications.NotificationTexts.Line
import vn.servio.customer.ui.notifications.NotificationTexts.Target
import java.time.LocalDate

class NotificationTextsTest {

    private val weekdays = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
    private val nbsp = ' '

    /** Friday 20/11/2026 in Vietnam. */
    private val today = LocalDate.of(2026, 11, 20)

    private fun n(id: String, createdAt: String, deepLink: String? = null, isRead: Boolean = false) =
        AppNotification(id = id, type = "NEW_QUOTE", title = "t$id", body = "b$id", deepLink = deepLink, isRead = isRead, createdAt = createdAt)

    // ---- Day groups

    @Test
    fun `today and yesterday by name, older days like the rest of the app`() {
        assertEquals(Day.Today, NotificationTexts.day(today, today, weekdays))
        assertEquals(Day.Yesterday, NotificationTexts.day(today.minusDays(1), today, weekdays))
        assertEquals(Day.Date("T5${nbsp}12/11"), NotificationTexts.day(LocalDate.of(2026, 11, 12), today, weekdays))
        assertEquals(Day.Date("T4${nbsp}18/11"), NotificationTexts.day(LocalDate.of(2026, 11, 18), today, weekdays))
    }

    @Test
    fun `another year adds the year`() {
        assertEquals(Day.Date("T4${nbsp}31/12/2025"), NotificationTexts.day(LocalDate.of(2025, 12, 31), today, weekdays))
        // 1 January seen on 2 January: yesterday wins over the year.
        assertEquals(Day.Yesterday, NotificationTexts.day(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2), weekdays))
    }

    @Test
    fun `days are cut at midnight in Vietnam, not UTC`() {
        val lines = NotificationTexts.lines(
            listOf(
                n("a", "2026-11-20T01:30:00Z"), // 08:30 today in VN
                n("b", "2026-11-19T17:05:00Z"), // 00:05 today in VN although 19/11 in UTC
                n("c", "2026-11-19T16:55:00Z"), // 23:55 yesterday in VN
                n("d", "2026-11-18T10:00:00+07:00"),
            ),
            today, weekdays,
        )
        assertEquals(
            listOf(
                Line.Header(Day.Today),
                Line.Row(n("a", "2026-11-20T01:30:00Z"), "08:30"),
                Line.Row(n("b", "2026-11-19T17:05:00Z"), "00:05"),
                Line.Header(Day.Yesterday),
                Line.Row(n("c", "2026-11-19T16:55:00Z"), "23:55"),
                Line.Header(Day.Date("T4${nbsp}18/11")),
                Line.Row(n("d", "2026-11-18T10:00:00+07:00"), "10:00"),
            ),
            lines,
        )
    }

    @Test
    fun `an unreadable time stays under the header before it and shows no time`() {
        val lines = NotificationTexts.lines(listOf(n("a", "2026-11-19T10:00:00+07:00"), n("b", "")), today, weekdays)
        assertEquals(listOf(Line.Header(Day.Yesterday), Line.Row(n("a", "2026-11-19T10:00:00+07:00"), "10:00"), Line.Row(n("b", ""), "")), lines)
        assertEquals(listOf(Line.Header(Day.Today), Line.Row(n("b", ""), "")), NotificationTexts.lines(listOf(n("b", "")), today, weekdays))
        assertEquals(emptyList<Line>(), NotificationTexts.lines(emptyList(), today, weekdays))
    }

    // ---- deepLink → screen

    @Test
    fun `deepLinks of the server open CS-20, CS-15 and CS-30`() {
        assertEquals(Target.Order("o1"), NotificationTexts.fromDeepLink("orders/o1"))
        assertEquals(Target.Order("o1"), NotificationTexts.fromDeepLink("/orders/o1/"))
        assertEquals(Target.Request("r1"), NotificationTexts.fromDeepLink("service-requests/r1"))
        assertEquals(Target.Wallet, NotificationTexts.fromDeepLink("wallet"))
    }

    @Test
    fun `other links are not a screen`() {
        assertNull(NotificationTexts.fromDeepLink(null))
        assertNull(NotificationTexts.fromDeepLink(""))
        assertNull(NotificationTexts.fromDeepLink("orders"))
        assertNull(NotificationTexts.fromDeepLink("orders/o1/complete"))
        assertNull(NotificationTexts.fromDeepLink("conversations/c1"))
        assertNull(NotificationTexts.fromDeepLink("partner/kyc"))
    }

    @Test
    fun `without a usable link the ids of data are used, else only read`() {
        val order = buildJsonObject { put("orderId", "o2"); put("reviewId", "rv") }
        val request = buildJsonObject { put("serviceRequestId", "r2"); put("quoteId", "q") }
        val payment = buildJsonObject { put("transactionId", "t") }
        assertEquals(Target.Order("o2"), NotificationTexts.target(n("x", "", deepLink = "bad/link").copy(data = order)))
        assertEquals(Target.Request("r2"), NotificationTexts.target(n("x", "").copy(data = request)))
        assertEquals(Target.Wallet, NotificationTexts.target(n("x", "").copy(data = payment)))
        assertEquals(Target.None, NotificationTexts.target(n("x", "", deepLink = "conversations/c1")))
        assertEquals(Target.None, NotificationTexts.target(n("x", "").copy(data = buildJsonObject { put("orderId", JsonPrimitive(5)) })))
        // A good deepLink wins over data.
        assertEquals(Target.Request("r3"), NotificationTexts.target(n("x", "", deepLink = "service-requests/r3").copy(data = order)))
    }

    // ---- Icon

    @Test
    fun `icon by type, unknown types get the bell`() {
        assertEquals(Icon.QUOTE, NotificationTexts.icon("NEW_QUOTE"))
        assertEquals(Icon.ORDER, NotificationTexts.icon("ORDER_STATUS_CHANGED"))
        assertEquals(Icon.REVIEW, NotificationTexts.icon("REVIEW"))
        assertEquals(Icon.PAYMENT, NotificationTexts.icon("PAYMENT"))
        assertEquals(Icon.OTHER, NotificationTexts.icon("SYSTEM"))
        assertEquals(Icon.OTHER, NotificationTexts.icon(""))
    }

    // ---- Badge

    @Test
    fun `badge hidden at 0, the number up to 9, then 9+`() {
        assertNull(NotificationTexts.badge(null))
        assertNull(NotificationTexts.badge(0))
        assertNull(NotificationTexts.badge(-1))
        assertEquals("1", NotificationTexts.badge(1))
        assertEquals("9", NotificationTexts.badge(9))
        assertEquals("9+", NotificationTexts.badge(10))
        assertEquals("9+", NotificationTexts.badge(250))
    }
}

package vn.servio.customer.ui.dispute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.servio.customer.data.model.Dispute
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.ui.dispute.DisputeRules.BannerLine
import vn.servio.customer.ui.dispute.DisputeRules.DescriptionError
import vn.servio.customer.ui.dispute.DisputeRules.Reason
import vn.servio.customer.ui.dispute.DisputeRules.ResultLine
import java.time.Instant
import java.time.ZoneId

class DisputeRulesTest {

    private val weekdays = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
    private val nbsp = ' '
    private val vn = ZoneId.of("Asia/Ho_Chi_Minh")

    private fun order(status: OrderStatus, completedAt: String? = null) = OrderDetail(id = "o1", code = "OD1", status = status, completedAt = completedAt)

    private fun dispute(code: String, status: String, sla: String? = null, note: String? = null, by: String = "CUSTOMER") =
        Dispute(id = code, code = code, status = status, slaDueAt = sla, resolutionNote = note, raisedByType = by)

    // ---- Chips

    @Test
    fun `the four chips send the reasons of 146, in the order of Figma`() {
        assertEquals(
            listOf("WORK_QUALITY", "OVERCHARGE", "BEHAVIOR", "OTHER"),
            Reason.entries.map { it.apiValue },
        )
    }

    // ---- Description

    @Test
    fun `description is 10 to 1000 characters once trimmed, without a phone or a link`() {
        assertEquals(DescriptionError.EMPTY, DisputeRules.checkDescription("   "))
        assertEquals(DescriptionError.TOO_SHORT, DisputeRules.checkDescription("  ngắn quá  "))
        assertEquals(DescriptionError.TOO_SHORT, DisputeRules.checkDescription("  " + "a".repeat(9) + "  "))
        assertNull(DisputeRules.checkDescription("  " + "a".repeat(10) + "  ")) // exactly 10 once trimmed
        assertNull(DisputeRules.checkDescription("a".repeat(1000)))
        assertEquals(DescriptionError.TOO_LONG, DisputeRules.checkDescription("a".repeat(1001)))
        assertEquals(DescriptionError.CONTACT_INFO, DisputeRules.checkDescription("Gọi tôi 0912 345 678 nhé"))
        assertEquals(DescriptionError.CONTACT_INFO, DisputeRules.checkDescription("Xem ảnh ở www.example.com nhé"))
        // A price is not a phone number.
        assertNull(DisputeRules.checkDescription("Đòi thêm 1.500.000 đ không báo trước"))
    }

    // ---- "Khiếu nại" in CS-20

    @Test
    fun `menu shows from ACCEPTED to COMPLETED, never for the other statuses`() {
        val shown = OrderStatus.entries.filter { DisputeRules.canOpen(order(it), windowHours = 72) }
        assertEquals(
            setOf(
                OrderStatus.ACCEPTED, OrderStatus.ON_THE_WAY, OrderStatus.ARRIVED, OrderStatus.IN_PROGRESS,
                OrderStatus.COMPLETED_BY_PARTNER, OrderStatus.COMPLETED,
            ),
            shown.toSet(),
        )
        assertFalse(DisputeRules.canOpen(order(OrderStatus.DISPUTED), 72))
        assertFalse(DisputeRules.canOpen(order(OrderStatus.PENDING), 72))
        assertFalse(DisputeRules.canOpen(order(OrderStatus.AWAITING_PAYMENT), 72))
        assertFalse(DisputeRules.canOpen(order(OrderStatus.CANCELLED), 72))
    }

    @Test
    fun `after completion only within the window of app-config`() {
        val done = order(OrderStatus.COMPLETED, completedAt = "2026-11-17T10:00:00+07:00")
        assertTrue(DisputeRules.canOpen(done, 72, Instant.parse("2026-11-20T03:00:00Z"))) // exactly 72 h
        assertFalse(DisputeRules.canOpen(done, 72, Instant.parse("2026-11-20T03:00:01Z")))
        assertTrue(DisputeRules.canOpen(done, 24, Instant.parse("2026-11-18T02:59:00Z")))
    }

    @Test
    fun `without app-config a completed order hides it, a running one keeps it`() {
        assertFalse(DisputeRules.canOpen(order(OrderStatus.COMPLETED, "2026-11-20T10:00:00+07:00"), null, Instant.parse("2026-11-20T04:00:00Z")))
        assertTrue(DisputeRules.canOpen(order(OrderStatus.ARRIVED), null))
        assertTrue(DisputeRules.canOpen(order(OrderStatus.IN_PROGRESS), null))
        // Unreadable completedAt: the window cannot be checked.
        assertFalse(DisputeRules.canOpen(order(OrderStatus.COMPLETED, "hôm qua"), 72))
    }

    // ---- Banner and result line of CS-20

    @Test
    fun `banner line takes the newest open case with its support deadline`() {
        val list = listOf(
            dispute("DS3", Dispute.STATUS_IN_REVIEW, sla = "2026-11-19T07:00:00Z"),
            dispute("DS2", Dispute.STATUS_OPEN, sla = "2026-11-18T07:00:00Z"),
            dispute("DS1", Dispute.STATUS_REJECTED),
        )
        val open = DisputeRules.openCase(list)!!
        assertEquals("DS3", open.code)
        assertEquals(BannerLine("DS3", "14:00, T5${nbsp}19/11"), DisputeRules.bannerLine(open, weekdays, vn))
        assertEquals(BannerLine("DS9", null), DisputeRules.bannerLine(dispute("DS9", Dispute.STATUS_OPEN, sla = "?"), weekdays, vn))
        assertNull(DisputeRules.bannerLine(dispute(" ", Dispute.STATUS_OPEN), weekdays, vn))
        assertNull(DisputeRules.openCase(listOf(dispute("DS1", Dispute.STATUS_RESOLVED))))
    }

    @Test
    fun `result line takes the newest closed case, an empty note becomes null`() {
        val list = listOf(
            dispute("DS2", Dispute.STATUS_RESOLVED, note = "  Đối tác quay lại sửa miễn phí  "),
            dispute("DS1", Dispute.STATUS_REJECTED, note = "Không đủ bằng chứng"),
        )
        assertEquals(ResultLine("DS2", "Đối tác quay lại sửa miễn phí"), DisputeRules.resultLine(DisputeRules.closedCase(list)!!))
        assertEquals(ResultLine("DS5", null), DisputeRules.resultLine(dispute("DS5", Dispute.STATUS_REJECTED, note = "   ")))
        assertNull(DisputeRules.closedCase(listOf(dispute("DS3", Dispute.STATUS_OPEN))))
        assertNull(DisputeRules.closedCase(emptyList()))
    }

    @Test
    fun `result line skips cases the partner opened`() {
        // OD2611180015: the partner's UNPAID_CASH report (newest) was upheld; the customer's own case is older.
        val list = listOf(
            dispute("DS3", Dispute.STATUS_RESOLVED, note = "Khách xác nhận chưa trả.", by = "PARTNER"),
            dispute("DS1", Dispute.STATUS_REJECTED, note = "Không đủ bằng chứng"),
        )
        assertEquals("DS1", DisputeRules.closedCase(list)!!.code)
        assertNull(DisputeRules.closedCase(list.take(1)))
        // A case without raisedByType is not known to be the customer's.
        assertNull(DisputeRules.closedCase(listOf(Dispute(id = "x", code = "DS4", status = Dispute.STATUS_RESOLVED))))
    }
}

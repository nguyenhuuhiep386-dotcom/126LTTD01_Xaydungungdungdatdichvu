package vn.servio.customer.ui.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.customer.R
import vn.servio.customer.data.model.ExtraCharge
import vn.servio.customer.data.model.ExtraChargeStatus
import vn.servio.customer.data.model.OrderAssignment
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderPricing
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.TimelineEntry
import vn.servio.customer.ui.completion.ConfirmCompletionUi.Deadline
import vn.servio.customer.ui.completion.ConfirmCompletionUi.Failure
import vn.servio.customer.ui.completion.ConfirmCompletionUi.Photo
import vn.servio.customer.ui.order.OrderRoutes
import vn.servio.customer.ui.order.OrderTexts
import vn.servio.customer.ui.request.MoneyInput
import java.io.File
import java.time.ZoneOffset

class ConfirmCompletionUiTest {

    private fun charge(id: String, status: ExtraChargeStatus, amount: Long, at: String) =
        ExtraCharge(id = id, description = "Khoản $id", amount = amount, status = status, createdAt = at)

    private fun order(autoConfirmAt: String? = "2026-11-19T08:10:00Z") = OrderDetail(
        id = "o1",
        code = "OD2611180010",
        status = OrderStatus.COMPLETED_BY_PARTNER,
        completedByPartnerAt = "2026-11-18T08:10:00Z",
        autoConfirmAt = autoConfirmAt,
        pricing = OrderPricing(subTotal = 200_000, extraChargeTotal = 120_000, totalAmount = 320_000),
        assignments = listOf(OrderAssignment("a1", OrderStatus.COMPLETED_BY_PARTNER, isPrimary = true, beforeImages = listOf("/b1.jpg"), afterImages = listOf("/a1.jpg", "/a2.jpg"))),
        extraCharges = listOf(
            charge("B", ExtraChargeStatus.REJECTED, 50_000, "2026-11-18T07:38:00Z"),
            charge("A", ExtraChargeStatus.APPROVED, 120_000, "2026-11-18T07:36:00Z"),
            charge("C", ExtraChargeStatus.PENDING, 30_000, "2026-11-18T07:40:00Z"),
        ),
    )

    @Test
    fun `the deadline sentence has the time of autoConfirmAt, or none when it is missing`() {
        val at = ConfirmCompletionUi.deadline(order())
        assertEquals(Deadline.At("2026-11-19T08:10:00Z"), at)
        assertEquals("15:10, T5 19/11", OrderTexts.moment((at as Deadline.At).autoConfirmAt, listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN"), ZoneOffset.ofHours(7)))
        assertEquals(Deadline.Unknown, ConfirmCompletionUi.deadline(order(autoConfirmAt = null)))
        assertEquals(Deadline.Unknown, ConfirmCompletionUi.deadline(order(autoConfirmAt = "không đọc được")))
    }

    @Test
    fun `payment lists only the approved charges between the quote and the server total`() {
        val payment = ConfirmCompletionUi.payment(order())
        assertEquals(200_000L, payment.quoted)
        assertEquals(listOf("A"), payment.approved.map { it.id })
        assertEquals(320_000L, payment.total)
        assertEquals(emptyList<ExtraCharge>(), ConfirmCompletionUi.payment(order().copy(extraCharges = emptyList())).approved)
    }

    @Test
    fun `the button names the cash total with a no-break space before đ`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        fun template(name: String) = Regex("<string name=\"$name\">(.*?)</string>").find(strings)!!.groupValues[1] // &#160; decoded as aapt does
            .replace("&#160;", OrderTexts.NBSP.toString())
        val money = OrderTexts.keepTogether(template("quote_price").replace("%1\$s", MoneyInput.format(ConfirmCompletionUi.payment(order()).total)))
        assertEquals("Xác nhận hoàn thành và đã trả tiền mặt 320.000 đ", template("confirm_button").replace("%1\$s", money))
    }

    @Test
    fun `photos are every before then every after, without captions`() {
        assertEquals(listOf(Photo("/b1.jpg", true), Photo("/a1.jpg", false), Photo("/a2.jpg", false)), ConfirmCompletionUi.photos(order()))
        val none = order().copy(assignments = listOf(OrderAssignment("a1", isPrimary = true)))
        assertEquals(emptyList<Photo>(), ConfirmCompletionUi.photos(none))
    }

    @Test
    fun `the partner note is the latest completed by partner note of 64`() {
        val rows = listOf(
            TimelineEntry(OrderStatus.IN_PROGRESS, note = "bắt đầu", createdAt = "2026-11-18T07:36:00Z"),
            TimelineEntry(OrderStatus.COMPLETED_BY_PARTNER, note = "  Đã thay bộ phát mới  ", createdAt = "2026-11-18T08:10:00Z"),
        )
        assertEquals("Đã thay bộ phát mới", ConfirmCompletionUi.partnerNote(rows))
        assertNull(ConfirmCompletionUi.partnerNote(listOf(TimelineEntry(OrderStatus.COMPLETED_BY_PARTNER, note = " "))))
        assertNull(ConfirmCompletionUi.partnerNote(null))
    }

    @Test
    fun `each 71 error code has its outcome`() {
        assertEquals(Failure.CHANGED, ConfirmCompletionUi.failure("RESOURCE_VERSION_CONFLICT"))
        assertEquals(Failure.NOT_WAITING, ConfirmCompletionUi.failure("INVALID_STATUS_TRANSITION"))
        assertEquals(Failure.DISPUTED, ConfirmCompletionUi.failure("ORDER_DISPUTED"))
        assertEquals(Failure.OTHER, ConfirmCompletionUi.failure("NETWORK_ERROR"))
    }

    @Test
    fun `a CS-19 card opens CS-23 only while waiting for the confirmation`() {
        assertEquals(R.id.cs23, OrderRoutes.cardDestination(OrderStatus.COMPLETED_BY_PARTNER))
        OrderStatus.entries.filter { it != OrderStatus.COMPLETED_BY_PARTNER }.forEach {
            assertEquals(R.id.cs20, OrderRoutes.cardDestination(it))
        }
    }
}

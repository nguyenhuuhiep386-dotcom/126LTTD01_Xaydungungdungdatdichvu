package vn.servio.customer.ui.orderdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.servio.customer.data.model.ExtraCharge
import vn.servio.customer.data.model.ExtraChargeStatus
import vn.servio.customer.data.model.OrderAssignment
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderPricing
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.ui.order.OrderTexts
import vn.servio.customer.ui.orderdetail.OrderDetailUi.ChargeFailure
import vn.servio.customer.ui.orderdetail.OrderDetailUi.Note
import vn.servio.customer.ui.orderdetail.OrderDetailUi.StepKind
import vn.servio.customer.ui.request.MoneyInput
import java.io.File

/** CS-22 rules of [OrderDetailUi]: which charge opens, the price block, the counts of CS-20, the #74 errors. */
class ExtraChargeUiTest {

    private fun charge(id: String, at: String, amount: Long = 50_000, status: ExtraChargeStatus = ExtraChargeStatus.PENDING) =
        ExtraCharge(id = id, assignmentId = "a1", description = "Thay tụ quạt", amount = amount, status = status, createdAt = at)

    private val a = charge("A", "2026-11-18T08:00:00Z", amount = 120_000)
    private val b = charge("B", "2026-11-18T08:05:00Z")

    private fun order(status: OrderStatus, vararg charges: ExtraCharge, pricing: OrderPricing = OrderPricing(200_000, 0, 200_000)) = OrderDetail(
        id = "o1",
        code = "OD2611180010",
        status = status,
        createdAt = "2026-11-18T06:00:00Z",
        pricing = pricing,
        assignments = listOf(OrderAssignment("a1", status, isPrimary = true, startedAt = "2026-11-18T07:55:00Z")),
        extraCharges = charges.toList(),
    )

    @Test
    fun `pending charges are counted oldest first, answered ones are not`() {
        val approved = charge("C", "2026-11-18T07:59:00Z", status = ExtraChargeStatus.APPROVED)
        val rejected = charge("D", "2026-11-18T07:58:00Z", status = ExtraChargeStatus.REJECTED)
        val pending = OrderDetailUi.pendingCharges(order(OrderStatus.IN_PROGRESS, b, approved, a, rejected))
        assertEquals(listOf("A", "B"), pending.map { it.id })
        assertEquals(listOf("A"), OrderDetailUi.pendingCharges(order(OrderStatus.ARRIVED, a)).map { it.id })
    }

    @Test
    fun `outside arrived and in progress nothing is pending for the customer`() {
        listOf(OrderStatus.DISPUTED, OrderStatus.CANCELLED, OrderStatus.COMPLETED_BY_PARTNER, OrderStatus.ON_THE_WAY).forEach {
            assertTrue(OrderDetailUi.pendingCharges(order(it, a, b)).isEmpty())
            assertNull(OrderDetailUi.chargeToOpen(order(it, a, b), emptySet(), includeClosed = true))
        }
    }

    @Test
    fun `the oldest charge opens, a closed one is skipped unless the warning is tapped`() {
        val o = order(OrderStatus.IN_PROGRESS, b, a)
        assertEquals("A", OrderDetailUi.chargeToOpen(o, emptySet(), includeClosed = false)?.id)
        assertEquals("B", OrderDetailUi.chargeToOpen(o, setOf("a"), includeClosed = false)?.id) // ids without case
        assertNull(OrderDetailUi.chargeToOpen(o, setOf("A", "B"), includeClosed = false))
        assertEquals("A", OrderDetailUi.chargeToOpen(o, setOf("A", "B"), includeClosed = true)?.id)
    }

    @Test
    fun `a held charge stays shut even from the warning`() {
        val o = order(OrderStatus.IN_PROGRESS, a, b)
        assertEquals("B", OrderDetailUi.chargeToOpen(o, emptySet(), includeClosed = true, held = "A")?.id)
    }

    @Test
    fun `bill without approved extras hides the row and adds the charge to the total`() {
        val bill = OrderDetailUi.bill(order(OrderStatus.IN_PROGRESS, b), b)
        assertEquals(OrderDetailUi.Bill(quoted = 200_000, approvedExtras = null, charge = 50_000, newTotal = 250_000), bill)
    }

    @Test
    fun `bill with an approved extra shows it and starts from the server total`() {
        val o = order(OrderStatus.IN_PROGRESS, b, pricing = OrderPricing(subTotal = 200_000, extraChargeTotal = 120_000, totalAmount = 320_000))
        assertEquals(OrderDetailUi.Bill(quoted = 200_000, approvedExtras = 120_000, charge = 50_000, newTotal = 370_000), OrderDetailUi.bill(o, b))
    }

    @Test
    fun `the approve label names the amount with a no-break space before đ`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        fun template(name: String) = Regex("<string name=\"$name\">(.*?)</string>").find(strings)!!.groupValues[1]
        val money = OrderTexts.keepTogether(template("quote_price").replace("%1\$s", MoneyInput.format(a.amount)))
        val label = template("extra_charge_approve").replace("%1\$s", money)
        assertEquals("Duyệt 120.000${OrderTexts.NBSP}đ", label)
        assertEquals("+50.000${OrderTexts.NBSP}đ", template("extra_charge_plus").replace("%1\$s", OrderTexts.keepTogether(template("quote_price").replace("%1\$s", MoneyInput.format(b.amount)))))
    }

    @Test
    fun `the in progress step counts pending charges, arrived does not reach it`() {
        val inProgress = OrderDetailUi.timeline(order(OrderStatus.IN_PROGRESS, a, b)).steps.first { it.kind == StepKind.IN_PROGRESS }
        assertEquals(Note.At("2026-11-18T07:55:00Z", pendingCharges = 2), inProgress.note)

        val none = OrderDetailUi.timeline(order(OrderStatus.IN_PROGRESS, a.copy(status = ExtraChargeStatus.APPROVED))).steps.first { it.kind == StepKind.IN_PROGRESS }
        assertEquals(Note.At("2026-11-18T07:55:00Z", pendingCharges = 0), none.note)

        val arrived = OrderDetailUi.timeline(order(OrderStatus.ARRIVED, a)).steps
        assertEquals(Note.None, arrived.first { it.kind == StepKind.IN_PROGRESS }.note)
        assertEquals(1, OrderDetailUi.pendingCharges(order(OrderStatus.ARRIVED, a)).size) // the warning line still counts it
    }

    @Test
    fun `each 74 error code has its outcome`() {
        assertEquals(ChargeFailure.HANDLED, OrderDetailUi.chargeFailure("INVALID_STATUS_TRANSITION"))
        assertEquals(ChargeFailure.HANDLED, OrderDetailUi.chargeFailure("CONFLICT"))
        assertEquals(ChargeFailure.DISPUTED, OrderDetailUi.chargeFailure("ORDER_DISPUTED"))
        assertEquals(ChargeFailure.NOT_FOUND, OrderDetailUi.chargeFailure("NOT_FOUND"))
        assertEquals(ChargeFailure.OTHER, OrderDetailUi.chargeFailure("NETWORK_ERROR"))
        assertEquals(ChargeFailure.OTHER, OrderDetailUi.chargeFailure("UNKNOWN"))
    }
}

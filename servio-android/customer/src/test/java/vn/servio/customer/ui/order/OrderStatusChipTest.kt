package vn.servio.customer.ui.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.ui.order.OrderStatusChip.Look
import vn.servio.customer.ui.order.OrderStatusChip.Tone

class OrderStatusChipTest {

    @Test
    fun `orders that move on are yellow with their icon`() {
        assertEquals(Look(R.string.order_status_pending, Tone.ACTIVE, R.drawable.ic_hourglass), OrderStatusChip.look(OrderStatus.PENDING))
        assertEquals(Look(R.string.order_status_accepted, Tone.ACTIVE, R.drawable.ic_check), OrderStatusChip.look(OrderStatus.ACCEPTED))
        assertEquals(Look(R.string.order_status_on_the_way, Tone.ACTIVE, R.drawable.ic_navigation), OrderStatusChip.look(OrderStatus.ON_THE_WAY))
        assertEquals(Look(R.string.order_status_arrived, Tone.ACTIVE, R.drawable.ic_pin), OrderStatusChip.look(OrderStatus.ARRIVED))
        assertEquals(Look(R.string.order_status_in_progress, Tone.ACTIVE, R.drawable.ic_wrench), OrderStatusChip.look(OrderStatus.IN_PROGRESS))
        assertEquals(
            Look(R.string.order_status_completed_by_partner, Tone.ACTIVE, R.drawable.ic_check),
            OrderStatusChip.look(OrderStatus.COMPLETED_BY_PARTNER),
        )
    }

    @Test
    fun `finished orders are grey, cancelled without icon`() {
        assertEquals(Look(R.string.order_status_completed, Tone.CLOSED, R.drawable.ic_check), OrderStatusChip.look(OrderStatus.COMPLETED))
        assertEquals(Look(R.string.order_status_cancelled, Tone.CLOSED, null), OrderStatusChip.look(OrderStatus.CANCELLED))
    }

    @Test
    fun `a dispute is red with a flag`() {
        assertEquals(Look(R.string.order_status_disputed, Tone.ALERT, R.drawable.ic_flag), OrderStatusChip.look(OrderStatus.DISPUTED))
        assertEquals(vn.servio.core.R.color.servio_error, OrderStatusChip.textColor(Tone.ALERT))
    }

    @Test
    fun `an order waiting for payment is red with a warning, like Chua tra on CS-30`() {
        assertEquals(Look(R.string.order_status_unpaid, Tone.ALERT, R.drawable.ic_warning), OrderStatusChip.look(OrderStatus.AWAITING_PAYMENT))
        assertEquals(vn.servio.core.R.color.servio_error_container, OrderStatusChip.backgroundColor(Tone.ALERT))
    }

    @Test
    fun `statuses outside the course scope and unknown values read Dang xu ly`() {
        listOf(OrderStatus.REFUNDED, OrderStatus.SEARCHING, OrderStatus.UNKNOWN).forEach {
            val look = OrderStatusChip.look(it)
            assertEquals(R.string.order_status_processing, look.label)
            assertEquals(Tone.CLOSED, look.tone)
            assertNull(look.icon)
        }
    }
}

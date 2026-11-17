package vn.servio.partner.ui.order

import androidx.annotation.StringRes
import vn.servio.partner.R
import vn.servio.partner.data.model.OrderStatus

/** Partner-side wording of order statuses (PS-13, PS-14). */
object OrderTexts {
    @StringRes
    fun status(status: OrderStatus): Int = when (status) {
        OrderStatus.PENDING -> R.string.order_status_pending
        OrderStatus.ACCEPTED -> R.string.order_status_accepted
        OrderStatus.ON_THE_WAY -> R.string.order_status_on_the_way
        OrderStatus.ARRIVED -> R.string.order_status_arrived
        OrderStatus.IN_PROGRESS -> R.string.order_status_in_progress
        OrderStatus.COMPLETED_BY_PARTNER -> R.string.order_status_completed_by_partner
        OrderStatus.AWAITING_PAYMENT -> R.string.order_status_awaiting_payment
        OrderStatus.COMPLETED -> R.string.order_status_completed
        OrderStatus.CANCELLED -> R.string.order_status_cancelled
        OrderStatus.DISPUTED -> R.string.order_status_disputed
    }
}

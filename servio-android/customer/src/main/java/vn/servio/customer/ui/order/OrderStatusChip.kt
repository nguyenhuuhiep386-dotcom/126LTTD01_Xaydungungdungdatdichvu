package vn.servio.customer.ui.order

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderStatus

/**
 * Status chip of an order (CS-19 cards, CS-20). Same pill as the request chip ([R.drawable.bg_status_chip]), tinted:
 * pale yellow while the order moves on, grey once it is over or unclear, red for a dispute (the red of the cancel
 * button). Icons are the 2 px stroke set, drawn at 16 dp in the colour of the text.
 */
object OrderStatusChip {

    enum class Tone { ACTIVE, CLOSED, ALERT }

    data class Look(@StringRes val label: Int, val tone: Tone, @DrawableRes val icon: Int?)

    fun look(status: OrderStatus): Look = when (status) {
        OrderStatus.PENDING -> Look(R.string.order_status_pending, Tone.ACTIVE, R.drawable.ic_hourglass)
        OrderStatus.ACCEPTED -> Look(R.string.order_status_accepted, Tone.ACTIVE, R.drawable.ic_check)
        OrderStatus.ON_THE_WAY -> Look(R.string.order_status_on_the_way, Tone.ACTIVE, R.drawable.ic_navigation)
        OrderStatus.ARRIVED -> Look(R.string.order_status_arrived, Tone.ACTIVE, R.drawable.ic_pin)
        OrderStatus.IN_PROGRESS -> Look(R.string.order_status_in_progress, Tone.ACTIVE, R.drawable.ic_wrench)
        OrderStatus.COMPLETED_BY_PARTNER -> Look(R.string.order_status_completed_by_partner, Tone.ACTIVE, R.drawable.ic_check)
        OrderStatus.COMPLETED -> Look(R.string.order_status_completed, Tone.CLOSED, R.drawable.ic_check)
        OrderStatus.CANCELLED -> Look(R.string.order_status_cancelled, Tone.CLOSED, null)
        OrderStatus.DISPUTED -> Look(R.string.order_status_disputed, Tone.ALERT, R.drawable.ic_flag)
        // Not used in the course scope (AWAITING_PAYMENT without #177, REFUNDED, SEARCHING) or unknown to this app.
        OrderStatus.AWAITING_PAYMENT, OrderStatus.REFUNDED, OrderStatus.SEARCHING, OrderStatus.UNKNOWN ->
            Look(R.string.order_status_processing, Tone.CLOSED, null)
    }

    @ColorRes
    fun textColor(tone: Tone): Int = when (tone) {
        Tone.ACTIVE -> vn.servio.core.R.color.servio_text_accent
        Tone.CLOSED -> vn.servio.core.R.color.servio_text_secondary
        Tone.ALERT -> vn.servio.core.R.color.servio_error
    }

    @ColorRes
    fun backgroundColor(tone: Tone): Int = when (tone) {
        Tone.ACTIVE -> vn.servio.core.R.color.servio_primary_container
        Tone.CLOSED -> vn.servio.core.R.color.servio_surface_variant
        Tone.ALERT -> vn.servio.core.R.color.servio_error_container
    }

    fun bind(view: TextView, status: OrderStatus) {
        val look = look(status)
        val context = view.context
        val color = context.getColor(textColor(look.tone))
        view.setText(look.label)
        view.setBackgroundResource(R.drawable.bg_status_chip)
        view.backgroundTintList = ColorStateList.valueOf(context.getColor(backgroundColor(look.tone)))
        view.setTextColor(color)
        val icon = look.icon?.let { ContextCompat.getDrawable(context, it)?.mutate() }
        if (icon != null) {
            val size = (ICON_DP * context.resources.displayMetrics.density).toInt()
            icon.setBounds(0, 0, size, size)
        }
        TextViewCompat.setCompoundDrawablesRelative(view, icon, null, null, null)
        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(color))
    }

    private const val ICON_DP = 16
}

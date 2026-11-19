package vn.servio.customer.ui.common

import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.widget.TextViewCompat
import vn.servio.customer.R
import vn.servio.customer.data.model.ServiceRequestStatus

/**
 * Status chip of a service request, in the style of the order chips (Figma only draws OPEN):
 * a request still in progress is pale yellow with an accent icon (clock while waiting, tick once matched);
 * a closed one (cancelled, expired, rejected) is neutral like the CS-16 skill chips, secondary text, no icon.
 * CS-15 uses it now; CS-14 will use it when its cards are redone.
 */
object RequestStatusChip {

    enum class Tone { ACTIVE, CLOSED }

    enum class Icon { CLOCK, CHECK, NONE }

    data class Look(@StringRes val label: Int, val tone: Tone, val icon: Icon)

    /** [openLabel] lets CS-15 say "Đang nhận báo giá" where a list says "Đang mở". */
    fun look(status: ServiceRequestStatus, @StringRes openLabel: Int = R.string.requests_status_open): Look = when (status) {
        ServiceRequestStatus.OPEN -> Look(openLabel, Tone.ACTIVE, Icon.CLOCK)
        ServiceRequestStatus.DRAFT -> Look(R.string.requests_status_draft, Tone.ACTIVE, Icon.CLOCK)
        ServiceRequestStatus.MATCHED -> Look(R.string.requests_status_matched, Tone.ACTIVE, Icon.CHECK)
        ServiceRequestStatus.CANCELLED -> Look(R.string.requests_status_cancelled, Tone.CLOSED, Icon.NONE)
        ServiceRequestStatus.EXPIRED -> Look(R.string.requests_status_expired, Tone.CLOSED, Icon.NONE)
        ServiceRequestStatus.REJECTED_BY_MODERATION -> Look(R.string.requests_status_rejected, Tone.CLOSED, Icon.NONE)
    }

    fun bind(view: TextView, status: ServiceRequestStatus, @StringRes openLabel: Int = R.string.requests_status_open) {
        val look = look(status, openLabel)
        view.setText(look.label)
        view.setBackgroundResource(
            if (look.tone == Tone.ACTIVE) R.drawable.bg_status_chip else R.drawable.bg_skill_chip,
        )
        view.setTextColor(
            view.context.getColor(
                if (look.tone == Tone.ACTIVE) vn.servio.core.R.color.servio_text_accent else vn.servio.core.R.color.servio_text_secondary,
            ),
        )
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(view, iconRes(look.icon), 0, 0, 0)
    }

    @DrawableRes
    private fun iconRes(icon: Icon) = when (icon) {
        Icon.CLOCK -> R.drawable.ic_clock_accent
        Icon.CHECK -> R.drawable.ic_check_accent
        Icon.NONE -> 0
    }
}

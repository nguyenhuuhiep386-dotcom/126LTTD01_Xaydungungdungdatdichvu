package vn.servio.partner.ui.quote

import android.content.Context
import androidx.annotation.StringRes
import vn.servio.partner.R
import vn.servio.partner.data.model.PartnerQuote
import vn.servio.partner.data.model.QuoteStatus
import vn.servio.partner.ui.common.Texts

/** Labels of a quote on PS-10 and PS-12. */
object QuoteTexts {
    /** Default commission rate shown in the PS-11 estimate (SystemConfigs commission.default_rate). */
    const val COMMISSION_PERCENT = 15

    /** quote.max_pending_per_partner */
    const val MAX_PENDING = 5

    @StringRes
    fun status(status: QuoteStatus): Int = when (status) {
        QuoteStatus.PENDING -> R.string.quote_status_pending
        QuoteStatus.ACCEPTED -> R.string.quote_status_accepted
        QuoteStatus.REJECTED -> R.string.quote_status_rejected
        QuoteStatus.WITHDRAWN -> R.string.quote_status_withdrawn
        QuoteStatus.EXPIRED -> R.string.quote_status_expired
    }

    /** "Có mặt 14:00, T7 17/10 · 60 phút" */
    fun arrival(context: Context, quote: PartnerQuote): String {
        val start = Texts.parse(quote.availableFrom) ?: return ""
        return context.getString(
            R.string.quote_arrival,
            Texts.time(start),
            Texts.dayLabel(context, start),
            quote.estimatedDurationMinutes,
        )
    }

    fun commissionOf(amount: Long): Long = amount * COMMISSION_PERCENT / 100
}

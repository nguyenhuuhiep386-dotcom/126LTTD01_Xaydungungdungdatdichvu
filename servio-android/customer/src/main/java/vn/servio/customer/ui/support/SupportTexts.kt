package vn.servio.customer.ui.support

import androidx.annotation.StringRes
import vn.servio.customer.R
import vn.servio.customer.data.model.ServerConfig
import vn.servio.customer.data.model.SupportContact
import vn.servio.customer.ui.confirmquote.PolicyTexts

/** CS-34 content as plain data, so it can be unit tested; [SupportFragment] turns it into text. */
object SupportTexts {

    /** A figure in an answer: a fee (formatted by OrderFormat.money), a count of hours (%d) or ready text (%s). */
    sealed interface Arg {
        data class Money(val amount: Long) : Arg
        data class Count(val value: Int) : Arg
        data class Text(val value: String) : Arg
    }

    data class Faq(@StringRes val question: Int, @StringRes val answer: Int, val args: List<Arg> = emptyList())

    /** The four questions of the Figma, in its order; every figure from #107. */
    fun faqs(config: ServerConfig): List<Faq> = listOf(
        Faq(
            R.string.support_faq_fee_question,
            R.string.support_faq_fee_answer,
            listOf(
                Arg.Money(config.cancellationFeeAfterOnTheWay),
                Arg.Money(config.cancellationFeeAfterArrivedMin),
                Arg.Text(PolicyTexts.percent(config.cancellationFeeAfterArrivedPercent)),
            ),
        ),
        Faq(R.string.support_faq_payment_question, R.string.support_faq_payment_answer),
        Faq(R.string.support_faq_auto_question, R.string.support_faq_auto_answer, listOf(Arg.Count(config.autoConfirmHours))),
        Faq(R.string.support_faq_dispute_question, R.string.support_faq_dispute_answer, listOf(Arg.Count(config.disputeWindowHours))),
    )

    /**
     * The hotline card: [hotline] null hides the number and "Gọi hotline", [hours] null hides its line; null — no card
     * — when both are missing. Blank counts as missing.
     */
    fun contact(support: SupportContact): SupportContact? {
        val hotline = support.hotline?.trim()?.takeIf { it.isNotEmpty() }
        val hours = support.hours?.trim()?.takeIf { it.isNotEmpty() }
        return if (hotline == null && hours == null) null else SupportContact(hotline, hours)
    }

    /** What ACTION_DIAL gets: the hotline without spaces or dots ("1900 1234" → "19001234"). */
    fun dialNumber(hotline: String): String = hotline.filter { it.isDigit() || it == '+' }
}

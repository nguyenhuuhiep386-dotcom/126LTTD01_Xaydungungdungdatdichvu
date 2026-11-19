package vn.servio.customer.ui.order

import android.content.Context
import vn.servio.customer.ui.requestdetail.QuoteFormat

/** Strings of the order screens that need a Context. */
object OrderFormat {

    /** "20.000 đ" from QuoteFormat, with a no-break space so the amount and "đ" never split across lines. */
    fun money(context: Context, amount: Long): String = OrderTexts.keepTogether(QuoteFormat.money(context, amount))
}

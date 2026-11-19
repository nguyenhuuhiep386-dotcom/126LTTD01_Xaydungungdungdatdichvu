package vn.servio.customer.ui.confirmquote

import java.math.BigDecimal

/** CS-18 policy figures of #107 that need more than a plain format, kept here so they can be unit tested. */
object PolicyTexts {
    /** Percent without a useless decimal part: 20.0 → "20", 12.5 → "12,5" (Vietnamese decimal comma). */
    fun percent(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',')
}

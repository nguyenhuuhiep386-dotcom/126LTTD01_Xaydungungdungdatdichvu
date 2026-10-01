package vn.servio.core.utils

import java.text.NumberFormat
import java.util.Locale

/** VND amounts are whole numbers (spec 0 ý 5). Display: 450.000 đ */
object Money {
    private val vietnamese = Locale.forLanguageTag("vi-VN")

    fun format(amount: Long): String = NumberFormat.getIntegerInstance(vietnamese).format(amount) + " đ"

    /** "200.000 đ – 400.000 đ", "≤ 300.000 đ", "≥ 150.000 đ", or null when both ends are missing. */
    fun range(min: Long?, max: Long?): String? = when {
        min != null && max != null -> "${format(min)} – ${format(max)}"
        max != null -> "≤ ${format(max)}"
        min != null -> "≥ ${format(min)}"
        else -> null
    }
}

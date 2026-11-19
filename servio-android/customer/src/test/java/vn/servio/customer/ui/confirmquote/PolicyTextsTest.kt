package vn.servio.customer.ui.confirmquote

import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyTextsTest {

    @Test
    fun aWholePercentHasNoDecimals() {
        // #107 sends the C# decimal 20 as 20 or 20.0.
        assertEquals("20", PolicyTexts.percent(20.0))
        assertEquals("100", PolicyTexts.percent(100.0))
        assertEquals("0", PolicyTexts.percent(0.0))
    }

    @Test
    fun aFractionUsesTheVietnameseComma() {
        assertEquals("12,5", PolicyTexts.percent(12.5))
        assertEquals("7,25", PolicyTexts.percent(7.25))
    }
}

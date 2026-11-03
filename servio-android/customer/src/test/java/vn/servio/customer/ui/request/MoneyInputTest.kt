package vn.servio.customer.ui.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.customer.ui.request.MoneyInput.Edit

class MoneyInputTest {

    @Test
    fun formatGroupsThousandsWithDots() {
        assertEquals("0", MoneyInput.format(0))
        assertEquals("999", MoneyInput.format(999))
        assertEquals("300.000", MoneyInput.format(300_000))
        assertEquals("1.500.000", MoneyInput.format(1_500_000))
        assertEquals("999.999.999", MoneyInput.format(999_999_999))
    }

    @Test
    fun parseKeepsDigitsOnly() {
        assertEquals(300_000L, MoneyInput.parse("300.000"))
        assertEquals(300_000L, MoneyInput.parse("300.000 đ"))
        assertEquals(0L, MoneyInput.parse("0"))
        assertNull(MoneyInput.parse(""))
        assertNull(MoneyInput.parse("đ."))
    }

    @Test
    fun typingGroupsDigits() {
        // "30.000|" + "0"
        assertEquals(Edit("300.000", 7), MoneyInput.reformat("30.000", "30.0000", 7))
    }

    @Test
    fun insertingMidNumberKeepsCursorAfterTypedDigit() {
        // "3|00.000" + "5" → "3.5|00.000"
        assertEquals(Edit("3.500.000", 3), MoneyInput.reformat("300.000", "3500.000", 2))
    }

    @Test
    fun backspaceOverDotRemovesDigitInFront() {
        // "300.|000", backspace takes the dot: the "0" in front of it goes instead → "30|.000"
        assertEquals(Edit("30.000", 2), MoneyInput.reformat("300.000", "300000", 3))
    }

    @Test
    fun overNineDigitsIsRejected() {
        assertNull(MoneyInput.reformat("999.999.999", "9999.999.999", 4))
        assertNull(MoneyInput.reformat("", "1234567890", 10))
    }

    @Test
    fun nineDigitsIsAccepted() {
        assertEquals(Edit("123.456.789", 11), MoneyInput.reformat("12.345.678", "12.345.6789", 11))
    }

    @Test
    fun leadingZerosAreDropped() {
        assertEquals(Edit("5", 1), MoneyInput.reformat("0", "05", 2))
        assertEquals(Edit("300", 3), MoneyInput.reformat("", "000300", 6))
        // A lone zero stays: "Từ 0 đ" is a valid budget.
        assertEquals(Edit("0", 1), MoneyInput.reformat("", "0", 1))
        assertEquals(Edit("0", 1), MoneyInput.reformat("0", "00", 2))
    }

    @Test
    fun pasteKeepsDigitsOnly() {
        assertEquals(Edit("1.200.000", 9), MoneyInput.reformat("", "abc 1.200.000 VND", 17))
        assertEquals(Edit("500.000", 7), MoneyInput.reformat("", "500,000đ", 8))
    }

    @Test
    fun clearingTheBoxGivesEmpty() {
        assertEquals(Edit("", 0), MoneyInput.reformat("5", "", 0))
    }
}

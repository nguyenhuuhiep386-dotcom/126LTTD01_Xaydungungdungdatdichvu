package vn.servio.customer.ui.request

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactInfoGuardTest {

    // Same cases as servio-backend MarketplaceTests.ContentGuard_DetectsPhonesAndLinks_ButNotPrices.
    @Test
    fun serverCases() {
        assertGuard("Gọi em 0901234567 nhé", true)
        assertGuard("Liên hệ +84 90 123 4567", true)
        assertGuard("Xem zalo.me/abc hoặc www.servio.vn", true)
        assertGuard("Ngân sách 1.500.000 đồng, máy 1.5HP", false)
        assertGuard("Lắp ở tầng 3, phòng 302, TP.HCM", false)
    }

    // .NET reads \d, \s and \b as Unicode classes; the client must catch the same text.
    @Test
    fun unicodeClassesMatchDotNet() {
        assertGuard("Gọi 0901 234 567", true) // NBSP separators (\s)
        assertGuard("Gọi 0９０１２３４５６７", true) // full-width digits after the literal "0" (\d)
        assertGuard("Gọi ０９０１２３４５６７", false) // the prefix "0" / "84" is ASCII on the server too
        assertGuard("Số 0901-234-567 nhé", true)
        assertGuard("Mã đơn 12345678901234", false) // too many digits, not a phone number
        assertGuard("Xem ZALO.ME/abc", true) // IgnoreCase
        assertGuard("đzalo.me", false) // "đ" is a word character in .NET, so there is no \b before "z"
        assertGuard("Máy lạnh chảy nước, kém lạnh", false)
    }

    private fun assertGuard(text: String, expected: Boolean) =
        assertEquals(text, expected, ContactInfoGuard.containsContactInfo(text))
}

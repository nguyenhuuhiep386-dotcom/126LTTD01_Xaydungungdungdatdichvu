package vn.servio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.servio.core.data.model.ConsentRecord
import vn.servio.core.data.repository.LegalConsents

class LegalConsentsTest {

    private fun terms(granted: Boolean, current: Boolean) =
        ConsentRecord("TERMS_AND_PRIVACY", granted, if (current) "2026-11-15" else "2026-10-01", current)

    @Test
    fun `asked when the terms were never agreed`() {
        assertTrue(LegalConsents.needsTermsConsent(emptyList()))
        // A partner's other consents do not count.
        assertTrue(LegalConsents.needsTermsConsent(listOf(ConsentRecord("KYC_IDENTITY", true, "2026-11-15", true))))
    }

    @Test
    fun `asked again after a withdrawal or a new policy version`() {
        assertTrue(LegalConsents.needsTermsConsent(listOf(terms(granted = false, current = true))))
        assertTrue(LegalConsents.needsTermsConsent(listOf(terms(granted = true, current = false))))
    }

    @Test
    fun `not asked once agreed on the current version`() {
        assertFalse(LegalConsents.needsTermsConsent(listOf(terms(granted = true, current = true))))
        assertFalse(
            LegalConsents.needsTermsConsent(
                listOf(ConsentRecord("LOCATION_SHARING", false, "2026-11-15", true), terms(granted = true, current = true)),
            ),
        )
    }

    @Test
    fun `page addresses have exactly one slash after the base url`() {
        val base = "http://10.0.2.2:5080/"
        assertEquals("http://10.0.2.2:5080/chinh-sach-bao-mat", LegalConsents.pageUrl("/chinh-sach-bao-mat", base))
        assertEquals("http://10.0.2.2:5080/dieu-khoan-su-dung", LegalConsents.pageUrl("dieu-khoan-su-dung", base))
        assertEquals("http://10.0.2.2:5080/dieu-khoan-su-dung", LegalConsents.pageUrl("/dieu-khoan-su-dung", "http://10.0.2.2:5080"))
        assertEquals("https://servio.vn/legal", LegalConsents.pageUrl("https://servio.vn/legal", base))
        assertNull(LegalConsents.pageUrl("  ", base))
    }
}

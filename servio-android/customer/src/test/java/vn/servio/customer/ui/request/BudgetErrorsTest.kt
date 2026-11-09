package vn.servio.customer.ui.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.servio.core.data.model.Category
import vn.servio.customer.ui.request.CreateRequestViewModel.Companion.budgetErrors
import vn.servio.customer.ui.request.CreateRequestViewModel.FieldError

class BudgetErrorsTest {

    private fun range(min: Long?, max: Long?) = RequestDraft(quoteByPartner = false, budgetMin = min, budgetMax = max)

    @Test
    fun quoteByPartnerHasNoErrorEvenWithBadBoxes() {
        assertEquals(emptySet<FieldError>(), budgetErrors(RequestDraft()))
        val draft = RequestDraft(quoteByPartner = true, budgetMin = 500_000, budgetMax = 100_000)
        assertEquals(emptySet<FieldError>(), budgetErrors(draft))
        assertNull(draft.budgetMinToSend)
        assertNull(draft.budgetMaxToSend)
    }

    @Test
    fun missingMin() {
        assertEquals(setOf(FieldError.BUDGET_MIN_MISSING), budgetErrors(range(null, 500_000)))
    }

    @Test
    fun missingMax() {
        assertEquals(setOf(FieldError.BUDGET_MAX_MISSING), budgetErrors(range(300_000, null)))
    }

    @Test
    fun missingBoth() {
        assertEquals(setOf(FieldError.BUDGET_MIN_MISSING, FieldError.BUDGET_MAX_MISSING), budgetErrors(range(null, null)))
    }

    @Test
    fun minAboveMax() {
        assertEquals(setOf(FieldError.BUDGET_RANGE), budgetErrors(range(500_000, 300_000)))
    }

    @Test
    fun minEqualToMaxIsValid() {
        val draft = range(300_000, 300_000)
        assertEquals(emptySet<FieldError>(), budgetErrors(draft))
        assertEquals(300_000L, draft.budgetMinToSend)
        assertEquals(300_000L, draft.budgetMaxToSend)
    }

    @Test
    fun zeroMinIsValid() {
        assertEquals(emptySet<FieldError>(), budgetErrors(range(0, 200_000)))
    }

    // region certificateState / referenceRange (no seed category covers these on the device)

    @Test
    fun requiredCertificateIsTickedAndLocked() {
        assertEquals(CertificateState(checked = true, locked = true), certificateState(categoryRequires = true, userChoice = false))
        assertEquals(CertificateState(checked = true, locked = true), certificateState(categoryRequires = true, userChoice = true))
    }

    @Test
    fun optionalCertificateFollowsCustomer() {
        assertEquals(CertificateState(checked = false, locked = false), certificateState(categoryRequires = false, userChoice = false))
        assertEquals(CertificateState(checked = true, locked = false), certificateState(categoryRequires = false, userChoice = true))
    }

    private fun service(min: Long?, max: Long?) =
        Category(id = "c1", name = "Sửa máy lạnh", slug = "sua-may-lanh", referencePriceMin = min, referencePriceMax = max)

    @Test
    fun referenceRangeNeedsBothEnds() {
        assertEquals(PriceRange(200_000, 1_500_000), referenceRange(service(200_000, 1_500_000)))
        assertNull(referenceRange(service(null, 1_500_000)))
        assertNull(referenceRange(service(200_000, null)))
        assertNull(referenceRange(service(null, null)))
    }

    @Test
    fun defaultDraftQuotesByPartner() {
        val draft = RequestDraft()
        assertTrue(draft.quoteByPartner)
        assertFalse(draft.hasBudget)
        assertEquals(RequestDraft.DEFAULT_RADIUS_KM, draft.searchRadiusKm)
    }

    // endregion
}

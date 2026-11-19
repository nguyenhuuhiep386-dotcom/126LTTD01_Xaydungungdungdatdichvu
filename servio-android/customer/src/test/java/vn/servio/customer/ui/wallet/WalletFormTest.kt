package vn.servio.customer.ui.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.servio.customer.data.model.Debt
import vn.servio.customer.ui.wallet.WalletForm.EvidenceStatus
import vn.servio.customer.ui.wallet.WalletForm.Status
import java.time.ZoneId

class WalletFormTest {

    private val owed = Debt("d1", "CANCELLATION_FEE", 20_000, "Phí huỷ đơn", "OD2611180012", null, "2026-11-17T22:49:58Z")
    private val declared = owed.copy(id = "d2", pendingTransactionCode = "TX2611190001")

    @Test
    fun `status is owes, waiting or clear`() {
        assertEquals(Status.OWES, WalletForm.status(listOf(owed)))
        assertEquals(Status.OWES, WalletForm.status(listOf(owed, declared)))
        assertEquals(Status.WAITING, WalletForm.status(listOf(declared)))
        assertEquals(Status.CLEAR, WalletForm.status(emptyList()))
        assertEquals(listOf(owed), WalletForm.undeclared(listOf(owed, declared)))
    }

    @Test
    fun `send needs an undeclared fee, a 3 to 100 character reference, an uploaded photo and no send running`() {
        val debts = listOf(owed)
        assertTrue(WalletForm.canSubmit(debts, "FT2611190001", EvidenceStatus.UPLOADED, sending = false))
        assertFalse(WalletForm.canSubmit(debts, "FT2611190001", EvidenceStatus.UPLOADED, sending = true))
        assertFalse(WalletForm.canSubmit(listOf(declared), "FT2611190001", EvidenceStatus.UPLOADED, sending = false))
        assertFalse(WalletForm.canSubmit(emptyList(), "FT2611190001", EvidenceStatus.UPLOADED, sending = false))
        assertFalse(WalletForm.canSubmit(debts, "FT2611190001", EvidenceStatus.UPLOADING, sending = false))
        assertFalse(WalletForm.canSubmit(debts, "FT2611190001", EvidenceStatus.FAILED, sending = false))
        assertFalse(WalletForm.canSubmit(debts, "FT2611190001", null, sending = false))
        assertFalse(WalletForm.canSubmit(debts, " ab ", EvidenceStatus.UPLOADED, sending = false))
        assertTrue(WalletForm.canSubmit(debts, " abc ", EvidenceStatus.UPLOADED, sending = false))
        assertTrue(WalletForm.canSubmit(debts, "x".repeat(100), EvidenceStatus.UPLOADED, sending = false))
        assertFalse(WalletForm.canSubmit(debts, "x".repeat(101), EvidenceStatus.UPLOADED, sending = false))
    }

    @Test
    fun `the reference error shows only once something is typed`() {
        assertFalse(WalletForm.referenceError(""))
        assertFalse(WalletForm.referenceError("   "))
        assertTrue(WalletForm.referenceError("ab"))
        assertFalse(WalletForm.referenceError("abc"))
    }

    @Test
    fun `server codes`() {
        assertEquals(WalletForm.Failure.NOTHING_TO_PAY, WalletForm.failure("NOTHING_TO_PAY"))
        assertEquals(WalletForm.Failure.VALIDATION, WalletForm.failure("VALIDATION_ERROR"))
        assertEquals(WalletForm.Failure.OTHER, WalletForm.failure("NETWORK_ERROR"))
        assertTrue(WalletForm.isEvidenceField("evidenceUrl"))
        assertFalse(WalletForm.isEvidenceField("bankTransferReference"))
        assertFalse(WalletForm.isEvidenceField(null))
    }

    @Test
    fun `day of createdAt in the phone zone`() {
        val vietnam = ZoneId.of("Asia/Ho_Chi_Minh")
        assertEquals("18/11", WalletForm.day("2026-11-17T22:49:58.0732861+00:00", vietnam))
        assertEquals("17/11", WalletForm.day("2026-11-17T10:00:00Z", vietnam))
        assertNull(WalletForm.day("", vietnam))
    }
}

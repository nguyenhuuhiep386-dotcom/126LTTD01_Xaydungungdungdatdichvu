package vn.servio.customer.ui.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.customer.data.model.BankAccount

class TransferCardTest {

    private val bank = BankAccount("Vietcombank", "0123456789", "CONG TY SERVIO", "SERVIO {phone}")

    @Test
    fun `the phone number is written as people type it in a bank app`() {
        assertEquals("0912345678", TransferCard.localPhone("+84912345678"))
        assertEquals("0912345678", TransferCard.localPhone("+84 912 345 678"))
        assertEquals("0912345678", TransferCard.localPhone("0912 345 678"))
        assertNull(TransferCard.localPhone("  "))
    }

    @Test
    fun `phone goes in place of the placeholder`() {
        assertEquals("SERVIO 0912345678", TransferCard.note("SERVIO {phone}", "+84912345678"))
        assertEquals("PHI HUY 0912345678 SERVIO", TransferCard.note("PHI HUY {phone} SERVIO", "+84 912 345 678"))
        // A template without the placeholder is used as it is, even without a phone number.
        assertEquals("SERVIO PHI HUY", TransferCard.note("SERVIO PHI HUY", null))
    }

    @Test
    fun `no note without a template, or without the phone it needs`() {
        assertNull(TransferCard.note(null, "+84912345678"))
        assertNull(TransferCard.note(" ", "+84912345678"))
        assertNull(TransferCard.note("SERVIO {phone}", null))
        assertNull(TransferCard.note("SERVIO {phone}", ""))
    }

    @Test
    fun `the card needs the bank, the account number and the holder`() {
        assertEquals(TransferCard("Vietcombank", "0123456789", "CONG TY SERVIO", "SERVIO 0912345678"), TransferCard.of(bank, "+84912345678"))
        assertNull(TransferCard.of(null, "+84912345678"))
        assertNull(TransferCard.of(bank.copy(bankName = null), "+84912345678"))
        assertNull(TransferCard.of(bank.copy(accountNumber = " "), "+84912345678"))
        assertNull(TransferCard.of(bank.copy(accountHolder = ""), "+84912345678"))
        // Without the phone the card stays, its note row is hidden.
        assertNull(TransferCard.of(bank, null)!!.note)
    }
}

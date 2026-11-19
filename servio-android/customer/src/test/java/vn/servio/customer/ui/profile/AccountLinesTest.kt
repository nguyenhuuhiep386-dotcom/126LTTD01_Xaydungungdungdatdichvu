package vn.servio.customer.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.servio.customer.data.model.Wallet
import vn.servio.customer.ui.profile.AccountLines.Fee
import vn.servio.customer.ui.profile.AccountLines.Notifications

class AccountLinesTest {

    @Test
    fun `fee line follows 87`() {
        assertEquals(Fee.Unavailable, AccountLines.fee(null))
        assertEquals(Fee.Outstanding(20_000), AccountLines.fee(Wallet(debtBalance = 20_000, maxDebt = 100_000)))
        assertEquals(Fee.None, AccountLines.fee(Wallet(debtBalance = 0, pendingPayment = 0, maxDebt = 100_000)))
    }

    @Test
    fun `a declared fee is pending, not to pay, because debtBalance still counts it`() {
        // #87 after #156 for the only fee: debtBalance 20 000 of which 20 000 waits for the admin.
        assertEquals(Fee.Pending(20_000), AccountLines.fee(Wallet(debtBalance = 20_000, pendingPayment = 20_000)))
        // Two fees, one declared: only the other one is still to pay.
        assertEquals(Fee.Outstanding(30_000), AccountLines.fee(Wallet(debtBalance = 50_000, pendingPayment = 20_000)))
    }

    @Test
    fun `notification line follows the total of 104`() {
        assertEquals(Notifications.Unavailable, AccountLines.notifications(null))
        assertEquals(Notifications.Unread(26), AccountLines.notifications(26))
        assertEquals(Notifications.None, AccountLines.notifications(0))
    }
}

package vn.servio.customer.ui.profile

import vn.servio.customer.data.model.Wallet

/** The second line of the CS-29 rows that show a counter, decided without Android so it can be unit tested. */
object AccountLines {

    sealed interface Fee {
        /** #87 failed: "–". */
        data object Unavailable : Fee

        /** "Còn X đ cần thanh toán". */
        data class Outstanding(val amount: Long) : Fee

        /** Nothing left to declare, but a declared transfer waits for the admin: "Đang chờ xác nhận X đ". */
        data class Pending(val amount: Long) : Fee

        /** "Không có khoản cần thanh toán". */
        data object None : Fee
    }

    sealed interface Notifications {
        /** #104 failed: "–". */
        data object Unavailable : Notifications

        /** "N chưa đọc". */
        data class Unread(val count: Int) : Notifications

        /** "Không có thông báo mới". */
        data object None : Notifications
    }

    /**
     * The server's debtBalance counts every DUE fee, the declared ones too (pendingPayment is part of it), so what is
     * still to pay is the difference; only when nothing is left to declare does the pending part show.
     */
    fun fee(wallet: Wallet?): Fee {
        if (wallet == null) return Fee.Unavailable
        val toPay = wallet.debtBalance - wallet.pendingPayment
        return when {
            toPay > 0 -> Fee.Outstanding(toPay)
            wallet.pendingPayment > 0 -> Fee.Pending(wallet.pendingPayment)
            else -> Fee.None
        }
    }

    fun notifications(unread: Int?): Notifications = when {
        unread == null -> Notifications.Unavailable
        unread > 0 -> Notifications.Unread(unread)
        else -> Notifications.None
    }
}

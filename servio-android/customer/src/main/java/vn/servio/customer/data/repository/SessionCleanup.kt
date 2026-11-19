package vn.servio.customer.data.repository

import vn.servio.core.realtime.Realtime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the customer app keeps for the signed-in account outside the screens, dropped on sign-out so the next account
 * starts clean:
 *
 * - The three SignalR connections ([Realtime.stopAll]). An open connection keeps the identity of the token it was
 *   opened with, even after logout, so without this the next account would get the previous one's events. The event
 *   names registered by [OrdersHub] stay in HubClient (only the per-connection attachments are reset), so the next
 *   start() by a screen of the new account opens a fresh connection with the new token and still gets every event.
 * - The "Thợ báo đã đến" requests kept by [OrderUpdates].
 *
 * Nothing else needs it: [OrdersHub] and [QuoteUpdates] keep no data (their flows do not replay), the ViewModels are
 * cleared with the back stack by navigateToLogin, and the "Tin nhắn" badge is removed by MainActivity once the auth
 * screens are shown. Safe to call more than once and before anything was started.
 */
interface SessionCleanup {
    fun signedOut()
}

@Singleton
class CustomerSessionCleanup @Inject constructor(
    private val realtime: Realtime,
    private val orderUpdates: OrderUpdates,
) : SessionCleanup {
    override fun signedOut() {
        realtime.stopAll()
        orderUpdates.clearSession()
    }
}

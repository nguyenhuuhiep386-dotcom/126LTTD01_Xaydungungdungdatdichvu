package vn.servio.partner

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import vn.servio.core.realtime.Realtime
import vn.servio.core.ui.MainHostActivity
import vn.servio.partner.data.model.QuoteAcceptedEvent
import vn.servio.partner.ui.order.OrderDetailFragment
import javax.inject.Inject

/** ServioPartner. Tabs: PS-07 Newsfeed, PS-12 Báo giá, PS-13 Đơn hàng, PS-20 Tin nhắn, PS-21 Thu nhập. */
@AndroidEntryPoint
class MainActivity : MainHostActivity() {
    override val navGraphRes = R.navigation.nav_root
    override val bottomMenuRes = R.menu.bottom_nav
    override val topLevelDestinations = setOf(R.id.ps07, R.id.ps12, R.id.ps13, R.id.ps20, R.id.ps21)

    @Inject lateinit var realtime: Realtime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Once in the main tabs, keep /hubs/orders open: a picked quote must reach the partner on any tab.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id in topLevelDestinations && tokenStore.hasSession) realtime.orders.start()
        }
        // F-ORD-01: the partner has 10 minutes to confirm, so say it right away instead of waiting for a tab change.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                realtime.orders.listen(EVENT_QUOTE_ACCEPTED, QuoteAcceptedEvent.serializer()).collect { event ->
                    val orderId = event.orderId ?: return@collect
                    if (navController.currentDestination?.id == R.id.ps14) return@collect
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.quote_accepted_title)
                        .setMessage(R.string.quote_accepted_message)
                        .setNegativeButton(R.string.action_close, null)
                        .setPositiveButton(R.string.quote_accepted_open) { _, _ ->
                            navController.navigate(R.id.ps14, bundleOf(OrderDetailFragment.ARG_ORDER_ID to orderId))
                        }
                        .show()
                }
            }
        }
    }

    private companion object {
        const val EVENT_QUOTE_ACCEPTED = "QuoteAccepted"
    }
}

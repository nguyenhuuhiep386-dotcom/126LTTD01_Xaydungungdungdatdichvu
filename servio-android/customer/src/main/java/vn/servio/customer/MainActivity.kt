package vn.servio.customer

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import vn.servio.core.data.repository.ChatRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.ui.MainHostActivity
import vn.servio.customer.data.repository.SessionCleanup
import javax.inject.Inject

/** Servio (customer). Tabs: CS-06 Trang chủ, CS-14 Yêu cầu, CS-19 Đơn hàng, CS-27 Tin nhắn, CS-29 Tài khoản. */
@AndroidEntryPoint
class MainActivity : MainHostActivity() {
    override val navGraphRes = R.navigation.nav_root
    override val bottomMenuRes = R.menu.bottom_nav
    override val topLevelDestinations = setOf(R.id.cs06, R.id.cs14, R.id.cs19, R.id.cs27, R.id.cs29)

    @Inject lateinit var chat: ChatRepository

    @Inject lateinit var sessionCleanup: SessionCleanup

    /** True while a screen of the main graph is shown; going from there to the auth screens is a sign-out. */
    private var inMainGraph = false

    /** One pending "count again" is enough: several triggers in a row give one call (#61). */
    private val unreadRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Live chat events, only while signed in (the hub needs the token). */
    private var liveJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bottomNav = findViewById<BottomNavigationView>(vn.servio.core.R.id.bottomNav)

        // Badge on "Tin nhắn": #61 when the app comes to the foreground, on every screen change and on each
        // incoming message or hub reconnect. 0 hides it; a failed call keeps the number already shown.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                unreadRefresh.tryEmit(Unit)
                unreadRefresh.collectLatest {
                    if (!signedIn()) {
                        liveJob?.cancel()
                        liveJob = null
                        bottomNav.removeBadge(R.id.cs27)
                        return@collectLatest
                    }
                    listenLive()
                    when (val result = chat.unreadCount()) {
                        is ApiResult.Success -> showUnread(bottomNav, result.data)
                        is ApiResult.Failure -> Unit
                    }
                }
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            // Logout (CS-32 also does it) or an expired session (core sends every screen back to login): close the
            // hubs and drop the account's data so the next account starts clean. Repeating it is harmless. The consent
            // screen lives in auth_graph but is also opened over CS-11 (CONSENT_REQUIRED): still signed in there.
            val inAuthFlow = isAuthFlow(destination)
            if (inAuthFlow && inMainGraph) sessionCleanup.signedOut()
            inMainGraph = !inAuthFlow
            unreadRefresh.tryEmit(Unit)
        }
    }

    private fun signedIn(): Boolean =
        tokenStore.hasSession && navController.currentDestination?.let(::isAuthFlow) != true

    /** A login screen. The consent screen is one too, except when opened over the app's screens. */
    private fun isAuthFlow(destination: NavDestination): Boolean =
        destination.parent?.id == vn.servio.core.R.id.auth_graph &&
            !(destination.id == vn.servio.core.R.id.consentFragment && inMainGraph)

    private fun listenLive() {
        if (liveJob?.isActive == true) return
        liveJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                merge(chat.incoming().map { }, chat.reconnected()).collect { unreadRefresh.tryEmit(Unit) }
            }
        }
    }

    private fun showUnread(bottomNav: BottomNavigationView, count: Int) {
        if (count <= 0) {
            bottomNav.removeBadge(R.id.cs27)
            return
        }
        bottomNav.getOrCreateBadge(R.id.cs27).apply {
            number = count
            backgroundColor = getColor(vn.servio.core.R.color.servio_error)
            badgeTextColor = getColor(android.R.color.white)
            isVisible = true
        }
    }
}

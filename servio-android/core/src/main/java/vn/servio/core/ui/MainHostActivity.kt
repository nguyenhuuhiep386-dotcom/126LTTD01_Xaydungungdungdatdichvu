package vn.servio.core.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.MenuRes
import androidx.annotation.NavigationRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import kotlinx.coroutines.launch
import vn.servio.core.R
import vn.servio.core.data.local.TokenStore
import vn.servio.core.databinding.ActivityMainHostBinding
import vn.servio.core.ui.auth.navigateToLogin
import vn.servio.core.utils.SessionEvents
import javax.inject.Inject

/**
 * Single activity shared by both apps. Each app subclasses it (annotated with @AndroidEntryPoint)
 * and provides its navigation graph, bottom-nav menu and the ids of the 5 tab destinations.
 */
abstract class MainHostActivity : AppCompatActivity() {

    @Inject lateinit var sessionEvents: SessionEvents
    @Inject lateinit var tokenStore: TokenStore

    @get:NavigationRes protected abstract val navGraphRes: Int
    @get:MenuRes protected abstract val bottomMenuRes: Int

    /** Destinations that show the bottom navigation. Menu item ids must equal these destination ids. */
    protected abstract val topLevelDestinations: Set<Int>

    protected lateinit var navController: NavController
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val binding = ActivityMainHostBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        // The root already pads for the navigation bar; stop BottomNavigationView adding it a second time.
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { _, insets -> insets }

        val navHost = binding.navHost.getFragment<NavHostFragment>()
        navController = navHost.navController
        // Always set the graph: after process death the NavController restores its back stack onto it.
        navController.setGraph(navGraphRes)

        binding.bottomNav.inflateMenu(bottomMenuRes)
        binding.bottomNav.setupWithNavController(navController)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.isVisible = destination.id in topLevelDestinations
        }

        // Refresh token rejected (TokenAuthenticator): go back to login from anywhere.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionEvents.expired.collect { navController.navigateToLogin() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The session may have expired while the app was in the background (the event above is not replayed).
        val inAuthFlow = navController.currentDestination?.parent?.id == R.id.auth_graph
        if (!tokenStore.hasSession && !inAuthFlow) {
            navController.navigateToLogin()
        }
    }
}

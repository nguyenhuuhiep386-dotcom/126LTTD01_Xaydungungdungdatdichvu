package vn.servio.core.ui.auth

import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import vn.servio.core.R

/** Clears the whole back stack and opens the login flow (used after logout or when the session expires). */
fun NavController.navigateToLogin() {
    navigate(R.id.auth_graph, null, navOptions { popUpTo(graph.id) { inclusive = true } })
}

/** Leaves the auth flow: opens [destination] and removes the auth screens from the back stack. */
internal fun Fragment.navigateAfterLogin(@IdRes destination: Int) {
    findNavController().navigate(
        destination,
        null,
        navOptions { popUpTo(R.id.auth_graph) { inclusive = true } },
    )
}

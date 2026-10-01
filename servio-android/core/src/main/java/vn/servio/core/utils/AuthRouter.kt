package vn.servio.core.utils

import androidx.annotation.IdRes
import vn.servio.core.data.model.Me

/**
 * Tells the shared auth screens where to go after login. Each app binds its own implementation:
 * - Customer: CS-05 when the name is missing, otherwise the main graph.
 * - Partner: signup/KYC screens until the profile is APPROVED, otherwise the main graph.
 * Return a destination id from the app's nav_root.xml (or R.id.main_graph / R.id.completeProfileFragment from :core).
 */
fun interface AuthRouter {
    @IdRes
    fun destinationAfterLogin(user: Me): Int
}

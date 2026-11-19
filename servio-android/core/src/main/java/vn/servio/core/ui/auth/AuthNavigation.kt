package vn.servio.core.ui.auth

import androidx.annotation.IdRes
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import vn.servio.core.R
import vn.servio.core.data.model.Me
import vn.servio.core.utils.AuthRouter

/** Clears the whole back stack and opens the login flow (used after logout or when the session expires). */
fun NavController.navigateToLogin() {
    navigate(R.id.auth_graph, null, navOptions { popUpTo(graph.id) { inclusive = true } })
}

/** A finished login (Splash with a session, OTP, CS-05) and whether the terms of the current version are agreed. */
data class SignedIn(val user: Me, val needsConsent: Boolean)

/**
 * The one exit of the shared auth flow: the consent screen first when the terms and privacy policy of the current
 * version are not agreed (Luật 91/2025, before any profile data such as the name of CS-05), then the app's
 * [AuthRouter]. The auth screens behind it are dropped: back on the consent screen closes the app.
 */
internal fun Fragment.continueAfterLogin(signedIn: SignedIn, authRouter: AuthRouter) {
    if (signedIn.needsConsent) {
        findNavController().navigate(
            R.id.consentFragment,
            null,
            navOptions { popUpTo(R.id.auth_graph) { inclusive = false } },
        )
    } else {
        navigateAfterLogin(authRouter.destinationAfterLogin(signedIn.user))
    }
}

/** Leaves the auth flow: opens [destination] and removes the auth screens from the back stack. */
internal fun Fragment.navigateAfterLogin(@IdRes destination: Int) {
    findNavController().navigate(
        destination,
        null,
        navOptions { popUpTo(R.id.auth_graph) { inclusive = true } },
    )
}

/**
 * Opens the consent screen over the current screen (a call answered 422 CONSENT_REQUIRED, e.g. posting a request).
 * Once agreed it goes back; the caller reads [LegalConsentFragment.RESULT_GRANTED] from its own saved state:
 * `findNavController().currentBackStackEntry?.savedStateHandle?.getStateFlow(RESULT_GRANTED, false)`.
 */
fun Fragment.openLegalConsentForResult() {
    // By deep link: an id of auth_graph cannot be found from a screen of the app's main_graph.
    findNavController().navigate(LEGAL_CONSENT_RETURN_URI.toUri())
}

private const val LEGAL_CONSENT_RETURN_URI = "servio-internal://legal-consent?returnToCaller=true"

package vn.servio.core.ui.auth

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.core.view.isInvisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.R
import vn.servio.core.databinding.FragmentLegalConsentBinding
import vn.servio.core.ui.auth.LegalConsentViewModel.Event
import vn.servio.core.ui.auth.LegalConsentViewModel.Page
import vn.servio.core.utils.AuthRouter
import vn.servio.core.utils.collectWhenStarted
import javax.inject.Inject

/**
 * Terms and privacy consent (Luật 91/2025, Điều 9: explicit, never implied), shared by both apps.
 *
 * Two modes ([ARG_RETURN_TO_CALLER]):
 * - After login (Splash, OTP, CS-05 via continueAfterLogin): once agreed, the app's AuthRouter decides where to go.
 *   Back closes the app; "Đăng xuất" goes back to CS-03.
 * - Return: opened by a screen whose call answered 422 CONSENT_REQUIRED. Once agreed it sets [RESULT_GRANTED] on that
 *   screen's saved state and goes back to it; back simply returns without agreeing.
 */
@AndroidEntryPoint
class LegalConsentFragment : Fragment(R.layout.fragment_legal_consent) {

    @Inject lateinit var authRouter: AuthRouter

    private val viewModel: LegalConsentViewModel by viewModels()

    private var binding: FragmentLegalConsentBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentLegalConsentBinding.bind(view).also { binding = it }
        val returnToCaller = arguments?.getBoolean(ARG_RETURN_TO_CALLER) ?: false
        if (!returnToCaller) {
            // Nothing behind it but the finished login: leaving without agreeing closes the app (asked again next time).
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { requireActivity().finish() }
        }

        binding.agreeCheck.setOnCheckedChangeListener { _, checked -> viewModel.setChecked(checked) }
        binding.agreeButton.setOnClickListener { viewModel.agree() }
        binding.termsLink.setOnClickListener { viewModel.openPage(Page.TERMS) }
        binding.privacyLink.setOnClickListener { viewModel.openPage(Page.PRIVACY) }
        binding.logoutButton.setOnClickListener { viewModel.logout() }

        collectWhenStarted(viewModel.state) { state ->
            if (binding.agreeCheck.isChecked != state.checked) binding.agreeCheck.isChecked = state.checked
            binding.agreeButton.isEnabled = state.canAgree
            binding.agreeCheck.isEnabled = !state.isLoading
            binding.logoutButton.isEnabled = !state.isLoading
            binding.progress.isInvisible = !state.isLoading
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is Event.Message -> showMessage(event.text ?: event.res?.let(::getString) ?: return@collectWhenStarted)
                is Event.OpenPage -> open(event.url)
                is Event.Agreed -> navigateAfterLogin(authRouter.destinationAfterLogin(event.user))
                Event.Returned -> {
                    val navController = findNavController()
                    navController.previousBackStackEntry?.savedStateHandle?.set(RESULT_GRANTED, true)
                    navController.popBackStack()
                }
                Event.LoggedOut -> findNavController().navigateToLogin()
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.legal_link_unavailable))
        }
    }

    private fun showMessage(text: String) {
        val root = binding?.root ?: return
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        /** Navigation argument: true when opened by [openLegalConsentForResult]. */
        const val ARG_RETURN_TO_CALLER = "returnToCaller"

        /** Saved-state key set to true on the opening screen once the user agreed (return mode). */
        const val RESULT_GRANTED = "legalConsentGranted"
    }
}

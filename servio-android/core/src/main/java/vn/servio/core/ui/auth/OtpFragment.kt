package vn.servio.core.ui.auth

import android.os.Bundle
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.BuildConfig
import vn.servio.core.R
import vn.servio.core.databinding.FragmentOtpBinding
import vn.servio.core.utils.AuthRouter
import vn.servio.core.utils.collectWhenStarted
import javax.inject.Inject

/** CS-04: enter the 6-digit OTP (#2). Verifies automatically when 6 digits are typed. */
@AndroidEntryPoint
class OtpFragment : Fragment(R.layout.fragment_otp) {

    @Inject lateinit var authRouter: AuthRouter

    private val viewModel: OtpViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentOtpBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.subtitle.text = getString(R.string.otp_subtitle, requireArguments().getString("maskedPhone"))
        binding.demoHint.isVisible = BuildConfig.DEBUG

        val code = { binding.codeInput.text?.toString().orEmpty() }
        // Registered after the view state is restored, so a code restored on rotation is not verified twice.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.withStarted {
                binding.codeInput.doAfterTextChanged {
                    binding.codeLayout.error = null
                    binding.verifyButton.isEnabled = code().length == 6
                    if (code().length == 6) viewModel.verify(code())
                }
            }
        }
        if (savedInstanceState == null) {
            binding.codeInput.requestFocus()
            WindowCompat.getInsetsController(requireActivity().window, binding.codeInput).show(WindowInsetsCompat.Type.ime())
        }
        binding.verifyButton.setOnClickListener { viewModel.verify(code()) }
        binding.resendButton.setOnClickListener { viewModel.resend() }

        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isInvisible = !state.isLoading
            binding.verifyButton.isEnabled = !state.isLoading && code().length == 6
            binding.codeLayout.error = state.error
            binding.resendButton.isEnabled = state.resendInSeconds == 0 && !state.isLoading
            binding.resendButton.text = if (state.resendInSeconds > 0) {
                getString(R.string.otp_resend_in, state.resendInSeconds)
            } else {
                getString(R.string.otp_resend)
            }
        }
        collectWhenStarted(viewModel.loggedIn) { user ->
            navigateAfterLogin(authRouter.destinationAfterLogin(user))
        }
    }
}

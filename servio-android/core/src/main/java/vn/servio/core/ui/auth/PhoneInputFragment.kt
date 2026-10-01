package vn.servio.core.ui.auth

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.R
import vn.servio.core.databinding.FragmentPhoneInputBinding
import vn.servio.core.utils.collectWhenStarted

/** CS-03: enter the phone number and request an OTP (#1). */
@AndroidEntryPoint
class PhoneInputFragment : Fragment(R.layout.fragment_phone_input) {

    private val viewModel: PhoneInputViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentPhoneInputBinding.bind(view)
        val submit = { viewModel.submit(binding.phoneInput.text?.toString().orEmpty()) }

        binding.continueButton.setOnClickListener { submit() }
        binding.phoneInput.doAfterTextChanged { binding.phoneLayout.error = null }
        binding.phoneInput.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE).also { if (it) submit() }
        }

        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isInvisible = !state.isLoading
            binding.continueButton.isEnabled = !state.isLoading
            binding.phoneLayout.error = when (state.error) {
                null -> null
                PhoneInputViewModel.INVALID_PHONE -> getString(R.string.phone_invalid)
                else -> state.error
            }
        }
        collectWhenStarted(viewModel.events) { event ->
            findNavController().navigate(
                R.id.action_phoneInput_to_otp,
                bundleOf(
                    "otpId" to event.result.otpId,
                    "phoneNumber" to event.phoneNumber,
                    "maskedPhone" to event.result.maskedPhone,
                    "resendAfterSeconds" to event.result.resendAfterSeconds,
                ),
            )
        }
    }
}

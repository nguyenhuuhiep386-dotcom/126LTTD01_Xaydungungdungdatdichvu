package vn.servio.partner.ui.signup

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.image.CameraCaptureFiles
import vn.servio.partner.databinding.FragmentSignupInfoBinding
import vn.servio.partner.ui.common.LoadStatus
import vn.servio.partner.ui.common.PhotoPicker
import vn.servio.partner.ui.common.showLocalPhoto
import vn.servio.partner.ui.signup.SignupInfoViewModel.Event
import vn.servio.partner.ui.signup.SignupInfoViewModel.FieldError
import javax.inject.Inject

/** PS-02 Đăng ký đối tác – Bước 1/4 Thông tin cá nhân. First screen after login for a new partner. */
@AndroidEntryPoint
class SignupInfoFragment : Fragment(R.layout.fragment_signup_info) {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var captureFiles: CameraCaptureFiles

    private val viewModel: SignupInfoViewModel by viewModels()
    private val photoPicker = PhotoPicker(this, { captureFiles }) { uri -> viewModel.onAvatarPicked(uri) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSignupInfoBinding.bind(view)
        // First signup screen: there is nothing to go back to (the auth screens were popped).
        setupSignupToolbar(binding.toolbar, authRepository, showBack = false)
        SignupSteps.bind(binding.steps, 1, R.string.signup_step_info)

        binding.avatarButton.setOnClickListener { photoPicker.show() }
        binding.yearsMinus.setOnClickListener { viewModel.changeYears(-1) }
        binding.yearsPlus.setOnClickListener { viewModel.changeYears(+1) }
        binding.actionBar.primaryButton.setOnClickListener {
            viewModel.save(
                fullName = binding.nameInput.text?.toString().orEmpty(),
                bio = binding.bioInput.text?.toString().orEmpty(),
            )
        }

        collectWhenStarted(viewModel.state) { state ->
            LoadStatus.bind(binding.status, state, binding.form) { viewModel.load() }
            binding.actionBar.root.isVisible = state is UiState.Content
            if (state is UiState.Content && !viewModel.isFormFilled) {
                viewModel.isFormFilled = true
                binding.nameInput.setText(state.data.fullName)
                binding.bioInput.setText(state.data.bio.orEmpty())
            }
        }
        collectWhenStarted(viewModel.avatar) { avatar ->
            binding.avatarProgress.isVisible = avatar.uploading
            val hasPhoto = avatar.localUri != null || avatar.remoteUrl != null
            binding.avatarCamera.isVisible = !avatar.uploading && avatar.localUri == null
            binding.avatarCamera.setImageResource(if (hasPhoto) R.drawable.ic_check else R.drawable.ic_camera)
            avatar.localUri?.let { showLocalPhoto(binding.avatarImage, it) }
        }
        collectWhenStarted(viewModel.years) { years ->
            binding.yearsValue.text = getString(R.string.signup_years_value, years)
        }
        collectWhenStarted(viewModel.fieldErrors) { errors ->
            binding.nameLayout.error = if (FieldError.NAME_INVALID in errors) getString(R.string.signup_name_invalid) else null
        }
        collectWhenStarted(viewModel.saving) { saving ->
            binding.actionBar.primaryButton.isEnabled = !saving
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                Event.Saved -> openSignupStep(R.id.ps03)
                is Event.Error -> Snackbar.make(view, event.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }
}

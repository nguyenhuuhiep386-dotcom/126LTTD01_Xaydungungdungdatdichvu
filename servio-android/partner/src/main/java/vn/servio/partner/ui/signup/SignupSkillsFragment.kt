package vn.servio.partner.ui.signup

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.image.CameraCaptureFiles
import vn.servio.partner.databinding.FragmentSignupSkillsBinding
import vn.servio.partner.ui.common.LoadStatus
import vn.servio.partner.ui.common.PhotoPicker
import vn.servio.partner.ui.signup.SkillPickerViewModel.Event
import javax.inject.Inject

/**
 * PS-03 Đăng ký – Bước 2/4 Kỹ năng. With the nav argument `manage = true` it is PS-24 Quản lý kỹ năng
 * (reached from PS-23): same list, no step bar, "Lưu" goes back.
 */
@AndroidEntryPoint
class SignupSkillsFragment : Fragment(R.layout.fragment_signup_skills) {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var captureFiles: CameraCaptureFiles

    private val viewModel: SkillPickerViewModel by viewModels()
    private val photoPicker = PhotoPicker(this, { captureFiles }) { uri -> viewModel.onCertificatePicked(uri) }

    private val manage: Boolean get() = arguments?.getBoolean(ARG_MANAGE) == true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSignupSkillsBinding.bind(view)
        if (manage) {
            binding.toolbar.setTitle(R.string.skills_manage_title)
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
            binding.toolbar.setNavigationContentDescription(R.string.action_back)
            binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
            binding.steps.root.isVisible = false
            binding.actionBar.primaryButton.setText(R.string.action_save)
        } else {
            setupSignupToolbar(binding.toolbar, authRepository)
            SignupSteps.bind(binding.steps, 2, R.string.signup_step_skills)
        }

        val adapter = SkillAdapter(viewModel::toggleGroup, viewModel::toggleService)
        binding.list.adapter = adapter
        binding.certificateRow.setOnClickListener { photoPicker.show() }
        binding.actionBar.primaryButton.setOnClickListener { viewModel.save(requireAtLeastOne = !manage) }

        collectWhenStarted(viewModel.state) { state ->
            LoadStatus.bind(binding.status, state, binding.form) { viewModel.load() }
            binding.actionBar.root.isVisible = state is UiState.Content
            if (state is UiState.Content) {
                adapter.submitList(state.data.rows)
                binding.selectedCount.text =
                    getString(R.string.skills_selected_count, state.data.selectedIds.size, SkillPickerViewModel.MAX_SKILLS)
            }
        }
        collectWhenStarted(viewModel.certificate) { certificate ->
            binding.certificateProgress.isVisible = certificate.uploading
            binding.certificateIcon.setImageResource(if (certificate.url != null) R.drawable.ic_check else R.drawable.ic_image)
            binding.certificateText.setText(
                when {
                    certificate.uploading -> R.string.skills_certificate_uploading
                    certificate.url != null -> R.string.skills_certificate_uploaded
                    else -> R.string.skills_certificate_upload
                },
            )
        }
        collectWhenStarted(viewModel.saving) { saving -> binding.actionBar.primaryButton.isEnabled = !saving }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                Event.Saved -> if (manage) findNavController().navigateUp() else openSignupStep(R.id.ps04)
                Event.NoSkill -> snack(view, getString(R.string.skills_none))
                is Event.LimitReached -> snack(view, getString(R.string.skills_limit, event.max))
                is Event.CertificateMissing -> snack(view, getString(R.string.skills_certificate_missing, event.serviceName))
                is Event.Message -> snack(view, event.text)
            }
        }
    }

    private fun snack(view: View, text: String) = Snackbar.make(view, text, Snackbar.LENGTH_LONG).show()

    companion object {
        const val ARG_MANAGE = "manage"
    }
}

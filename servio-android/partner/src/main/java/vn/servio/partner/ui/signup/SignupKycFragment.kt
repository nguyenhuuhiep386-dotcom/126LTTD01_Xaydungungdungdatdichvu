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
import vn.servio.partner.data.model.ApprovalStatus
import vn.servio.partner.data.model.DocumentType
import vn.servio.partner.data.repository.ConsentPurpose
import vn.servio.partner.data.repository.ConsentRepository
import vn.servio.partner.databinding.FragmentSignupKycBinding
import vn.servio.partner.databinding.ItemKycSlotBinding
import vn.servio.partner.ui.common.LoadStatus
import vn.servio.partner.ui.common.PhotoPicker
import vn.servio.partner.ui.common.showLocalPhoto
import vn.servio.partner.ui.common.withConsent
import vn.servio.partner.ui.signup.SignupKycViewModel.Event
import vn.servio.partner.ui.signup.SignupKycViewModel.Slot
import javax.inject.Inject

/** PS-05 Đăng ký – Bước 4/4 Xác minh danh tính. KYC photos are taken with the camera (gallery only without one). */
@AndroidEntryPoint
class SignupKycFragment : Fragment(R.layout.fragment_signup_kyc) {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var captureFiles: CameraCaptureFiles
    @Inject lateinit var consents: ConsentRepository

    private val viewModel: SignupKycViewModel by viewModels()
    private var pendingType: DocumentType? = null
    private val photoPicker = PhotoPicker(this, { captureFiles }) { uri ->
        pendingType?.let { viewModel.onPhotoPicked(it, uri) }
        pendingType = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingType = savedInstanceState?.getString(STATE_PENDING_TYPE)?.let(DocumentType::valueOf)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingType?.let { outState.putString(STATE_PENDING_TYPE, it.name) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSignupKycBinding.bind(view)
        setupSignupToolbar(binding.toolbar, authRepository)
        SignupSteps.bind(binding.steps, 4, R.string.signup_step_kyc)
        binding.actionBar.primaryButton.setText(R.string.kyc_submit)
        binding.actionBar.primaryButton.setOnClickListener {
            withConsent(consents, listOf(ConsentPurpose.TERMS_AND_PRIVACY, ConsentPurpose.KYC_IDENTITY)) { viewModel.submit() }
        }

        val views = mapOf(
            DocumentType.ID_FRONT to binding.idFront,
            DocumentType.ID_BACK to binding.idBack,
            DocumentType.SELFIE_WITH_ID to binding.selfie,
        )
        views.forEach { (type, slot) ->
            slot.title.setText(titleOf(type))
            slot.captureButton.setOnClickListener {
                // CCCD photos are sensitive data: the server needs the KYC_IDENTITY consent before the upload.
                withConsent(consents, listOf(ConsentPurpose.KYC_IDENTITY)) {
                    pendingType = type
                    photoPicker.show(cameraOnly = true)
                }
            }
        }

        collectWhenStarted(viewModel.state) { state ->
            LoadStatus.bind(binding.status, state, binding.form) { viewModel.load() }
            binding.actionBar.root.isVisible = state is UiState.Content
            if (state is UiState.Content) {
                state.data.forEach { slot -> views[slot.type]?.let { bindSlot(it, slot) } }
                binding.actionBar.primaryButton.isEnabled =
                    state.data.all { it.isDone } && state.data.none { it.uploading } && !viewModel.submitting.value
            }
        }
        collectWhenStarted(viewModel.submitting) { submitting ->
            val slots = (viewModel.state.value as? UiState.Content)?.data.orEmpty()
            binding.actionBar.primaryButton.isEnabled = !submitting && slots.isNotEmpty() && slots.all { it.isDone }
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                Event.Submitted -> leaveSignup(R.id.ps06)
                is Event.Error -> Snackbar.make(view, event.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun bindSlot(view: ItemKycSlotBinding, slot: Slot) {
        view.progress.isVisible = slot.uploading
        view.placeholderIcon.isVisible = slot.localUri == null && !slot.uploading
        view.placeholderIcon.setImageResource(if (slot.isDone) R.drawable.ic_check else R.drawable.ic_camera)
        if (slot.localUri != null) showLocalPhoto(view.thumbnail, slot.localUri) else view.thumbnail.setImageDrawable(null)
        view.state.text = when {
            slot.uploading -> getString(R.string.kyc_uploading)
            slot.status == ApprovalStatus.REJECTED ->
                getString(R.string.kyc_rejected, slot.rejectReason ?: getString(R.string.kyc_rejected_no_reason))
            slot.status == ApprovalStatus.APPROVED -> getString(R.string.status_approved)
            slot.isDone -> getString(R.string.kyc_uploaded)
            else -> getString(R.string.kyc_not_taken)
        }
        view.captureButton.setText(if (slot.status == null) R.string.kyc_take else R.string.kyc_retake)
        // Approved documents cannot be replaced (VERIFICATION_LOCKED on the server).
        view.captureButton.isVisible = slot.status != ApprovalStatus.APPROVED
        view.captureButton.isEnabled = !slot.uploading
    }

    private fun titleOf(type: DocumentType) = when (type) {
        DocumentType.ID_FRONT -> R.string.kyc_id_front
        DocumentType.ID_BACK -> R.string.kyc_id_back
        else -> R.string.kyc_selfie
    }

    private companion object {
        const val STATE_PENDING_TYPE = "pendingType"
    }
}

package vn.servio.partner.ui.work

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.image.CameraCaptureFiles
import vn.servio.partner.data.model.CreateExtraChargeRequest
import vn.servio.partner.data.repository.FileRepository
import vn.servio.partner.data.repository.OrderRepository
import vn.servio.partner.databinding.SheetExtraChargeBinding
import vn.servio.partner.ui.common.PhotoPicker
import vn.servio.partner.ui.common.PhotoUploads
import vn.servio.partner.ui.common.bindPhotoStrip
import vn.servio.partner.ui.order.OrderDetailFragment
import javax.inject.Inject

@HiltViewModel
class ExtraChargeViewModel @Inject constructor(
    private val repository: OrderRepository,
    fileRepository: FileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val orderId: String = checkNotNull(savedStateHandle[OrderDetailFragment.ARG_ORDER_ID])
    private val assignmentId: String = checkNotNull(savedStateHandle[ExtraChargeSheet.ARG_ASSIGNMENT_ID])

    enum class FieldError { DESCRIPTION, AMOUNT }

    val evidence = PhotoUploads(viewModelScope, fileRepository, max = 1)

    private val _fieldErrors = MutableStateFlow<Set<FieldError>>(emptySet())
    val fieldErrors: StateFlow<Set<FieldError>> = _fieldErrors.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** null = sent, otherwise an error message. */
    private val _results = Channel<String?>(Channel.BUFFERED)
    val results: Flow<String?> = _results.receiveAsFlow()

    fun submit(description: String, amountText: String, explanation: String) {
        val amount = amountText.filter(Char::isDigit).toLongOrNull()
        val text = listOf(description.trim(), explanation.trim()).filter { it.isNotEmpty() }.joinToString(". ")
        val errors = buildSet {
            if (description.trim().length < MIN_DESCRIPTION) add(FieldError.DESCRIPTION)
            if (amount == null || amount !in AMOUNT_RANGE) add(FieldError.AMOUNT)
        }
        _fieldErrors.value = errors
        if (errors.isNotEmpty() || amount == null || _sending.value || evidence.isUploading) return
        _sending.value = true
        viewModelScope.launch {
            val request = CreateExtraChargeRequest(assignmentId, text.take(MAX_DESCRIPTION), amount, evidence.urls.firstOrNull())
            val result = repository.addExtraCharge(orderId, request)
            _sending.value = false
            _results.send((result as? ApiResult.Failure)?.message)
        }
    }

    private companion object {
        const val MIN_DESCRIPTION = 3
        const val MAX_DESCRIPTION = 300
        val AMOUNT_RANGE = 1_000L..100_000_000L
    }
}

/** PS-17 Thêm chi phí phát sinh (#73). The customer approves or rejects it on CS-22; the result comes live. */
@AndroidEntryPoint
class ExtraChargeSheet : BottomSheetDialogFragment() {

    @Inject lateinit var captureFiles: CameraCaptureFiles

    private val viewModel: ExtraChargeViewModel by viewModels()
    private val photoPicker = PhotoPicker(this, { captureFiles }) { uri -> viewModel.evidence.add(uri) }
    private var binding: SheetExtraChargeBinding? = null

    override fun getTheme() = R.style.ThemeOverlay_Servio_BottomSheetDialog

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetExtraChargeBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        binding.closeButton.setOnClickListener { dismiss() }
        binding.sendButton.setOnClickListener {
            viewModel.submit(
                binding.descriptionInput.text?.toString().orEmpty(),
                binding.amountInput.text?.toString().orEmpty(),
                binding.explanationInput.text?.toString().orEmpty(),
            )
        }
        collectWhenStarted(viewModel.evidence.photos) { photos ->
            bindPhotoStrip(binding.evidenceStrip, photos, viewModel.evidence.max, onAdd = { photoPicker.show() },
                onRemove = viewModel.evidence::remove)
        }
        collectWhenStarted(viewModel.fieldErrors) { errors ->
            binding.descriptionLayout.error =
                if (ExtraChargeViewModel.FieldError.DESCRIPTION in errors) getString(R.string.extra_description_invalid) else null
            binding.amountLayout.error =
                if (ExtraChargeViewModel.FieldError.AMOUNT in errors) getString(R.string.extra_amount_invalid) else null
        }
        collectWhenStarted(viewModel.sending) { sending ->
            binding.sendButton.isEnabled = !sending
            binding.progress.isVisible = sending
        }
        collectWhenStarted(viewModel.results) { error ->
            if (error == null) {
                setFragmentResult(RESULT_KEY, bundleOf())
                dismiss()
            } else {
                Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_ASSIGNMENT_ID = "assignmentId"
        const val RESULT_KEY = "extra_charge_sent"
    }
}

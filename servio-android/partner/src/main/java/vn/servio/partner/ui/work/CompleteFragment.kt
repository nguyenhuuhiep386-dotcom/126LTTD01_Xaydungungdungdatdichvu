package vn.servio.partner.ui.work

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.Money
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.core.utils.toUiState
import vn.servio.partner.R
import vn.servio.partner.data.image.CameraCaptureFiles
import vn.servio.partner.data.model.ExtraChargeStatus
import vn.servio.partner.data.model.OrderDetail
import vn.servio.partner.data.repository.FileRepository
import vn.servio.partner.data.repository.OrderRepository
import vn.servio.partner.databinding.FragmentCompleteBinding
import vn.servio.partner.databinding.ItemStatusRowBinding
import vn.servio.partner.ui.common.LoadStatus
import vn.servio.partner.ui.common.PhotoPicker
import vn.servio.partner.ui.common.PhotoUploads
import vn.servio.partner.ui.common.bindPhotoStrip
import vn.servio.partner.ui.order.OrderDetailFragment
import javax.inject.Inject

@HiltViewModel
class CompleteViewModel @Inject constructor(
    private val repository: OrderRepository,
    fileRepository: FileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val orderId: String = checkNotNull(savedStateHandle[OrderDetailFragment.ARG_ORDER_ID])

    val afterPhotos = PhotoUploads(viewModelScope, fileRepository)

    private val _state = MutableStateFlow<UiState<OrderDetail>>(UiState.Loading)
    val state: StateFlow<UiState<OrderDetail>> = _state.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** null = done, otherwise an error message. */
    private val _results = Channel<String?>(Channel.BUFFERED)
    val results: Flow<String?> = _results.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch { _state.value = repository.get(orderId).toUiState() }
    }

    fun complete(note: String) {
        val assignmentId = (_state.value as? UiState.Content)?.data?.assignment?.id ?: return
        if (_sending.value || afterPhotos.isUploading || afterPhotos.urls.isEmpty()) return
        _sending.value = true
        viewModelScope.launch {
            val result = repository.complete(orderId, assignmentId, afterPhotos.urls, note.trim().ifEmpty { null })
            _sending.value = false
            _results.send((result as? ApiResult.Failure)?.message)
        }
    }
}

/** PS-18 Hoàn thành công việc (#70): after photos, result note, what the customer pays; then PS-19. */
@AndroidEntryPoint
class CompleteFragment : Fragment(R.layout.fragment_complete) {

    @Inject lateinit var captureFiles: CameraCaptureFiles

    private val viewModel: CompleteViewModel by viewModels()
    private val photoPicker = PhotoPicker(this, { captureFiles }) { uri -> viewModel.afterPhotos.add(uri) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCompleteBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.actionBar.primaryButton.setText(R.string.complete_send)
        binding.actionBar.primaryButton.setOnClickListener { viewModel.complete(binding.noteInput.text?.toString().orEmpty()) }

        collectWhenStarted(viewModel.state) { state ->
            LoadStatus.bind(binding.status, state, binding.content) { viewModel.load() }
            binding.actionBar.root.isVisible = state is UiState.Content
            if (state is UiState.Content) bindMoney(binding.money, state.data)
        }
        collectWhenStarted(viewModel.afterPhotos.photos.combine(viewModel.sending) { photos, sending -> photos to sending }) { (photos, sending) ->
            bindPhotoStrip(binding.afterStrip, photos, viewModel.afterPhotos.max, onAdd = { photoPicker.show() },
                onRemove = viewModel.afterPhotos::remove)
            binding.actionBar.primaryButton.isEnabled = !sending && photos.any { it.url != null } && photos.none { it.uploading }
        }
        collectWhenStarted(viewModel.afterPhotos.errors) { error ->
            if (error != null) {
                Snackbar.make(view, error, Snackbar.LENGTH_LONG).show()
                viewModel.afterPhotos.consumeError()
            }
        }
        collectWhenStarted(viewModel.results) { error ->
            if (error == null) {
                // PS-19 replaces this screen: back from there goes to the work screen, not to a finished form.
                findNavController().navigate(
                    R.id.ps19,
                    bundleOf(OrderDetailFragment.ARG_ORDER_ID to viewModel.orderId),
                    navOptions { popUpTo(R.id.ps18) { inclusive = true } },
                )
            } else {
                Snackbar.make(view, error, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun bindMoney(container: ViewGroup, order: OrderDetail) {
        container.removeAllViews()
        addRow(container, getString(R.string.order_price_quote), Money.format(order.pricing.subTotal))
        order.extraCharges.filter { it.status == ExtraChargeStatus.APPROVED }.forEach {
            addRow(container, getString(R.string.complete_extra_line, it.description), Money.format(it.amount))
        }
        addRow(container, getString(R.string.complete_total), Money.format(order.pricing.totalAmount))
    }

    private fun addRow(container: ViewGroup, label: String, value: String) {
        val row = ItemStatusRowBinding.inflate(layoutInflater, container, false)
        row.label.text = label
        row.value.text = value
        container.addView(row.root)
    }
}

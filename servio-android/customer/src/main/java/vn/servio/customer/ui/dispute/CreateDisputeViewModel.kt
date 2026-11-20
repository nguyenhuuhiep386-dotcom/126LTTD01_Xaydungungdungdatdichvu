package vn.servio.customer.ui.dispute

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.CreateDisputeBody
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.ServerConfig
import vn.servio.customer.data.repository.DisputeRepository
import vn.servio.customer.data.repository.FileRepository
import vn.servio.customer.data.repository.OrderRepository
import vn.servio.customer.data.repository.ServerConfigRepository
import vn.servio.customer.ui.dispute.DisputeRules.DescriptionError
import vn.servio.customer.ui.dispute.DisputeRules.Reason
import vn.servio.customer.ui.request.DraftImage
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * CS-33 Gửi khiếu nại (#146). The order card comes from #63 and the info card from /app-config; neither blocks the
 * form (each is hidden when it fails). Evidence photos upload as soon as they are picked (DISPUTE_EVIDENCE); "Gửi" is
 * off while one is uploading or failed. Field errors show from the first tap on "Gửi", then follow every edit.
 */
@HiltViewModel
class CreateDisputeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val orderRepository: OrderRepository,
    private val configRepository: ServerConfigRepository,
    private val fileRepository: FileRepository,
    private val disputeRepository: DisputeRepository,
) : ViewModel() {

    val orderId: String = savedStateHandle.get<String>(ARG_ORDER_ID).orEmpty()

    /**
     * [order] / [config] null while unknown or failed. [reasonError] / [descriptionError]: a local rule broken (shown
     * once [checked]) or [serverReasonError] / [serverDescriptionError], the server's sentence for that field (cleared
     * by the next edit of it).
     */
    data class State(
        val order: OrderDetail? = null,
        val config: ServerConfig? = null,
        val reason: Reason? = null,
        val description: String = "",
        val images: List<DraftImage> = emptyList(),
        val checked: Boolean = false,
        val sending: Boolean = false,
        val serverReasonError: String? = null,
        val serverDescriptionError: String? = null,
    ) {
        val missingReason: Boolean get() = checked && reason == null
        val descriptionError: DescriptionError? get() = if (checked) DisputeRules.checkDescription(description) else null
        val imagesBusy: Boolean get() = images.any { it.status != DraftImage.Status.UPLOADED }
        val canSend: Boolean get() = !sending && !imagesBusy
        val freeSlots: Int get() = DisputeRules.MAX_IMAGES - images.size
    }

    sealed interface Event {
        /** #146 201: leave for CS-20. */
        data object Sent : Event

        /** A failure that belongs to no field (409, 422, network …): the server's sentence. */
        data class Message(val text: String) : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val nextImageId = AtomicLong()
    private val uploadJobs = mutableMapOf<Long, Job>()

    init {
        viewModelScope.launch {
            (orderRepository.getOrder(orderId) as? ApiResult.Success)?.data?.let { order -> _state.update { it.copy(order = order) } }
        }
        viewModelScope.launch {
            (configRepository.getConfig() as? ApiResult.Success)?.data?.let { config -> _state.update { it.copy(config = config) } }
        }
    }

    fun selectReason(reason: Reason) = _state.update { it.copy(reason = reason, serverReasonError = null) }

    fun setDescription(text: String) {
        if (text == _state.value.description) return
        _state.update { it.copy(description = text, serverDescriptionError = null) }
    }

    fun addImages(uris: List<Uri>) {
        val added = uris.take(_state.value.freeSlots).map { DraftImage(nextImageId.incrementAndGet(), it, DraftImage.Status.UPLOADING) }
        if (added.isEmpty()) return
        _state.update { it.copy(images = it.images + added) }
        added.forEach(::upload)
    }

    fun retryImage(localId: Long) {
        val image = _state.value.images.firstOrNull { it.localId == localId } ?: return
        if (image.status != DraftImage.Status.FAILED) return
        val retrying = image.copy(status = DraftImage.Status.UPLOADING, errorMessage = null)
        replaceImage(retrying)
        upload(retrying)
    }

    fun removeImage(localId: Long) {
        uploadJobs.remove(localId)?.cancel()
        _state.update { s -> s.copy(images = s.images.filter { it.localId != localId }) }
    }

    private fun upload(image: DraftImage) {
        uploadJobs[image.localId] = viewModelScope.launch {
            val updated = when (val result = fileRepository.uploadEvidence(image.uri)) {
                is ApiResult.Success -> image.copy(status = DraftImage.Status.UPLOADED, url = result.data.url)
                is ApiResult.Failure -> image.copy(status = DraftImage.Status.FAILED, errorMessage = result.message)
            }
            uploadJobs.remove(image.localId)
            replaceImage(updated)
        }
    }

    /** No-op when the image was removed while uploading. */
    private fun replaceImage(image: DraftImage) = _state.update { s ->
        s.copy(images = s.images.map { if (it.localId == image.localId) image else it })
    }

    /** "Gửi khiếu nại": local checks first (errors stay visible from now on), then #146. */
    fun send() {
        _state.update { it.copy(checked = true) }
        val s = _state.value
        val reason = s.reason
        if (!s.canSend || reason == null || DisputeRules.checkDescription(s.description) != null) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            val body = CreateDisputeBody(
                reason = reason.apiValue,
                description = s.description.trim(),
                evidenceUrls = s.images.mapNotNull { it.url },
            )
            when (val result = disputeRepository.create(orderId, body)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(sending = false) }
                    _events.send(Event.Sent)
                }
                is ApiResult.Failure -> {
                    when (result.field) {
                        FIELD_REASON -> _state.update { it.copy(sending = false, serverReasonError = result.message) }
                        FIELD_DESCRIPTION -> _state.update { it.copy(sending = false, serverDescriptionError = result.message) }
                        else -> {
                            _state.update { it.copy(sending = false) }
                            _events.send(Event.Message(result.message))
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ARG_ORDER_ID = "orderId"
        const val FIELD_REASON = "reason"
        const val FIELD_DESCRIPTION = "description"
    }
}

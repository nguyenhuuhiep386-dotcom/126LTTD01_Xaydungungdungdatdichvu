package vn.servio.partner.ui.work

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.partner.data.model.OrderDetail
import vn.servio.partner.data.repository.FileRepository
import vn.servio.partner.data.repository.OrderRepository
import vn.servio.partner.ui.common.PhotoUploads
import vn.servio.partner.ui.order.OrderDetailFragment
import javax.inject.Inject

/**
 * PS-16 Thực hiện công việc. ARRIVED: before photos then "Bắt đầu làm việc" (#69), or "Khách không có mặt" (#180).
 * IN_PROGRESS: before photos, extra charges (PS-17, live answers from the customer) and "Hoàn thành công việc" (PS-18).
 */
@HiltViewModel
class WorkViewModel @Inject constructor(
    private val repository: OrderRepository,
    fileRepository: FileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val orderId: String = checkNotNull(savedStateHandle[OrderDetailFragment.ARG_ORDER_ID])

    sealed interface Event {
        data class Message(val text: String) : Event
        data object Started : Event
        data object NoShowReported : Event
    }

    val beforePhotos = PhotoUploads(viewModelScope, fileRepository)

    private val _state = MutableStateFlow<UiState<OrderDetail>>(UiState.Loading)
    val state: StateFlow<UiState<OrderDetail>> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        load()
        viewModelScope.launch {
            repository.changes().filter { it == null || it == orderId }.collect { load(showSpinner = false) }
        }
    }

    fun load(showSpinner: Boolean = true) {
        if (showSpinner) _state.value = UiState.Loading
        viewModelScope.launch {
            val result = repository.get(orderId)
            if (result is ApiResult.Success || showSpinner) _state.value = result.toUiState()
        }
    }

    fun startWork() {
        val assignmentId = (_state.value as? UiState.Content)?.data?.assignment?.id ?: return
        if (_busy.value || beforePhotos.isUploading || beforePhotos.urls.isEmpty()) return
        _busy.value = true
        viewModelScope.launch {
            val result = repository.startWork(orderId, assignmentId, beforePhotos.urls)
            _busy.value = false
            when (result) {
                is ApiResult.Success -> {
                    _events.send(Event.Started)
                    load(showSpinner = false)
                }
                is ApiResult.Failure -> _events.send(Event.Message(result.message))
            }
        }
    }

    fun reportNoShow(note: String) {
        val assignmentId = (_state.value as? UiState.Content)?.data?.assignment?.id ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = repository.customerNoShow(orderId, assignmentId, note.trim().ifEmpty { null })
            _busy.value = false
            when (result) {
                is ApiResult.Success -> _events.send(Event.NoShowReported)
                is ApiResult.Failure -> _events.send(Event.Message(result.message))
            }
        }
    }
}

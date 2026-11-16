package vn.servio.partner.ui.order

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
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
import vn.servio.partner.data.location.DeviceLocation
import vn.servio.partner.data.model.OrderDetail
import vn.servio.partner.data.model.TimelineEntry
import vn.servio.partner.data.repository.OrderRepository
import javax.inject.Inject

/**
 * PS-14 Chi tiết đơn (#63, #64). The bottom actions depend on this partner's assignment status:
 * PENDING → accept (#65) / reject (#66) within 10 minutes; ACCEPTED → start moving (#67); later steps in DT-5.
 */
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    private val repository: OrderRepository,
    private val deviceLocation: DeviceLocation,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val orderId: String = checkNotNull(savedStateHandle[OrderDetailFragment.ARG_ORDER_ID])

    data class Content(val order: OrderDetail, val timeline: List<TimelineEntry>)

    sealed interface Event {
        data class Message(val text: String) : Event

        /** #67 succeeded: start the tracking service and open PS-15. */
        data class StartedMoving(val assignmentId: String) : Event
        data object Rejected : Event
        data object Cancelled : Event
    }

    private val _state = MutableStateFlow<UiState<Content>>(UiState.Loading)
    val state: StateFlow<UiState<Content>> = _state.asStateFlow()

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
            val order = async { repository.get(orderId) }
            val timeline = async { repository.timeline(orderId) }
            _state.value = when (val result = order.await()) {
                is ApiResult.Failure -> if (showSpinner || _state.value !is UiState.Content) UiState.Error(result.message) else _state.value
                is ApiResult.Success -> UiState.Content(
                    Content(result.data, (timeline.await() as? ApiResult.Success)?.data.orEmpty()),
                )
            }
        }
    }

    fun accept() = runAction { order, assignmentId ->
        repository.accept(order.id, assignmentId).also { if (it is ApiResult.Success) load(showSpinner = false) }
    }

    fun reject(reason: String) = runAction { order, assignmentId ->
        repository.reject(order.id, assignmentId, reason).also { if (it is ApiResult.Success) _events.send(Event.Rejected) }
    }

    /** Call after the location permission was granted. The starting point is optional for the server. */
    fun startMoving() = runAction { order, assignmentId ->
        val from = deviceLocation.current()
        repository.startMoving(order.id, assignmentId, from).also {
            if (it is ApiResult.Success) {
                _events.send(Event.StartedMoving(assignmentId))
                load(showSpinner = false)
            }
        }
    }

    fun cancel(reason: String) = runAction { order, assignmentId ->
        repository.cancel(order.id, assignmentId, reason).also { if (it is ApiResult.Success) _events.send(Event.Cancelled) }
    }

    /** Runs one order action with the busy flag; failures become a snackbar message. */
    private fun runAction(action: suspend (OrderDetail, String) -> ApiResult<*>) {
        val order = (_state.value as? UiState.Content)?.data?.order ?: return
        val assignmentId = order.assignment?.id ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = action(order, assignmentId)
            _busy.value = false
            if (result is ApiResult.Failure) {
                _events.send(Event.Message(result.message))
                load(showSpinner = false)
            }
        }
    }
}

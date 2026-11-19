package vn.servio.customer.ui.completion

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.repository.OrderRepository
import vn.servio.customer.data.repository.OrderUpdates
import vn.servio.customer.data.repository.reloadCoalesced
import javax.inject.Inject

/**
 * CS-23 Xác nhận hoàn thành: #63 and #64 in parallel (#64 only for the partner's note; when it fails the note is just
 * not shown), then #71 with the rowVersion of the #63 on screen. OrderStatusChanged of this order reloads it; once the
 * order is no longer COMPLETED_BY_PARTNER (confirmed automatically, disputed …) the screen leaves.
 */
@HiltViewModel
class ConfirmCompletionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OrderRepository,
    orderUpdates: OrderUpdates,
) : ViewModel() {

    val orderId: String = savedStateHandle.get<String>(ARG_ORDER_ID).orEmpty()

    /** [error]: the first #63 failed (full-screen retry). [sending]: #71 runs, the button is off. */
    data class State(
        val detail: OrderDetail? = null,
        val note: String? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val sending: Boolean = false,
    )

    sealed interface Event {
        /** Not (or no longer) waiting for the confirmation: go back with "Đơn không còn chờ xác nhận". */
        data object NotWaiting : Event

        /** #71 succeeded: open CS-26 for [assignmentId] (null: no assignment to review, just go back). */
        data class Confirmed(val assignmentId: String?) : Event

        /** A snackbar on CS-23: a text of the app ([res]) or the server's [text]. */
        data class Message(@StringRes val res: Int? = null, val text: String? = null) : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    /** Once left (confirmed or no longer waiting) later reloads say nothing more. */
    private var finished = false

    init {
        if (orderId.isNotEmpty()) {
            viewModelScope.launch {
                orderUpdates.changes()
                    .filter { OrderUpdates.concerns(it, orderId) }
                    .map { }
                    .reloadCoalesced { refresh(withTimeline = false) }
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch { refresh(withTimeline = true) }
    }

    /** #71; ignored while it runs or before #63 answered. */
    fun confirm() {
        val current = _state.value
        val detail = current.detail ?: return
        if (current.sending || finished) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            when (val result = repository.confirmCompletion(orderId, detail.rowVersion)) {
                is ApiResult.Success -> {
                    finished = true
                    _state.update { it.copy(sending = false) }
                    _events.send(Event.Confirmed(detail.primary?.id))
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(sending = false) }
                    when (ConfirmCompletionUi.failure(result.code)) {
                        ConfirmCompletionUi.Failure.CHANGED -> {
                            _events.send(Event.Message(res = R.string.confirm_changed))
                            refresh(withTimeline = false)
                        }
                        ConfirmCompletionUi.Failure.NOT_WAITING -> leave()
                        ConfirmCompletionUi.Failure.DISPUTED -> _events.send(Event.Message(res = R.string.confirm_disputed))
                        ConfirmCompletionUi.Failure.OTHER -> _events.send(Event.Message(text = result.message))
                    }
                }
            }
        }
    }

    private suspend fun refresh(withTimeline: Boolean) = coroutineScope {
        val timeline = if (withTimeline) async { repository.getTimeline(orderId) } else null
        when (val result = repository.getOrder(orderId)) {
            is ApiResult.Success -> {
                val detail = result.data
                _state.update { it.copy(detail = detail, loading = false, error = null) }
                if (!ConfirmCompletionUi.isWaiting(detail)) leave()
            }
            is ApiResult.Failure -> _state.update {
                if (it.detail == null) it.copy(loading = false, error = result.message) else it.copy(loading = false)
            }
        }
        val entries = (timeline?.await() as? ApiResult.Success)?.data
        if (entries != null) _state.update { it.copy(note = ConfirmCompletionUi.partnerNote(entries)) }
    }

    private suspend fun leave() {
        if (finished) return
        finished = true
        _events.send(Event.NotWaiting)
    }

    companion object {
        const val ARG_ORDER_ID = "orderId"
    }
}

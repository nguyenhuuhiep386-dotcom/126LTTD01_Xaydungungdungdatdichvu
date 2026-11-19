package vn.servio.customer.ui.orderdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.ArrivalConfirmation
import vn.servio.customer.data.model.ArrivalConfirmationStatus
import vn.servio.customer.data.repository.OrderRepository
import vn.servio.customer.data.repository.OrderUpdates
import vn.servio.customer.ui.order.OrderTexts
import java.time.Instant
import javax.inject.Inject

/**
 * CS-20b "Thợ báo đã đến, đúng không?": answers one request with #179. The request comes in the arguments (from
 * [OrderUpdates.pendingArrival]); once answered, expired or refused by the server it is dropped from [OrderUpdates].
 */
@HiltViewModel
class ArrivalConfirmViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OrderRepository,
    private val orderUpdates: OrderUpdates,
) : ViewModel() {

    val request = ArrivalConfirmation(
        orderId = savedStateHandle.get<String>(ARG_ORDER_ID).orEmpty(),
        assignmentId = savedStateHandle.get<String>(ARG_ASSIGNMENT_ID).orEmpty(),
        requestId = savedStateHandle.get<String>(ARG_REQUEST_ID).orEmpty(),
        status = ArrivalConfirmationStatus.PENDING,
        expiresAt = savedStateHandle.get<String>(ARG_EXPIRES_AT).orEmpty(),
    )

    /** [sending]: #179 is running, both buttons are off. [error]: the last answer failed for another reason. */
    data class State(val sending: Boolean = false, val error: String? = null) {
        val buttonsEnabled: Boolean get() = !sending
    }

    sealed interface Event {
        /** Confirmed: the order is now ARRIVED. */
        data object Confirmed : Event

        /** "Chưa thấy" was sent. */
        data object Denied : Event

        /** Past expiresAt before an answer. */
        data object Expired : Event

        /** 409: expired or answered elsewhere; the screen reloads. */
        data object Conflict : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    internal var now: () -> Instant = Instant::now

    private var finished = false

    /** Seconds left until expiresAt (negative once past); null when unreadable. */
    fun secondsLeft(): Long? = OrderTexts.secondsUntil(request.expiresAt, now())

    fun answer(confirmed: Boolean) {
        if (_state.value.sending || finished) return
        if ((secondsLeft() ?: 0) <= 0) {
            expired()
            return
        }
        _state.update { State(sending = true) }
        viewModelScope.launch {
            when (val result = repository.respondArrival(request, confirmed)) {
                is ApiResult.Success -> finish(if (confirmed) Event.Confirmed else Event.Denied)
                is ApiResult.Failure -> if (result.code in CONFLICT_CODES) {
                    finish(Event.Conflict)
                } else {
                    _state.update { State(sending = false, error = result.message) }
                }
            }
        }
    }

    /** The countdown reached 0 (or the dialog found the request already past). Ignored while #179 is running. */
    fun expired() {
        if (finished || _state.value.sending) return
        viewModelScope.launch { finish(Event.Expired) }
    }

    private suspend fun finish(event: Event) {
        finished = true
        orderUpdates.arrivalAnswered(request.requestId)
        _state.update { it.copy(sending = false) }
        _events.send(event)
    }

    companion object {
        const val ARG_ORDER_ID = "orderId"
        const val ARG_ASSIGNMENT_ID = "assignmentId"
        const val ARG_REQUEST_ID = "requestId"
        const val ARG_EXPIRES_AT = "expiresAt"

        /** 409 of #179: CONFLICT (expired or already answered); a status move refused gives INVALID_STATUS_TRANSITION. */
        val CONFLICT_CODES = setOf("CONFLICT", "INVALID_STATUS_TRANSITION")
    }
}

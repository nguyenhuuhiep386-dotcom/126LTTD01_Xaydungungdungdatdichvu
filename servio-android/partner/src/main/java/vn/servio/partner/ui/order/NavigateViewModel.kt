package vn.servio.partner.ui.order

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.partner.R
import vn.servio.partner.data.location.DeviceLocation
import vn.servio.partner.data.location.Fix
import vn.servio.partner.data.model.OrderDetail
import vn.servio.partner.data.model.OrderStatus
import vn.servio.partner.data.repository.OrderRepository
import javax.inject.Inject

/**
 * PS-15 Điều hướng tới khách: map with the customer and the partner, "Đã đến nơi" (#68 GPS check-in),
 * and when GPS is too far or unreliable, "Nhờ khách xác nhận đã đến" (#178 → ArrivalConfirmationResolved).
 */
@HiltViewModel
class NavigateViewModel @Inject constructor(
    private val repository: OrderRepository,
    private val deviceLocation: DeviceLocation,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val orderId: String = checkNotNull(savedStateHandle[OrderDetailFragment.ARG_ORDER_ID])

    /** Why check-in failed, shown in the warning card; [canAskCustomer] shows "Nhờ khách xác nhận". */
    data class CheckInProblem(val message: String? = null, @param:StringRes val messageRes: Int = 0, val canAskCustomer: Boolean)

    sealed interface Event {
        data object Arrived : Event
        data class Message(val text: String) : Event
    }

    private val _state = MutableStateFlow<UiState<OrderDetail>>(UiState.Loading)
    val state: StateFlow<UiState<OrderDetail>> = _state.asStateFlow()

    /** The partner's own position on the map, refreshed every few seconds while the screen is open. */
    private val _me = MutableStateFlow<Fix?>(null)
    val me: StateFlow<Fix?> = _me.asStateFlow()

    private val _problem = MutableStateFlow<CheckInProblem?>(null)
    val problem: StateFlow<CheckInProblem?> = _problem.asStateFlow()

    /** Expiry (ISO) of the pending "Nhờ khách xác nhận" request, null when none. */
    private val _waitingUntil = MutableStateFlow<String?>(null)
    val waitingUntil: StateFlow<String?> = _waitingUntil.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        load()
        viewModelScope.launch {
            while (isActive) {
                deviceLocation.current(maxAgeMillis = REFRESH_MILLIS)?.let { _me.value = it }
                delay(REFRESH_MILLIS)
            }
        }
        viewModelScope.launch {
            repository.arrivalResolved().filter { it.orderId == orderId }.collect { answer ->
                _waitingUntil.value = null
                when (answer.status) {
                    STATUS_CONFIRMED -> _events.send(Event.Arrived)
                    STATUS_DENIED -> _problem.value = CheckInProblem(messageRes = R.string.navigate_denied, canAskCustomer = true)
                    else -> _problem.value = CheckInProblem(messageRes = R.string.navigate_expired, canAskCustomer = true)
                }
            }
        }
        // Arrived some other way (e.g. confirmed while this screen was in the background).
        viewModelScope.launch {
            repository.changes().filter { it == null || it == orderId }.collect {
                val order = repository.get(orderId)
                if (order is ApiResult.Success) {
                    _state.value = UiState.Content(order.data)
                    if (order.data.assignment?.status != OrderStatus.ON_THE_WAY) _events.send(Event.Arrived)
                }
            }
        }
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch { _state.value = repository.get(orderId).toUiState() }
    }

    fun arrive() {
        val assignmentId = (_state.value as? UiState.Content)?.data?.assignment?.id ?: return
        if (_busy.value) return
        _busy.value = true
        _problem.value = null
        viewModelScope.launch {
            // A fresh fix: the server rejects positions older than a minute or less accurate than 100 m.
            val fix = deviceLocation.current(maxAgeMillis = FRESH_FIX_MILLIS)
            if (fix == null) {
                _busy.value = false
                // #178 needs a check-in refused by the server in the last 10 minutes: without a fix nothing was sent.
                _problem.value = CheckInProblem(messageRes = R.string.navigate_no_fix, canAskCustomer = false)
                return@launch
            }
            _me.value = fix
            val result = repository.arrive(orderId, assignmentId, fix)
            _busy.value = false
            when (result) {
                is ApiResult.Success -> _events.send(Event.Arrived)
                is ApiResult.Failure -> _problem.value = CheckInProblem(
                    message = result.message,
                    canAskCustomer = result.code != MOCK_LOCATION,
                )
            }
        }
    }

    fun askCustomer() {
        val assignmentId = (_state.value as? UiState.Content)?.data?.assignment?.id ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = repository.requestArrivalConfirmation(orderId, assignmentId)
            _busy.value = false
            when (result) {
                is ApiResult.Success -> {
                    _problem.value = null
                    _waitingUntil.value = result.data.expiresAt
                }
                is ApiResult.Failure -> _events.send(Event.Message(result.message))
            }
        }
    }

    private companion object {
        const val REFRESH_MILLIS = 5_000L
        const val FRESH_FIX_MILLIS = 20_000L
        const val MOCK_LOCATION = "MOCK_LOCATION_DETECTED"
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_DENIED = "DENIED"
    }
}

package vn.servio.customer.ui.tracking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.Tracking
import vn.servio.customer.data.repository.OrderRepository
import vn.servio.customer.data.repository.OrderUpdates
import vn.servio.customer.data.repository.PartnerRepository
import vn.servio.customer.data.repository.reloadCoalesced
import vn.servio.customer.ui.order.TrackingUi
import javax.inject.Inject

/**
 * CS-21 Theo dõi bản đồ of one assignment: #63 (address, times, contact), #27 (number of reviews) and #76, then
 * PartnerLocationUpdated of this assignment. OrderStatusChanged of the order and hub reconnects reload #63 and #76;
 * once the assignment is no longer ON_THE_WAY (also when opened too late) the screen goes back to CS-20 ([Event.Leave]).
 */
@HiltViewModel
class OrderTrackingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OrderRepository,
    private val partnerRepository: PartnerRepository,
    private val orderUpdates: OrderUpdates,
) : ViewModel() {

    val orderId: String = savedStateHandle.get<String>(ARG_ORDER_ID).orEmpty()
    val assignmentId: String = savedStateHandle.get<String>(ARG_ASSIGNMENT_ID).orEmpty()

    /** [error]: the first #63 failed (retry). [reviewCount]: #27, null while unknown. [tracking]: null until #76 answers. */
    data class State(
        val detail: OrderDetail? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val reviewCount: Int? = null,
        val tracking: Tracking? = null,
    )

    sealed interface Event {
        /** The assignment is no longer ON_THE_WAY: back to CS-20. Sent once. */
        data object Leave : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var left = false
    private var reviewsLoaded = false

    init {
        if (orderId.isNotEmpty()) {
            viewModelScope.launch {
                orderUpdates.changes()
                    .filter { OrderUpdates.concerns(it, orderId) }
                    .map { }
                    .reloadCoalesced { refresh() }
            }
            viewModelScope.launch {
                orderUpdates.locations()
                    .filter { it.assignmentId.equals(assignmentId, ignoreCase = true) }
                    .collect { incoming -> _state.update { it.copy(tracking = TrackingUi.newer(it.tracking, incoming)) } }
            }
            load()
        }
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        if (left) return
        when (val result = repository.getOrder(orderId)) {
            is ApiResult.Success -> {
                val detail = result.data
                _state.update { it.copy(detail = detail, loading = false, error = null) }
                if (!isOnTheWay(detail, assignmentId)) {
                    leave()
                    return
                }
                coroutineScope {
                    launch { loadReviewCount(detail) }
                    launch { loadTracking() }
                }
            }
            is ApiResult.Failure -> _state.update {
                // A later failure keeps what is shown; the next change or reconnect asks again.
                if (it.detail == null) it.copy(loading = false, error = result.message) else it.copy(loading = false)
            }
        }
    }

    private suspend fun loadTracking() {
        val result = repository.getTracking(orderId, assignmentId)
        if (result is ApiResult.Success) {
            result.data?.let { fresh -> _state.update { it.copy(tracking = TrackingUi.newer(it.tracking, fresh)) } }
        }
    }

    private suspend fun loadReviewCount(detail: OrderDetail) {
        if (reviewsLoaded) return
        val partnerId = assignmentOf(detail, assignmentId)?.partner?.partnerProfileId ?: return
        val result = partnerRepository.getPublicProfile(partnerId)
        if (result is ApiResult.Success) {
            reviewsLoaded = true
            _state.update { it.copy(reviewCount = result.data.totalReviews) }
        }
    }

    private suspend fun leave() {
        if (left) return
        left = true
        _events.send(Event.Leave)
    }

    companion object {
        const val ARG_ORDER_ID = "orderId"
        const val ARG_ASSIGNMENT_ID = "assignmentId"

        fun assignmentOf(detail: OrderDetail, assignmentId: String) =
            detail.assignments.firstOrNull { it.id.equals(assignmentId, ignoreCase = true) } ?: detail.primary

        /** The order (not DISPUTED, not cancelled …) and this assignment are both ON_THE_WAY. */
        fun isOnTheWay(detail: OrderDetail, assignmentId: String): Boolean =
            detail.status == OrderStatus.ON_THE_WAY && assignmentOf(detail, assignmentId)?.status == OrderStatus.ON_THE_WAY
    }
}

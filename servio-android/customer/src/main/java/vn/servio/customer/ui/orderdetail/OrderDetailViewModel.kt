package vn.servio.customer.ui.orderdetail

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
 * CS-20 Chi tiết đơn hàng: #63, plus #27 for the number of reviews of the primary partner and #151 for the label of
 * the cancel button while the order can be cancelled, and #76 (then PartnerLocationUpdated) while ON_THE_WAY. OrderStatusChanged of this order (and hub reconnects) reload it
 * silently, coalesced (see [reloadCoalesced]).
 */
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OrderRepository,
    private val partnerRepository: PartnerRepository,
    private val orderUpdates: OrderUpdates,
) : ViewModel() {

    val orderId: String = savedStateHandle.get<String>(ARG_ORDER_ID).orEmpty()

    /**
     * [detail] null until the first answer. [error]: first load failed (full-screen retry); [notFound]: 404/403.
     * [reviewCount]: from #27, null while unknown or when it failed. [cancelFee]: from #151, null while unknown.
     */
    data class State(
        val detail: OrderDetail? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val notFound: Boolean = false,
        val reviewCount: Int? = null,
        val cancelFee: Long? = null,
        /** #76 / PartnerLocationUpdated of the primary assignment while ON_THE_WAY; null while unknown. */
        val tracking: Tracking? = null,
    )

    sealed interface Event {
        /** A reload failed while the order is on screen: it stays, with this message. */
        data class Message(val text: String) : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var reviewsFor: String? = null

    /** Deadline already answered by a reload (the countdown reached 0). */
    private var reloadedDeadline: String? = null

    init {
        if (orderId.isNotEmpty()) {
            viewModelScope.launch {
                orderUpdates.changes()
                    .filter { OrderUpdates.concerns(it, orderId) }
                    .map { }
                    .reloadCoalesced { refresh(silent = true) }
            }
            viewModelScope.launch {
                orderUpdates.locations().collect { incoming ->
                    val assignmentId = trackedAssignment(_state.value.detail) ?: return@collect
                    if (!incoming.assignmentId.equals(assignmentId, ignoreCase = true)) return@collect
                    _state.update { it.copy(tracking = TrackingUi.newer(it.tracking, incoming)) }
                }
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch { refresh(silent = false) }
    }

    /** The countdown of a PENDING order reached 0: the server has cancelled it (or the partner accepted). Once. */
    fun onDeadlinePassed() {
        val deadline = _state.value.detail?.primary?.acceptanceDeadlineAt ?: return
        if (deadline == reloadedDeadline) return
        reloadedDeadline = deadline
        load()
    }

    private suspend fun refresh(silent: Boolean) {
        when (val result = repository.getOrder(orderId)) {
            is ApiResult.Success -> {
                val detail = result.data
                _state.update { it.copy(detail = detail, loading = false, error = null, notFound = false) }
                coroutineScope {
                    launch { loadReviewCount(detail) }
                    launch { loadCancelFee(detail) }
                    launch { loadTracking(detail) }
                }
            }
            is ApiResult.Failure -> when {
                result.code == NOT_FOUND || result.code == FORBIDDEN ->
                    _state.update { it.copy(loading = false, notFound = true, error = null) }
                _state.value.detail == null ->
                    _state.update { it.copy(loading = false, error = result.message.takeUnless { silent } ?: it.error) }
                else -> {
                    _state.update { it.copy(loading = false) }
                    if (!silent) _events.send(Event.Message(result.message))
                }
            }
        }
    }

    /** #27 once per partner; on failure the row shows the score of #63 without a count. */
    private suspend fun loadReviewCount(detail: OrderDetail) {
        val partnerId = detail.primary?.partner?.partnerProfileId ?: return
        if (partnerId == reviewsFor && _state.value.reviewCount != null) return
        reviewsFor = partnerId
        val result = partnerRepository.getPublicProfile(partnerId)
        if (result is ApiResult.Success) _state.update { it.copy(reviewCount = result.data.totalReviews) }
    }

    /** #76 each time #63 shows the order ON_THE_WAY (also after a reconnect); otherwise there is nothing to track. */
    private suspend fun loadTracking(detail: OrderDetail) {
        val assignmentId = trackedAssignment(detail)
        if (assignmentId == null) {
            _state.update { it.copy(tracking = null) }
            return
        }
        val result = repository.getTracking(orderId, assignmentId)
        if (result is ApiResult.Success) {
            result.data?.let { fresh -> _state.update { it.copy(tracking = TrackingUi.newer(it.tracking, fresh)) } }
        }
    }

    /** The primary assignment while the order is ON_THE_WAY. */
    private fun trackedAssignment(detail: OrderDetail?): String? =
        detail?.takeIf { it.status == OrderStatus.ON_THE_WAY }?.primary?.id

    /** #151 while the order can be cancelled; otherwise (or on failure) the fee is unknown and the label plain. */
    private suspend fun loadCancelFee(detail: OrderDetail) {
        if (!OrderDetailUi.canCancel(detail.status)) {
            _state.update { it.copy(cancelFee = null) }
            return
        }
        val fee = (repository.cancelPreview(orderId) as? ApiResult.Success)?.data?.fee
        _state.update { it.copy(cancelFee = fee) }
    }

    companion object {
        const val ARG_ORDER_ID = "orderId"
        const val NOT_FOUND = "NOT_FOUND"
        const val FORBIDDEN = "FORBIDDEN"
    }
}

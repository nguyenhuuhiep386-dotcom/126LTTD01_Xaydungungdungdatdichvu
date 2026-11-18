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
import vn.servio.customer.data.model.ExtraCharge
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
 * silently, coalesced (see [reloadCoalesced]); so does ExtraChargeRequested, whose new PENDING charge CS-22 then shows.
 * It also holds CS-22's state (the sheet shares this view model): the charges closed in this visit and the #74 call.
 */
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
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
        /** CS-22: ids of the charges the customer closed during this visit of CS-20 (not opened again by themselves). */
        val closedCharges: Set<String> = emptySet(),
        /** CS-22: the charge whose #74 is running (both buttons off); null when none. */
        val answeringCharge: String? = null,
        /** CS-22: message of a failed #74 that keeps the sheet open (network …); cleared by the next try. */
        val chargeError: String? = null,
        /** CS-22: a charge whose #74 failed with 409/404, kept closed until the next #63 answer. */
        val heldCharge: String? = null,
    ) {
        /** The charge CS-22 should open now (see [OrderDetailUi.chargeToOpen]); null when none. */
        fun chargeToOpen(includeClosed: Boolean): ExtraCharge? =
            detail?.let { OrderDetailUi.chargeToOpen(it, closedCharges, includeClosed, heldCharge) }
    }

    sealed interface Event {
        /** A reload failed while the order is on screen: it stays, with this message. */
        data class Message(val text: String) : Event

        /** #74 succeeded for a charge of [amount]. */
        data class ChargeAnswered(val approved: Boolean, val amount: Long) : Event

        /** #74 failed in a way that closes the sheet ([OrderDetailUi.ChargeFailure], never OTHER). */
        data class ChargeFailed(val failure: OrderDetailUi.ChargeFailure) : Event
    }

    private val _state = MutableStateFlow(State(closedCharges = savedStateHandle.get<ArrayList<String>>(KEY_CLOSED_CHARGES).orEmpty().toSet()))
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

    /** The customer closed CS-22 on [chargeId] (Back, outside, swipe): it does not open by itself again here. */
    fun chargeClosed(chargeId: String) {
        _state.update { it.copy(closedCharges = it.closedCharges + chargeId, chargeError = null) }
        savedStateHandle[KEY_CLOSED_CHARGES] = ArrayList(_state.value.closedCharges)
    }

    /**
     * #74 without a note; ignored while another answer runs. Success: the charge takes the server's status at once
     * (so CS-22 closes), then #63 is reloaded for the new totals. A 409/404 closes the sheet and reloads; any other
     * error keeps it open with the message.
     */
    fun answerCharge(chargeId: String, approved: Boolean) {
        val current = _state.value
        if (current.answeringCharge != null) return
        val charge = current.detail?.extraCharges?.firstOrNull { it.id == chargeId } ?: return
        _state.update { it.copy(answeringCharge = chargeId, chargeError = null) }
        viewModelScope.launch {
            when (val result = repository.respondExtraCharge(orderId, chargeId, approved)) {
                is ApiResult.Success -> {
                    val answered = result.data
                    _state.update { state ->
                        val detail = state.detail?.let { d ->
                            d.copy(extraCharges = d.extraCharges.map { if (it.id == chargeId) it.copy(status = answered.status) else it })
                        }
                        state.copy(detail = detail, answeringCharge = null)
                    }
                    _events.send(Event.ChargeAnswered(approved, charge.amount))
                    refresh(silent = true)
                }
                is ApiResult.Failure -> when (val failure = OrderDetailUi.chargeFailure(result.code)) {
                    OrderDetailUi.ChargeFailure.OTHER ->
                        _state.update { it.copy(answeringCharge = null, chargeError = result.message) }
                    else -> {
                        _state.update { it.copy(answeringCharge = null, heldCharge = chargeId) }
                        _events.send(Event.ChargeFailed(failure))
                        refresh(silent = true)
                    }
                }
            }
        }
    }

    private suspend fun refresh(silent: Boolean) {
        when (val result = repository.getOrder(orderId)) {
            is ApiResult.Success -> {
                val detail = result.data
                _state.update { it.copy(detail = detail, loading = false, error = null, notFound = false, heldCharge = null) }
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
        private const val KEY_CLOSED_CHARGES = "closedCharges"
    }
}

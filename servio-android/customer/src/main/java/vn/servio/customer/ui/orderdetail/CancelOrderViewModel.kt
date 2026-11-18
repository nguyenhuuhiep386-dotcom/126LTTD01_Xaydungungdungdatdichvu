package vn.servio.customer.ui.orderdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.CancelPreview
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.repository.OrderRepository
import vn.servio.customer.ui.order.OrderTexts
import java.time.Instant
import javax.inject.Inject

/**
 * CS-20c "Huỷ đơn". Every opening asks #151 again (never a preview from CS-20); the fee shown is the server's.
 * #72 gets the rowVersion of the preview on screen. When the preview expired (60 s) or the order changed (409) the
 * preview is fetched again: a different fee is shown and nothing is sent until the customer taps again.
 */
@HiltViewModel
class CancelOrderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: OrderRepository,
) : ViewModel() {

    val orderId: String = savedStateHandle.get<String>(ARG_ORDER_ID).orEmpty()

    /** Clock of the expiry check; tests move it. */
    internal var now: () -> Instant = Instant::now

    /**
     * [preview] null while loading or after a failed first load ([loadError]). [debtLimit] = maxDebt of #87, null
     * when unknown (the note then names no amount). [reasonIndex] survives rotation and process death.
     * [feeChanged]: the fee on screen was just replaced by a newer one; the customer has to tap again.
     */
    data class State(
        val preview: CancelPreview? = null,
        val loading: Boolean = true,
        val loadError: String? = null,
        val debtLimit: Long? = null,
        val reasonIndex: Int? = null,
        val sending: Boolean = false,
        val sendError: String? = null,
        val feeChanged: Boolean = false,
    ) {
        val ui: CancelDialogUi get() = CancelDialogUi.of(preview, debtLimit, reasonIndex != null, sending, loading)
    }

    sealed interface Event {
        /** Cancelled; [fee] = cancellationFee of #72. */
        data class Cancelled(val fee: Long) : Event

        /** 422 ORDER_NOT_CANCELLABLE (work started) or 409 already cancelled: close and reload CS-20. */
        data object NotCancellable : Event
    }

    private val _state = MutableStateFlow(State(reasonIndex = savedStateHandle.get<Int>(KEY_REASON)))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, loadError = null) }
        loadJob = viewModelScope.launch {
            val limit = async { (repository.cancelDebtLimit() as? ApiResult.Success)?.data }
            when (val result = repository.cancelPreview(orderId)) {
                is ApiResult.Success -> _state.update { it.copy(preview = result.data, loading = false, debtLimit = limit.await()) }
                is ApiResult.Failure -> {
                    limit.cancel()
                    if (isGone(result)) {
                        _events.send(Event.NotCancellable)
                    } else {
                        _state.update { it.copy(loading = false, loadError = result.message) }
                    }
                }
            }
        }
    }

    fun selectReason(index: Int) {
        savedStateHandle[KEY_REASON] = index
        _state.update { it.copy(reasonIndex = index, sendError = null) }
    }

    /** [reason] is the text of the chosen item, sent as shown. */
    fun confirm(reason: String) {
        val current = _state.value
        val shown = current.preview ?: return
        if (current.sending || current.loading || current.reasonIndex == null || reason.isBlank()) return
        _state.update { it.copy(sending = true, sendError = null, feeChanged = false) }
        viewModelScope.launch {
            val preview = if (OrderTexts.isPast(shown.expiresAt, now())) refreshed(shown) ?: return@launch else shown
            send(reason, preview, retryOnConflict = true)
        }
    }

    private suspend fun send(reason: String, preview: CancelPreview, retryOnConflict: Boolean) {
        when (val result = repository.cancel(orderId, reason, preview.rowVersion)) {
            is ApiResult.Success -> {
                _state.update { it.copy(sending = false) }
                _events.send(Event.Cancelled(result.data.cancellationFee))
            }
            is ApiResult.Failure -> when {
                isGone(result) -> {
                    _state.update { it.copy(sending = false) }
                    _events.send(Event.NotCancellable)
                }
                result.code == RESOURCE_VERSION_CONFLICT && retryOnConflict -> {
                    val fresh = refreshed(preview) ?: return
                    send(reason, fresh, retryOnConflict = false)
                }
                else -> _state.update { it.copy(sending = false, sendError = result.message) }
            }
        }
    }

    /**
     * A new #151 in place of [old]. Returns it when the fee is the same (the caller sends with its rowVersion);
     * null when the fee changed (shown, waiting for another tap) or the call failed (message shown).
     */
    private suspend fun refreshed(old: CancelPreview): CancelPreview? =
        when (val result = repository.cancelPreview(orderId)) {
            is ApiResult.Success -> {
                val fresh = result.data
                if (fresh.fee == old.fee) {
                    _state.update { it.copy(preview = fresh) }
                    fresh
                } else {
                    _state.update { it.copy(preview = fresh, sending = false, feeChanged = true) }
                    null
                }
            }
            is ApiResult.Failure -> {
                if (isGone(result)) {
                    _state.update { it.copy(sending = false) }
                    _events.send(Event.NotCancellable)
                } else {
                    _state.update { it.copy(sending = false, sendError = result.message) }
                }
                null
            }
        }

    private fun isGone(failure: ApiResult.Failure) =
        failure.code == ORDER_NOT_CANCELLABLE || failure.code == INVALID_STATUS_TRANSITION

    companion object {
        const val ARG_ORDER_ID = "orderId"
        const val ORDER_NOT_CANCELLABLE = "ORDER_NOT_CANCELLABLE"
        const val INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION"
        const val RESOURCE_VERSION_CONFLICT = "RESOURCE_VERSION_CONFLICT"
        private const val KEY_REASON = "reasonIndex"
    }
}

/** What the CS-20c dialog shows (pure, unit tested). */
data class CancelDialogUi(
    val loading: Boolean,
    /** Fee of #151; null while unknown. */
    val fee: Long?,
    /** Why there is a fee, from assignmentFees; null when free or not said. */
    val feeReason: FeeReason?,
    /** Fee box and the debt sentence: only when there is a fee. */
    val showFee: Boolean,
    /** Amount named in the debt sentence (#87 maxDebt); null → the sentence without a number. */
    val debtLimit: Long?,
    /** "Huỷ đơn, chịu phí X đ" instead of "Huỷ đơn". */
    val confirmWithFee: Boolean,
    val confirmEnabled: Boolean,
) {
    enum class FeeReason { ON_THE_WAY, ARRIVED }

    companion object {
        fun of(preview: CancelPreview?, debtLimit: Long?, reasonChosen: Boolean, sending: Boolean, loading: Boolean): CancelDialogUi {
            val fee = preview?.fee
            val paid = fee != null && fee > 0
            val charged = preview?.assignmentFees.orEmpty().filter { it.fee > 0 }.map { it.status }
            val reason = when {
                !paid -> null
                OrderStatus.ARRIVED in charged -> FeeReason.ARRIVED
                OrderStatus.ON_THE_WAY in charged -> FeeReason.ON_THE_WAY
                else -> null
            }
            return CancelDialogUi(
                loading = loading,
                fee = fee,
                feeReason = reason,
                showFee = paid,
                debtLimit = debtLimit.takeIf { paid },
                confirmWithFee = paid,
                confirmEnabled = preview != null && !loading && reasonChosen && !sending,
            )
        }
    }
}

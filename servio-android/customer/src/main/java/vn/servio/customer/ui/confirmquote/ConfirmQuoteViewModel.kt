package vn.servio.customer.ui.confirmquote

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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.CreatedOrder
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.QuoteSort
import vn.servio.customer.data.model.QuoteStatus
import vn.servio.customer.data.model.ServerConfig
import vn.servio.customer.data.model.ServiceRequestDetail
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.repository.OrderRepository
import vn.servio.customer.data.repository.ServerConfigRepository
import vn.servio.customer.data.repository.ServiceRequestRepository
import javax.inject.Inject

/**
 * CS-18 Xác nhận đặt dịch vụ. Loads the request (#38) and its quotes (#42) itself, so it is right after the app was
 * restored and always sends the rowVersion the customer is looking at. Confirming calls #43 for that quote, in cash.
 * The two policy lines (accept window, cancellation fees) come from #107, asked beside them; they are hidden while it
 * has not answered or when it failed, the rest of the screen does not wait for it.
 */
@HiltViewModel
class ConfirmQuoteViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val requests: ServiceRequestRepository,
    private val orders: OrderRepository,
    private val serverConfig: ServerConfigRepository,
) : ViewModel() {

    val requestId: String = savedStateHandle.get<String>(ARG_REQUEST_ID).orEmpty()
    val quoteId: String = savedStateHandle.get<String>(ARG_QUOTE_ID).orEmpty()

    /**
     * [loaded] once #38 and #42 have both answered; [quote] is then null when #42 no longer lists it (withdrawn,
     * expired, another one chosen). [error] is only for a first load that failed (full-screen with "Thử lại").
     * [dialog] lives here so it survives a rotation; the screen clears it with [onDialogClosed].
     */
    data class State(
        val detail: ServiceRequestDetail? = null,
        val quote: CustomerQuote? = null,
        val loaded: Boolean = false,
        val loading: Boolean = true,
        val error: String? = null,
        val submitting: Boolean = false,
        val dialog: ConfirmFailure.Dialog? = null,
        /** #107; null while unknown (the policy lines are hidden). */
        val policy: ServerConfig? = null,
    ) {
        /** Only a pending quote of an open request can be booked; anything else shows "không còn hiệu lực". */
        val available: Boolean
            get() = loaded && detail?.status == ServiceRequestStatus.OPEN && quote?.status == QuoteStatus.PENDING

        val unavailable: Boolean get() = loaded && !available
    }

    sealed interface Event {
        /** 201: open the order (CS-20). */
        data class OrderCreated(val order: CreatedOrder) : Event

        /** Network or unexpected error of #43: snackbar with "Thử lại" (the same quote again returns the same order). */
        data class Retry(val message: String) : Event

        /** A reload failed while the screen already has content. */
        data class Message(val text: String) : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        if (requestId.isNotBlank() && quoteId.isNotBlank()) load() else _state.update { it.copy(loading = false, loaded = true) }
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch { fetch() }
        if (_state.value.policy == null) viewModelScope.launch { loadPolicy() }
    }

    /** #107 for the policy lines; a failure leaves them hidden (a retry of the screen asks again). */
    private suspend fun loadPolicy() {
        val result = serverConfig.getConfig()
        if (result is ApiResult.Success) _state.update { it.copy(policy = result.data) }
    }

    /** Request and quotes together; what is on screen stays if this fails. */
    private suspend fun fetch() = coroutineScope {
        val detailCall = async { requests.getDetail(requestId) }
        val quotesResult = requests.getQuotes(requestId, QuoteSort.TIME)
        val detailResult = detailCall.await()
        if (detailResult is ApiResult.Success && quotesResult is ApiResult.Success) {
            val quote = quotesResult.data.items.firstOrNull { it.id == quoteId }
            _state.update { it.copy(detail = detailResult.data, quote = quote, loaded = true, loading = false, error = null) }
        } else {
            val failure = detailResult as? ApiResult.Failure ?: quotesResult as ApiResult.Failure
            val hadContent = _state.value.loaded
            _state.update { it.copy(loading = false, error = failure.message.takeUnless { hadContent }) }
            if (hadContent) _events.send(Event.Message(failure.message))
        }
    }

    /** #43. Ignored while a call is running or when the quote can no longer be booked (double tap, stale button). */
    fun confirm() {
        val current = _state.value
        val quote = current.quote
        if (current.submitting || !current.available || quote == null) return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val result = orders.acceptQuote(requestId, quote.id, quote.rowVersion)
            when (result) {
                is ApiResult.Success -> {
                    _state.update { it.copy(submitting = false) }
                    _events.send(Event.OrderCreated(result.data))
                }
                is ApiResult.Failure -> onFailure(ConfirmFailure.of(result.code, result.message))
            }
        }
    }

    private suspend fun onFailure(failure: ConfirmFailure) {
        when (failure) {
            is ConfirmFailure.Changed -> {
                // Reload first: if the quote is still bookable the customer sees the new price, otherwise the screen
                // turns into "không còn hiệu lực" and no dialog is needed.
                loadJob?.cancel()
                _state.update { it.copy(loading = true) }
                fetch()
                _state.update { state -> state.copy(submitting = false, dialog = if (state.available) failure.dialog else null) }
            }
            is ConfirmFailure.Leave -> _state.update { it.copy(submitting = false, dialog = failure.dialog) }
            is ConfirmFailure.Retry -> {
                _state.update { it.copy(submitting = false) }
                _events.send(Event.Retry(failure.message))
            }
        }
    }

    fun onDialogClosed() {
        _state.update { it.copy(dialog = null) }
    }

    companion object {
        const val ARG_REQUEST_ID = "requestId"
        const val ARG_QUOTE_ID = "quoteId"
    }
}

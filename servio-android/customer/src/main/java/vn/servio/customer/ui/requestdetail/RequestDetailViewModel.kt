package vn.servio.customer.ui.requestdetail

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
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.QuoteSort
import vn.servio.customer.data.model.QuoteStatus
import vn.servio.customer.data.model.ServiceRequestDetail
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.repository.ServiceRequestRepository
import javax.inject.Inject

/**
 * CS-15 (request + quotes) and CS-16 (one quote, read from [State.quotes]). Scoped to the cs15 back stack entry so
 * the CS-16 sheet shares it.
 *
 * No SignalR client yet: quotes are reloaded over REST when the screen is opened, returned to, pulled down or
 * re-sorted. NewQuote / QuoteWithdrawn only have to call [reloadQuotes] later.
 */
@HiltViewModel
class RequestDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ServiceRequestRepository,
) : ViewModel() {

    val requestId: String = savedStateHandle.get<String>(ARG_REQUEST_ID).orEmpty()

    /**
     * [detail] and [quotes] are null until their first successful load. [error] is set only while one of them is
     * still missing (full-screen error); a failed reload with content on screen is an [Event.Message] instead.
     * [sort] is the order of [quotes] as the server returned them.
     */
    data class State(
        val detail: ServiceRequestDetail? = null,
        val quotes: List<CustomerQuote>? = null,
        val sort: QuoteSort = QuoteSort.TIME,
        val loading: Boolean = true,
        val error: String? = null,
        val cancelling: Boolean = false,
    ) {
        val hasContent: Boolean get() = detail != null && quotes != null

        /** Only a pending quote of an open request can be chosen (an accepted one just shows its status). */
        fun canSelect(quote: CustomerQuote): Boolean =
            detail?.status == ServiceRequestStatus.OPEN && quote.status == QuoteStatus.PENDING
    }

    sealed interface Event {
        data class Message(val text: String) : Event
        data object Cancelled : Event

        /**
         * #40 answered 409: the request is no longer OPEN/DRAFT (expired, matched, cancelled elsewhere). The screen
         * has its own wording, as the shared text for this code speaks of an order.
         */
        data object CancelConflict : Event

        /** The quote shown by CS-16 is not in the list (any more): the sheet has closed itself. */
        data object QuoteGone : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var quotesJob: Job? = null

    /** Request and quotes together. What is already on screen stays there if this fails. */
    fun load() {
        loadJob?.cancel()
        quotesJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            val detailCall = async { repository.getDetail(requestId) }
            val quotesResult = repository.getQuotes(requestId, _state.value.sort)
            val detailResult = detailCall.await()
            val failure = detailResult as? ApiResult.Failure ?: quotesResult as? ApiResult.Failure
            _state.update { s ->
                val detail = (detailResult as? ApiResult.Success)?.data ?: s.detail
                val quotes = (quotesResult as? ApiResult.Success)?.data?.items ?: s.quotes
                s.copy(
                    detail = detail,
                    quotes = quotes,
                    loading = false,
                    error = failure?.message.takeIf { detail == null || quotes == null },
                )
            }
            if (failure != null && _state.value.hasContent) _events.send(Event.Message(failure.message))
        }
    }

    /** Quotes only (#42), in [sort] order. On failure the list and its order stay as they are. */
    fun reloadQuotes(sort: QuoteSort = _state.value.sort) {
        if (loadJob?.isActive == true) {
            // A full load is running: let it fetch the quotes in the new order.
            _state.update { it.copy(sort = sort) }
            load()
            return
        }
        quotesJob?.cancel()
        _state.update { it.copy(loading = true) }
        quotesJob = viewModelScope.launch {
            when (val result = repository.getQuotes(requestId, sort)) {
                is ApiResult.Success -> _state.update { it.copy(quotes = result.data.items, sort = sort, loading = false) }
                is ApiResult.Failure -> {
                    _state.update { it.copy(loading = false) }
                    _events.send(Event.Message(result.message))
                }
            }
        }
    }

    fun setSort(sort: QuoteSort) {
        if (sort != _state.value.sort) reloadQuotes(sort)
    }

    /** #40. Reloads afterwards either way: a 409 means the status changed under us (expired, matched). */
    fun cancel(reason: String?) {
        if (_state.value.cancelling) return
        _state.update { it.copy(cancelling = true) }
        viewModelScope.launch {
            val result = repository.cancel(requestId, reason)
            _state.update { it.copy(cancelling = false) }
            _events.send(
                when (result) {
                    is ApiResult.Success -> Event.Cancelled
                    is ApiResult.Failure ->
                        if (result.code == INVALID_STATUS_TRANSITION) Event.CancelConflict else Event.Message(result.message)
                },
            )
            load()
        }
    }

    fun onQuoteGone() {
        _events.trySend(Event.QuoteGone)
    }

    companion object {
        const val ARG_REQUEST_ID = "requestId"
        const val INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION"
    }
}

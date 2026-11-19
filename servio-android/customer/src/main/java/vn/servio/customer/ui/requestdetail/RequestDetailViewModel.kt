package vn.servio.customer.ui.requestdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.data.repository.CategoryRepository
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.QuoteSort
import vn.servio.customer.data.model.QuoteStatus
import vn.servio.customer.data.model.ServiceRequestDetail
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.repository.PartnerRepository
import vn.servio.customer.data.repository.QuoteUpdates
import vn.servio.customer.data.repository.ServiceRequestRepository
import javax.inject.Inject

/**
 * CS-15 (request + quotes) and CS-16 (one quote, read from [State.quotes]). Scoped to the cs15 back stack entry so
 * the CS-16 sheet shares it.
 *
 * Quotes are reloaded over REST when the screen is opened, returned to, pulled down or re-sorted, and live: NewQuote,
 * QuoteWithdrawn of this request and hub reconnects ([QuoteUpdates]) reload #42 in the current sort, coalesced
 * (see [refreshFromHub]). A withdrawn quote that CS-16 shows then leaves the list, so the sheet closes with
 * [Event.QuoteGone] as before.
 */
@HiltViewModel
class RequestDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ServiceRequestRepository,
    private val partnerRepository: PartnerRepository,
    private val categoryRepository: CategoryRepository,
    private val quoteUpdates: QuoteUpdates,
) : ViewModel() {

    val requestId: String = savedStateHandle.get<String>(ARG_REQUEST_ID).orEmpty()

    /**
     * Pending live reload. Conflated: any number of events while one is waiting or running leaves exactly one more.
     */
    private val hubSignals = Channel<Unit>(Channel.CONFLATED)

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
        /** Name of the level-1 group of the request's category; null until (or unless) the category tree answers. */
        val categoryGroup: String? = null,
        /** Approved skill names by partner id (#27), for the CS-16 chips. A partner is absent until its call succeeds. */
        val partnerSkills: Map<String, List<String>> = emptyMap(),
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
    private val loadingPartners = mutableSetOf<String>()

    init {
        if (requestId.isNotEmpty()) {
            // The hub is shared with other screens: it is not stopped when this ViewModel goes away.
            viewModelScope.launch { quoteUpdates.changes(requestId).collect { hubSignals.trySend(Unit) } }
            viewModelScope.launch {
                for (signal in hubSignals) {
                    // Events within the window are answered by the same call.
                    delay(LIVE_RELOAD_WINDOW_MILLIS)
                    hubSignals.tryReceive()
                    refreshFromHub()
                }
            }
        }
    }

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
            loadCategoryGroup()
        }
    }

    /** Best effort: #38 only names the level-2 category, the group comes from the (cached) category tree. */
    private suspend fun loadCategoryGroup() {
        val categoryId = _state.value.detail?.categoryId?.takeIf { it.isNotEmpty() } ?: return
        if (_state.value.categoryGroup != null) return
        val tree = (categoryRepository.getCategoryTree() as? ApiResult.Success)?.data ?: return
        val group = tree.firstOrNull { group -> group.children.any { it.id == categoryId } } ?: return
        _state.update { it.copy(categoryGroup = group.name) }
    }

    /**
     * Skills of the partner shown by CS-16 (#42 does not carry them). Silent on failure: the sheet just has no chips,
     * and opening it again retries.
     */
    fun loadPartnerSkills(partnerId: String) {
        if (partnerId in _state.value.partnerSkills || !loadingPartners.add(partnerId)) return
        viewModelScope.launch {
            val result = partnerRepository.getPublicProfile(partnerId)
            loadingPartners.remove(partnerId)
            if (result is ApiResult.Success) {
                _state.update { it.copy(partnerSkills = it.partnerSkills + (partnerId to result.data.skills)) }
            }
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

    /**
     * One live reload of #42, never two at once (the loop above waits for it; an event during the call queues one
     * more). Silent: no spinner, and a failure keeps the list (the next event or reconnect tries again). Without a
     * first successful load it runs the full [load] instead. A result is dropped if the customer re-sorted or another
     * reload started meanwhile, since that one answers with fresher data.
     */
    private suspend fun refreshFromHub() {
        loadJob?.takeIf { it.isActive }?.join()
        if (!_state.value.hasContent) {
            load()
            loadJob?.join()
            return
        }
        val sort = _state.value.sort
        val result = repository.getQuotes(requestId, sort)
        if (result is ApiResult.Success && _state.value.sort == sort && quotesJob?.isActive != true && loadJob?.isActive != true) {
            _state.update { it.copy(quotes = result.data.items) }
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

        /** Live events closer together than this give one #42 call. */
        const val LIVE_RELOAD_WINDOW_MILLIS = 500L
    }
}

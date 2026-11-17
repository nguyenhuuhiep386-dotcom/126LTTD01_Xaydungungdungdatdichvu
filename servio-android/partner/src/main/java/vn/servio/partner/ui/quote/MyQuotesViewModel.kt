package vn.servio.partner.ui.quote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import vn.servio.core.network.ApiResult
import vn.servio.core.realtime.Realtime
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.partner.data.model.PartnerQuote
import vn.servio.partner.data.model.QuoteStatus
import vn.servio.partner.data.repository.FeedRepository
import javax.inject.Inject

/** PS-12 Báo giá của tôi (#53) with withdraw (#52). Reloads when the customer picks a quote (QuoteAccepted/Rejected). */
@HiltViewModel
class MyQuotesViewModel @Inject constructor(
    private val repository: FeedRepository,
    realtime: Realtime,
) : ViewModel() {

    enum class Tab(val statuses: Set<QuoteStatus>) {
        PENDING(setOf(QuoteStatus.PENDING)),
        ACCEPTED(setOf(QuoteStatus.ACCEPTED)),
        CLOSED(setOf(QuoteStatus.REJECTED, QuoteStatus.WITHDRAWN, QuoteStatus.EXPIRED)),
    }

    sealed interface Event {
        data class Message(val text: String) : Event
        data object Withdrawn : Event
    }

    private val _state = MutableStateFlow<UiState<List<PartnerQuote>>>(UiState.Loading)
    val state: StateFlow<UiState<List<PartnerQuote>>> = _state.asStateFlow()

    private val _tab = MutableStateFlow(Tab.PENDING)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        load()
        realtime.orders.start()
        viewModelScope.launch {
            merge(
                realtime.orders.listen(EVENT_ACCEPTED, JsonObject.serializer()),
                realtime.orders.listen(EVENT_REJECTED, JsonObject.serializer()),
                realtime.orders.reconnected,
            ).collect { load(showSpinner = false) }
        }
    }

    fun load(showSpinner: Boolean = true) {
        if (showSpinner) _state.value = UiState.Loading
        viewModelScope.launch { _state.value = repository.listMyQuotes(status = null).toUiState() }
    }

    fun selectTab(tab: Tab) {
        _tab.value = tab
    }

    fun withdraw(quoteId: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = repository.withdrawQuote(quoteId)
            _busy.value = false
            when (result) {
                is ApiResult.Success -> {
                    _events.send(Event.Withdrawn)
                    load(showSpinner = false)
                }
                is ApiResult.Failure -> _events.send(Event.Message(result.message))
            }
        }
    }

    private companion object {
        const val EVENT_ACCEPTED = "QuoteAccepted"
        const val EVENT_REJECTED = "QuoteRejected"
    }
}

package vn.servio.partner.ui.quote

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
import kotlinx.coroutines.launch
import vn.servio.core.data.repository.ChatRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.partner.data.model.PartnerRequestView
import vn.servio.partner.data.repository.FeedRepository
import vn.servio.partner.data.repository.PartnerProfileRepository
import javax.inject.Inject

/** PS-10 Chi tiết bài đăng (#38, partner view), withdrawing the own quote (#52) and "Nhắn tin" (#56). */
@HiltViewModel
class RequestDetailViewModel @Inject constructor(
    private val repository: FeedRepository,
    private val chatRepository: ChatRepository,
    private val profileRepository: PartnerProfileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val requestId: String = checkNotNull(savedStateHandle[RequestDetailFragment.ARG_REQUEST_ID])

    sealed interface Event {
        data class Message(val text: String) : Event
        data object Withdrawn : Event
        data class OpenChat(val conversationId: String) : Event
    }

    private val _state = MutableStateFlow<UiState<PartnerRequestView>>(UiState.Loading)
    val state: StateFlow<UiState<PartnerRequestView>> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** "Báo giá" on a feed card opens the sheet once, right after the detail has loaded. */
    var quoteSheetOpened = false

    init {
        load()
    }

    fun load(showSpinner: Boolean = true) {
        if (showSpinner) _state.value = UiState.Loading
        viewModelScope.launch { _state.value = repository.getRequest(requestId).toUiState() }
    }

    /** Opens (or creates) the conversation with the customer about this post; allowed before quoting. */
    fun openChat() {
        val request = (_state.value as? UiState.Content)?.data ?: return
        request.ownQuote?.conversationId?.let {
            _events.trySend(Event.OpenChat(it))
            return
        }
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val me = profileRepository.getMe()
            val result = when (me) {
                is ApiResult.Success -> chatRepository.open(requestId, me.data.id)
                is ApiResult.Failure -> me
            }
            _busy.value = false
            _events.send(
                when (result) {
                    is ApiResult.Success -> Event.OpenChat(result.data.id)
                    is ApiResult.Failure -> Event.Message(result.message)
                },
            )
        }
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
}

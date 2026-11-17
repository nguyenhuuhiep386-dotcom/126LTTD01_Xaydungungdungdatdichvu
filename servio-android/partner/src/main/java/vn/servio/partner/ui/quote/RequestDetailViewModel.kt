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
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.partner.data.model.PartnerRequestView
import vn.servio.partner.data.repository.FeedRepository
import javax.inject.Inject

/** PS-10 Chi tiết bài đăng (#38, partner view) and withdrawing the own quote (#52). */
@HiltViewModel
class RequestDetailViewModel @Inject constructor(
    private val repository: FeedRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val requestId: String = checkNotNull(savedStateHandle[RequestDetailFragment.ARG_REQUEST_ID])

    sealed interface Event {
        data class Message(val text: String) : Event
        data object Withdrawn : Event
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

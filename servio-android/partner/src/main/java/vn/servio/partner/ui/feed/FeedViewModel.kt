package vn.servio.partner.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.partner.data.location.DeviceLocation
import vn.servio.partner.data.model.FeedFilter
import vn.servio.partner.data.model.FeedItem
import vn.servio.partner.data.repository.FeedFilterStore
import vn.servio.partner.data.repository.FeedRepository
import vn.servio.partner.data.repository.PartnerProfileRepository
import vn.servio.partner.data.repository.PresenceRepository
import javax.inject.Inject

/** PS-07 Bảng tin: #46 list, NewPost pushed live while Online, Online switch (#25/#26), filter from PS-08. */
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: FeedRepository,
    private val presence: PresenceRepository,
    private val profileRepository: PartnerProfileRepository,
    private val deviceLocation: DeviceLocation,
    private val filterStore: FeedFilterStore,
) : ViewModel() {

    sealed interface Event {
        data class Message(val text: String) : Event
        data object LocationUnavailable : Event
    }

    private val _state = MutableStateFlow<UiState<List<FeedItem>>>(UiState.Loading)
    val state: StateFlow<UiState<List<FeedItem>>> = _state.asStateFlow()

    /** Posts that arrived live since the user last looked at the top of the list ("2 yêu cầu mới"). */
    private val _newCount = MutableStateFlow(0)
    val newCount: StateFlow<Int> = _newCount.asStateFlow()

    val isOnline: StateFlow<Boolean> = presence.isOnline
    val filter: StateFlow<FeedFilter> = filterStore.filter

    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        restorePresence()
        load()
        viewModelScope.launch { repository.newPosts().collect(::onNewPost) }
        viewModelScope.launch { repository.reconnected().collect { load(showSpinner = false) } }
        viewModelScope.launch { filterStore.filter.drop(1).collect { load() } }
    }

    fun load(showSpinner: Boolean = true) {
        if (showSpinner) _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = repository.getFeed(filterStore.filter.value).toUiState()
            _newCount.value = 0
        }
    }

    fun clearNewCount() {
        _newCount.value = 0
    }

    /** Call only after the location permission was granted (the Fragment asks for it). */
    fun setOnline(online: Boolean) {
        if (_switching.value || online == presence.isOnline.value) return
        _switching.value = true
        viewModelScope.launch {
            val result = if (online) {
                val fix = deviceLocation.current()
                if (fix == null) {
                    _switching.value = false
                    _events.send(Event.LocationUnavailable)
                    return@launch
                }
                presence.goOnline(fix)
            } else {
                presence.goOffline()
            }
            _switching.value = false
            if (result is ApiResult.Failure) _events.send(Event.Message(result.message))
            if (online && result is ApiResult.Success) load(showSpinner = false)
        }
    }

    private fun restorePresence() {
        viewModelScope.launch {
            val me = profileRepository.getMe()
            if (me is ApiResult.Success) presence.restore(me.data.isOnline)
        }
    }

    private fun onNewPost(item: FeedItem) {
        val filter = filterStore.filter.value
        if (filter.categoryIds.isNotEmpty() && item.categoryId !in filter.categoryIds) return
        if (filter.maxDistanceKm != null && item.distanceKm > filter.maxDistanceKm) return
        var added = false
        _state.update { state ->
            if (state !is UiState.Content || state.data.any { it.requestId == item.requestId }) {
                state
            } else {
                added = true
                UiState.Content(listOf(item) + state.data)
            }
        }
        if (added) _newCount.update { it + 1 }
    }
}

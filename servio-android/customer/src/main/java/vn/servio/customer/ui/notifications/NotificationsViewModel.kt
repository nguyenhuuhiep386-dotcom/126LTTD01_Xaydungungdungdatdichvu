package vn.servio.customer.ui.notifications

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
import vn.servio.customer.data.model.AppNotification
import vn.servio.customer.data.repository.NotificationRepository
import javax.inject.Inject

/**
 * CS-31 Thông báo (#101–#104). Paged like CS-19 (20 a page, rows deduplicated by id). Opening the screen marks
 * nothing; a tap marks one read (#102, not awaited), "Đọc hết" marks all (#103, the rows change once it succeeded).
 * [State.unread] is #104 at load, then followed locally, so "Đọc hết" also knows about unread rows of later pages.
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationRepository,
) : ViewModel() {

    /** [error] only when the first page failed (the list is empty then). [unread] null until #104 answered. */
    data class State(
        val items: List<AppNotification> = emptyList(),
        val totalCount: Int? = null,
        val page: Int = 0,
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val unread: Int? = null,
        val readingAll: Boolean = false,
    ) {
        val hasMore: Boolean get() = totalCount != null && items.size < totalCount
        val canReadAll: Boolean get() = !readingAll && ((unread ?: 0) > 0 || items.any { !it.isRead })
    }

    sealed interface Event {
        data object ReadAllFailed : Event
        data object RefreshFailed : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var firstJob: Job? = null
    private var moreJob: Job? = null

    init {
        load()
    }

    /** First load and "Thử lại": spinner in place of the list. */
    fun load() = loadFirstPage(pull = false)

    /** Pull to refresh: the list stays while loading; a failure keeps it and shows a snackbar. */
    fun refresh() = loadFirstPage(pull = true)

    private fun loadFirstPage(pull: Boolean) {
        firstJob?.cancel()
        moreJob?.cancel()
        val keepRows = pull && _state.value.error == null && _state.value.items.isNotEmpty()
        _state.update {
            if (keepRows) it.copy(refreshing = true, loadingMore = false)
            else it.copy(loading = true, refreshing = pull, loadingMore = false, error = null)
        }
        firstJob = viewModelScope.launch {
            val unreadCall = async { repository.unreadCount() }
            val result = repository.list(page = 1)
            val unread = (unreadCall.await() as? ApiResult.Success)?.data?.total
            _state.update { s ->
                when (result) {
                    is ApiResult.Success -> s.copy(
                        items = result.data.items.distinctBy(AppNotification::id),
                        totalCount = result.data.totalCount,
                        page = 1,
                        loading = false,
                        refreshing = false,
                        error = null,
                        unread = unread ?: s.unread,
                    )
                    is ApiResult.Failure ->
                        if (keepRows) s.copy(refreshing = false, unread = unread ?: s.unread)
                        else s.copy(items = emptyList(), totalCount = null, page = 0, loading = false, refreshing = false, error = result.message)
                }
            }
            if (result is ApiResult.Failure && keepRows) _events.send(Event.RefreshFailed)
        }
    }

    /** Next page when the list is scrolled to its end. A failure is silent: scrolling to the end again retries. */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.refreshing || current.loadingMore || !current.hasMore) return
        _state.update { it.copy(loadingMore = true) }
        moreJob = viewModelScope.launch {
            val page = current.page + 1
            when (val result = repository.list(page)) {
                is ApiResult.Success -> _state.update {
                    // A notification arriving meanwhile shifts the pages by one, so the same row can come twice.
                    val merged = (it.items + result.data.items).distinctBy(AppNotification::id)
                    val total = if (result.data.items.isEmpty()) merged.size else result.data.totalCount
                    it.copy(items = merged, totalCount = total, page = page, loadingMore = false)
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** Tap on a row: read at once on screen, #102 in the background (its result is not waited for nor shown). */
    fun markRead(notification: AppNotification) {
        val wasUnread = _state.value.items.firstOrNull { it.id == notification.id }?.isRead == false
        if (!wasUnread) return
        _state.update { s ->
            s.copy(
                items = s.items.map { if (it.id == notification.id) it.copy(isRead = true) else it },
                unread = s.unread?.let { (it - 1).coerceAtLeast(0) },
            )
        }
        viewModelScope.launch { repository.read(notification.id) }
    }

    /** "Đọc hết", no confirmation. The rows turn read only after #103 succeeded; a failure is a snackbar. */
    fun readAll() {
        if (!_state.value.canReadAll) return
        _state.update { it.copy(readingAll = true) }
        viewModelScope.launch {
            when (repository.readAll()) {
                is ApiResult.Success -> _state.update { s ->
                    s.copy(items = s.items.map { it.copy(isRead = true) }, unread = 0, readingAll = false)
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(readingAll = false) }
                    _events.send(Event.ReadAllFailed)
                }
            }
        }
    }
}

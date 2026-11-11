package vn.servio.customer.ui.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.data.repository.ServiceRequestRepository
import javax.inject.Inject

/**
 * CS-14. Each tab is its own #37 query (status filter on the server), so every tab has its own list, page and
 * totalCount. [refresh] reloads the first page of all three tabs, which also gives the numbers on the tabs.
 */
@HiltViewModel
class MyRequestsViewModel @Inject constructor(
    private val repository: ServiceRequestRepository,
) : ViewModel() {

    /** Tabs of CS-14 (spec 7.2): Đang mở / Đã ghép / Lịch sử. DRAFT (waiting for moderation) stays with the open ones. */
    enum class Tab(val statuses: Set<ServiceRequestStatus>) {
        OPEN(setOf(ServiceRequestStatus.OPEN, ServiceRequestStatus.DRAFT)),
        MATCHED(setOf(ServiceRequestStatus.MATCHED)),
        HISTORY(setOf(ServiceRequestStatus.EXPIRED, ServiceRequestStatus.CANCELLED, ServiceRequestStatus.REJECTED_BY_MODERATION)),
    }

    /**
     * [totalCount] is null until the server has answered (the tab then shows no number).
     * [error] is set only when the first page failed; the list is empty in that case.
     */
    data class TabState(
        val items: List<ServiceRequestSummary> = emptyList(),
        val totalCount: Int? = null,
        val page: Int = 0,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
    ) {
        val hasMore: Boolean get() = totalCount != null && items.size < totalCount
    }

    data class State(
        val selected: Tab = Tab.OPEN,
        val tabs: Map<Tab, TabState> = Tab.entries.associateWith { TabState() },
    ) {
        val current: TabState get() = tabs.getValue(selected)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private val moreJobs = mutableMapOf<Tab, Job>()

    /** First page of every tab. Lists already on screen stay visible until the answer arrives. */
    fun refresh() {
        refreshJob?.cancel()
        moreJobs.values.forEach { it.cancel() }
        moreJobs.clear()
        _state.update { s -> s.copy(tabs = s.tabs.mapValues { it.value.copy(loading = true, loadingMore = false, error = null) }) }
        refreshJob = viewModelScope.launch {
            Tab.entries.forEach { tab ->
                launch {
                    val next = when (val result = repository.getMyRequests(tab.statuses, page = 1)) {
                        is ApiResult.Success ->
                            TabState(items = result.data.items, totalCount = result.data.totalCount, page = 1, loading = false)
                        is ApiResult.Failure -> TabState(loading = false, error = result.message)
                    }
                    updateTab(tab) { next }
                }
            }
        }
    }

    /** Next page of the selected tab. A failure is silent: scrolling to the end again retries. */
    fun loadMore() {
        val tab = _state.value.selected
        val current = _state.value.current
        if (current.loading || current.loadingMore || !current.hasMore) return
        updateTab(tab) { it.copy(loadingMore = true) }
        moreJobs[tab] = viewModelScope.launch {
            val page = current.page + 1
            when (val result = repository.getMyRequests(tab.statuses, page)) {
                is ApiResult.Success -> updateTab(tab) {
                    // A request posted meanwhile shifts the pages by one, so the same row can come twice.
                    val merged = (it.items + result.data.items).distinctBy(ServiceRequestSummary::id)
                    val total = if (result.data.items.isEmpty()) merged.size else result.data.totalCount
                    it.copy(items = merged, totalCount = total, page = page, loadingMore = false)
                }
                is ApiResult.Failure -> updateTab(tab) { it.copy(loadingMore = false) }
            }
        }
    }

    fun selectTab(selected: Tab) {
        _state.update { it.copy(selected = selected) }
    }

    private fun updateTab(tab: Tab, change: (TabState) -> TabState) {
        _state.update { s -> s.copy(tabs = s.tabs + (tab to change(s.tabs.getValue(tab)))) }
    }
}

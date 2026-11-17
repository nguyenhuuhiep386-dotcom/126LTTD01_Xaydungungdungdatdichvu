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
import vn.servio.core.data.repository.CategoryRepository
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.data.repository.ServiceRequestRepository
import javax.inject.Inject

/**
 * CS-14. Each tab is its own #37 query (status filter on the server), so every tab has its own list, page and
 * totalCount. Only "Đang mở" shows a number (Figma), so [refresh] loads that tab and the one on screen; the others
 * load when they are opened.
 */
@HiltViewModel
class MyRequestsViewModel @Inject constructor(
    private val repository: ServiceRequestRepository,
    private val categories: CategoryRepository,
) : ViewModel() {

    /** Tabs of CS-14 (spec 7.2): Đang mở / Đã ghép / Lịch sử. DRAFT (waiting for moderation) stays with the open ones. */
    enum class Tab(val statuses: Set<ServiceRequestStatus>) {
        OPEN(setOf(ServiceRequestStatus.OPEN, ServiceRequestStatus.DRAFT)),
        MATCHED(setOf(ServiceRequestStatus.MATCHED)),
        HISTORY(setOf(ServiceRequestStatus.EXPIRED, ServiceRequestStatus.CANCELLED, ServiceRequestStatus.REJECTED_BY_MODERATION)),
    }

    /**
     * [totalCount] is null until the server has answered. [fetched] is false for a tab not asked for since the last
     * refresh: it loads when selected. [error] is set only when the first page failed; the list is empty then.
     */
    data class TabState(
        val items: List<ServiceRequestSummary> = emptyList(),
        val totalCount: Int? = null,
        val page: Int = 0,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val fetched: Boolean = false,
    ) {
        val hasMore: Boolean get() = totalCount != null && items.size < totalCount
    }

    /** [categoryGroups]: level-2 category id → level-1 group name, empty until (or unless) the tree answers. */
    data class State(
        val selected: Tab = Tab.OPEN,
        val tabs: Map<Tab, TabState> = Tab.entries.associateWith { TabState() },
        val categoryGroups: Map<String, String> = emptyMap(),
    ) {
        val current: TabState get() = tabs.getValue(selected)

        val cards: List<RequestCard> get() = current.items.map { RequestCard(it, categoryGroups[it.categoryId]) }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val firstJobs = mutableMapOf<Tab, Job>()
    private val moreJobs = mutableMapOf<Tab, Job>()

    /**
     * First page of "Đang mở" (its number) and of the tab on screen. Lists already on screen stay visible until the
     * answer arrives; the other tabs are emptied and reload when opened.
     */
    fun refresh() {
        (firstJobs.values + moreJobs.values).forEach { it.cancel() }
        firstJobs.clear()
        moreJobs.clear()
        val wanted = setOf(Tab.OPEN, _state.value.selected)
        _state.update { s ->
            s.copy(tabs = s.tabs.mapValues { (tab, old) -> if (tab in wanted) old.copy(loading = true, loadingMore = false, error = null) else TabState() })
        }
        wanted.forEach(::loadFirstPage)
        loadCategoryGroups()
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
        if (!_state.value.tabs.getValue(selected).fetched && firstJobs[selected]?.isActive != true) loadFirstPage(selected)
    }

    private fun loadFirstPage(tab: Tab) {
        firstJobs[tab] = viewModelScope.launch {
            val next = when (val result = repository.getMyRequests(tab.statuses, page = 1)) {
                is ApiResult.Success ->
                    TabState(items = result.data.items, totalCount = result.data.totalCount, page = 1, loading = false, fetched = true)
                is ApiResult.Failure -> TabState(loading = false, error = result.message, fetched = true)
            }
            updateTab(tab) { next }
        }
    }

    /** Best effort, as on CS-15: the tree is cached for the session, a failure leaves the level-2 names. */
    private fun loadCategoryGroups() {
        if (_state.value.categoryGroups.isNotEmpty()) return
        viewModelScope.launch {
            val tree = (categories.getCategoryTree() as? ApiResult.Success)?.data ?: return@launch
            val groups = tree.flatMap { group -> group.children.map { it.id to group.name } }.toMap()
            _state.update { it.copy(categoryGroups = groups) }
        }
    }

    private fun updateTab(tab: Tab, change: (TabState) -> TabState) {
        _state.update { s -> s.copy(tabs = s.tabs + (tab to change(s.tabs.getValue(tab)))) }
    }
}

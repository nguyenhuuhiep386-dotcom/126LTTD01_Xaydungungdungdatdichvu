package vn.servio.customer.ui.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.customer.data.model.OrderListFilter
import vn.servio.customer.data.model.OrderSummary
import vn.servio.customer.data.repository.OrderRepository
import vn.servio.customer.data.repository.OrderUpdates
import vn.servio.customer.data.repository.reloadCoalesced
import javax.inject.Inject

/**
 * CS-19 Đơn hàng, paged like CS-14: each tab is its own #62 query, with its own list, page and totalCount. Only
 * "Đang diễn ra" shows its number (Figma), so [refresh] loads that tab and the one on screen. Any OrderStatusChanged
 * (or hub reconnect) reloads both silently, coalesced.
 */
@HiltViewModel
class MyOrdersViewModel @Inject constructor(
    private val repository: OrderRepository,
    private val orderUpdates: OrderUpdates,
) : ViewModel() {

    /**
     * [totalCount] is null until the server has answered (no "(0)" before that). [fetched] is false for a tab not
     * asked for since the last refresh. [error] is set only when the first page failed; the list is empty then.
     */
    data class TabState(
        val items: List<OrderSummary> = emptyList(),
        val totalCount: Int? = null,
        val page: Int = 0,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val fetched: Boolean = false,
    ) {
        val hasMore: Boolean get() = totalCount != null && items.size < totalCount
    }

    data class State(
        val selected: OrderListFilter = OrderListFilter.ACTIVE,
        val tabs: Map<OrderListFilter, TabState> = OrderListFilter.entries.associateWith { TabState() },
    ) {
        val current: TabState get() = tabs.getValue(selected)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val firstJobs = mutableMapOf<OrderListFilter, Job>()
    private val moreJobs = mutableMapOf<OrderListFilter, Job>()

    init {
        viewModelScope.launch { orderUpdates.changes().map { }.reloadCoalesced { reloadLive() } }
    }

    /** First page of "Đang diễn ra" (its number) and of the tab on screen; the other tabs reload when opened. */
    fun refresh() {
        (firstJobs.values + moreJobs.values).forEach { it.cancel() }
        firstJobs.clear()
        moreJobs.clear()
        val wanted = wantedTabs()
        _state.update { s ->
            s.copy(tabs = s.tabs.mapValues { (tab, old) -> if (tab in wanted) old.copy(loading = true, loadingMore = false, error = null) else TabState() })
        }
        wanted.forEach(::loadFirstPage)
    }

    /** Next page of the selected tab. A failure is silent: scrolling to the end again retries. */
    fun loadMore() {
        val tab = _state.value.selected
        val current = _state.value.current
        if (current.loading || current.loadingMore || !current.hasMore) return
        updateTab(tab) { it.copy(loadingMore = true) }
        moreJobs[tab] = viewModelScope.launch {
            val page = current.page + 1
            when (val result = repository.getMyOrders(tab, page)) {
                is ApiResult.Success -> updateTab(tab) {
                    // An order created meanwhile shifts the pages by one, so the same row can come twice.
                    val merged = (it.items + result.data.items).distinctBy(OrderSummary::id)
                    val total = if (result.data.items.isEmpty()) merged.size else result.data.totalCount
                    it.copy(items = merged, totalCount = total, page = page, loadingMore = false)
                }
                is ApiResult.Failure -> updateTab(tab) { it.copy(loadingMore = false) }
            }
        }
    }

    fun selectTab(selected: OrderListFilter) {
        _state.update { it.copy(selected = selected) }
        if (!_state.value.tabs.getValue(selected).fetched && firstJobs[selected]?.isActive != true) loadFirstPage(selected)
    }

    private fun wantedTabs() = setOf(OrderListFilter.ACTIVE, _state.value.selected)

    private fun loadFirstPage(tab: OrderListFilter) {
        firstJobs[tab] = viewModelScope.launch {
            val next = when (val result = repository.getMyOrders(tab, page = 1)) {
                is ApiResult.Success ->
                    TabState(items = result.data.items, totalCount = result.data.totalCount, page = 1, loading = false, fetched = true)
                is ApiResult.Failure -> TabState(loading = false, error = result.message, fetched = true)
            }
            updateTab(tab) { next }
        }
    }

    /**
     * Live: first page of the same tabs, without a spinner. A failure keeps what is shown; a tab that is loading
     * anyway is left to that call. Rows of later pages are dropped (the order of the list may have changed).
     */
    private suspend fun reloadLive() {
        for (tab in wantedTabs()) {
            if (firstJobs[tab]?.isActive == true || moreJobs[tab]?.isActive == true) continue
            val result = repository.getMyOrders(tab, page = 1) as? ApiResult.Success ?: continue
            updateTab(tab) { TabState(items = result.data.items, totalCount = result.data.totalCount, page = 1, loading = false, fetched = true) }
        }
    }

    private fun updateTab(tab: OrderListFilter, change: (TabState) -> TabState) {
        _state.update { s -> s.copy(tabs = s.tabs + (tab to change(s.tabs.getValue(tab)))) }
    }
}

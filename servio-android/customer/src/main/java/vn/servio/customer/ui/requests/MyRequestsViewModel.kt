package vn.servio.customer.ui.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.model.ServiceRequestSummary
import vn.servio.customer.data.repository.ServiceRequestRepository
import javax.inject.Inject

@HiltViewModel
class MyRequestsViewModel @Inject constructor(
    private val repository: ServiceRequestRepository,
) : ViewModel() {

    /** Tabs of CS-14 (spec 7.2): Đang mở / Đã ghép / Lịch sử. */
    enum class Tab(val statuses: Set<ServiceRequestStatus>) {
        OPEN(setOf(ServiceRequestStatus.OPEN)),
        MATCHED(setOf(ServiceRequestStatus.MATCHED)),
        HISTORY(setOf(ServiceRequestStatus.EXPIRED, ServiceRequestStatus.CANCELLED, ServiceRequestStatus.REJECTED_BY_MODERATION)),
    }

    private val all = MutableStateFlow<UiState<List<ServiceRequestSummary>>>(UiState.Loading)
    private val tab = MutableStateFlow(Tab.OPEN)

    val state: StateFlow<UiState<List<ServiceRequestSummary>>> =
        combine(all, tab) { requests, selected ->
            when (requests) {
                is UiState.Content -> UiState.Content(requests.data.filter { it.status in selected.statuses })
                else -> requests
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    val selectedTab: StateFlow<Tab> = tab.asStateFlow()

    init {
        load()
    }

    fun load() {
        all.value = UiState.Loading
        viewModelScope.launch { all.value = repository.getMyRequests().toUiState() }
    }

    fun selectTab(selected: Tab) {
        tab.value = selected
    }
}

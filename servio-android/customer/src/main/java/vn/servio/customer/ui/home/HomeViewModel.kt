package vn.servio.customer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import vn.servio.core.data.model.Category
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.data.repository.CategoryRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.customer.data.repository.NotificationRepository
import vn.servio.customer.data.repository.OrdersHub
import vn.servio.customer.data.repository.reloadCoalesced
import javax.inject.Inject

/**
 * REFERENCE PATTERN (real API): the ViewModel calls repositories in parallel and exposes one StateFlow.
 * CS-06 full spec (order tracking button, open requests) is work package KH-2.
 *
 * KH-7: [unread] is the bell badge (#104). It is asked again when the screen is shown ([refreshUnread], also on
 * return from CS-31), on pull to refresh, and when the orders hub brings an event that comes with a notification
 * (NewQuote, OrderStatusChanged, ExtraChargeRequested, ArrivalConfirmationRequested) or reconnects. A failure keeps
 * the number shown.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val categoryRepository: CategoryRepository,
    private val notificationRepository: NotificationRepository,
    private val ordersHub: OrdersHub,
) : ViewModel() {

    data class HomeContent(val firstName: String, val categories: List<Category>)

    private val _state = MutableStateFlow<UiState<HomeContent>>(UiState.Loading)
    val state: StateFlow<UiState<HomeContent>> = _state.asStateFlow()

    /** Unread notifications of the customer app; null until #104 first answered (badge hidden). */
    private val _unread = MutableStateFlow<Int?>(null)
    val unread: StateFlow<Int?> = _unread.asStateFlow()

    private var unreadJob: Job? = null

    init {
        load()
        viewModelScope.launch { liveSignals().reloadCoalesced { reloadAfterEvent() } }
        ordersHub.start()
    }

    fun load() {
        _state.value = UiState.Loading
        refreshUnread()
        viewModelScope.launch {
            val me = async { authRepository.getMe() }
            val categories = async { categoryRepository.getCategoryTree() }
            val categoriesResult = categories.await()
            _state.value = when (categoriesResult) {
                is ApiResult.Failure -> UiState.Error(categoriesResult.message)
                is ApiResult.Success -> {
                    val fullName = (me.await() as? ApiResult.Success)?.data?.fullName.orEmpty()
                    UiState.Content(HomeContent(fullName.trim().substringAfterLast(' '), categoriesResult.data))
                }
            }
        }
    }

    /** #104 now; a call still running is replaced. */
    fun refreshUnread() {
        unreadJob?.cancel()
        unreadJob = viewModelScope.launch { fetchUnread() }
    }

    /** Returns the new number, or null when #104 failed (the shown number is kept). */
    private suspend fun fetchUnread(): Int? {
        val total = (notificationRepository.unreadCount() as? ApiResult.Success)?.data?.total ?: return null
        _unread.value = total
        return total
    }

    /**
     * The hub event can leave the server just before its notification is written (QuoteService sends NewQuote, then
     * the inbox row), so when the number has not moved it is asked once more a little later.
     */
    private suspend fun reloadAfterEvent() {
        val before = _unread.value
        val after = fetchUnread()
        if (after != null && after == before) {
            delay(SECOND_TRY_MILLIS)
            fetchUnread()
        }
    }

    private fun liveSignals(): Flow<Unit> = merge(
        *LIVE_EVENTS.map { name -> ordersHub.events(name, JsonObject.serializer()).map { } }.toTypedArray(),
        ordersHub.connects(),
    )

    private companion object {
        val LIVE_EVENTS = listOf(
            OrdersHub.EVENT_NEW_QUOTE,
            OrdersHub.EVENT_ORDER_STATUS_CHANGED,
            OrdersHub.EVENT_EXTRA_CHARGE_REQUESTED,
            OrdersHub.EVENT_ARRIVAL_CONFIRMATION_REQUESTED,
        )
        const val SECOND_TRY_MILLIS = 1_500L
    }
}

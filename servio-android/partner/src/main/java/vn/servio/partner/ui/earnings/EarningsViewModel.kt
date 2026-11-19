package vn.servio.partner.ui.earnings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.partner.data.model.FilePurpose
import vn.servio.partner.data.model.OrderStatus
import vn.servio.partner.data.model.Wallet
import vn.servio.partner.data.model.WalletTransaction
import vn.servio.partner.data.repository.AccountRepository
import vn.servio.partner.data.repository.FileRepository
import vn.servio.partner.data.repository.OrderRepository
import vn.servio.partner.ui.common.PhotoUploads
import vn.servio.partner.ui.common.Texts
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/**
 * PS-21 Thu nhập & công nợ: earnings of completed orders (#62), commission debt and its 200.000 đ limit (#87),
 * ledger (#88), and PS-21a "Khai báo trả nợ" (#156 with a transfer photo, POST /files DISPUTE_EVIDENCE).
 */
@HiltViewModel
class EarningsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val orderRepository: OrderRepository,
    fileRepository: FileRepository,
) : ViewModel() {

    data class Content(
        val wallet: Wallet,
        val transactions: List<WalletTransaction>,
        val todayEarning: Long,
        val todayOrders: Int,
        val weekEarning: Long,
        val pendingDeclared: Boolean,
    )

    sealed interface Event {
        data class Message(val text: String) : Event
        data object Declared : Event
    }

    val evidence = PhotoUploads(viewModelScope, fileRepository, FilePurpose.DISPUTE_EVIDENCE, max = 1)

    private val _state = MutableStateFlow<UiState<Content>>(UiState.Loading)
    val state: StateFlow<UiState<Content>> = _state.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        load()
    }

    fun load(showSpinner: Boolean = true) {
        if (showSpinner) _state.value = UiState.Loading
        viewModelScope.launch {
            val wallet = async { accountRepository.wallet() }
            val ledger = async { accountRepository.transactions() }
            val debts = async { accountRepository.debts() }
            val orders = async { orderRepository.list(OrderRepository.ListFilter.COMPLETED) }
            val walletResult = wallet.await()
            if (walletResult is ApiResult.Failure) {
                _state.value = UiState.Error(walletResult.message)
                return@launch
            }
            val completed = (orders.await() as? ApiResult.Success)?.data.orEmpty()
                .filter { it.status == OrderStatus.COMPLETED }
            val today = LocalDate.now()
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            fun dayOf(iso: String) = Texts.parse(iso)?.toLocalDate()
            val todays = completed.filter { dayOf(it.updatedAt) == today }
            val week = completed.filter { dayOf(it.updatedAt)?.let { d -> !d.isBefore(weekStart) } == true }
            _state.value = UiState.Content(
                Content(
                    wallet = (walletResult as ApiResult.Success).data,
                    transactions = (ledger.await() as? ApiResult.Success)?.data.orEmpty(),
                    todayEarning = todays.sumOf { it.earning ?: 0 },
                    todayOrders = todays.size,
                    weekEarning = week.sumOf { it.earning ?: 0 },
                    pendingDeclared = (debts.await() as? ApiResult.Success)?.data.orEmpty().any { it.pendingTransactionCode != null },
                ),
            )
        }
    }

    fun addEvidence(uri: Uri) = evidence.add(uri)

    fun declare(reference: String) {
        val url = evidence.urls.firstOrNull()
        if (_sending.value || evidence.isUploading || url == null || reference.trim().length < MIN_REFERENCE) return
        _sending.value = true
        viewModelScope.launch {
            val result = accountRepository.declarePayment(reference.trim(), url)
            _sending.value = false
            when (result) {
                is ApiResult.Success -> {
                    evidence.remove(evidence.photos.value.first().localUri)
                    _events.send(Event.Declared)
                    load(showSpinner = false)
                }
                is ApiResult.Failure -> _events.send(Event.Message(result.message))
            }
        }
    }

    companion object {
        const val MIN_REFERENCE = 3
    }
}

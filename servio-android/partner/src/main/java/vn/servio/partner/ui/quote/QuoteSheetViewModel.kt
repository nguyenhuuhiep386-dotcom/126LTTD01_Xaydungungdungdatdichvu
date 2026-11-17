package vn.servio.partner.ui.quote

import androidx.lifecycle.SavedStateHandle
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
import vn.servio.partner.data.model.PartnerRequestView
import vn.servio.partner.data.model.QuoteStatus
import vn.servio.partner.data.model.ScheduleType
import vn.servio.partner.data.model.SubmitQuoteRequest
import vn.servio.partner.data.repository.FeedRepository
import vn.servio.partner.ui.common.Texts
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** PS-11 Gửi báo giá (#50): price, arrival date/time inside the post's window, duration, note. */
@HiltViewModel
class QuoteSheetViewModel @Inject constructor(
    private val repository: FeedRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val requestId: String = checkNotNull(savedStateHandle[RequestDetailFragment.ARG_REQUEST_ID])

    data class Content(val request: PartnerRequestView, val pendingQuotes: Int)

    enum class FieldError { AMOUNT, TIME }

    sealed interface Event {
        data object Sent : Event
        data class Error(val message: String) : Event
    }

    private val _state = MutableStateFlow<UiState<Content>>(UiState.Loading)
    val state: StateFlow<UiState<Content>> = _state.asStateFlow()

    /** Arrival time; defaults to the start of the post's window, or 30 minutes from now for "càng sớm càng tốt". */
    private val _arrival = MutableStateFlow(ZonedDateTime.now().plusMinutes(30).truncatedTo(ChronoUnit.MINUTES))
    val arrival: StateFlow<ZonedDateTime> = _arrival.asStateFlow()

    private val _fieldErrors = MutableStateFlow<Set<FieldError>>(emptySet())
    val fieldErrors: StateFlow<Set<FieldError>> = _fieldErrors.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    var durationMinutes: Int = DEFAULT_DURATION
    var isFormFilled = false

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val request = async { repository.getRequest(requestId) }
            val pending = async { repository.listMyQuotes(QuoteStatus.PENDING) }
            _state.value = when (val result = request.await()) {
                is ApiResult.Failure -> UiState.Error(result.message)
                is ApiResult.Success -> {
                    val data = result.data
                    if (data.scheduleType == ScheduleType.SCHEDULED) {
                        Texts.parse(data.scheduledStartAt)?.let { _arrival.value = it }
                    }
                    val count = (pending.await() as? ApiResult.Success)?.data?.size ?: 0
                    UiState.Content(Content(data, count))
                }
            }
        }
    }

    fun setArrival(value: ZonedDateTime) {
        _arrival.value = value.truncatedTo(ChronoUnit.MINUTES)
    }

    fun submit(amountText: String, note: String) {
        val content = (_state.value as? UiState.Content)?.data ?: return
        val amount = amountText.filter(Char::isDigit).toLongOrNull()
        val errors = buildSet {
            if (amount == null || amount <= 0) add(FieldError.AMOUNT)
            if (_arrival.value.isBefore(ZonedDateTime.now())) add(FieldError.TIME)
        }
        _fieldErrors.value = errors
        if (errors.isNotEmpty() || amount == null || _sending.value) return
        _sending.value = true
        viewModelScope.launch {
            val request = SubmitQuoteRequest(
                amount = amount,
                estimatedDurationMinutes = durationMinutes,
                availableFrom = Texts.iso(_arrival.value),
                note = note.trim().ifEmpty { null },
                requestRevision = content.request.revision,
            )
            val result = repository.submitQuote(requestId, request)
            _sending.value = false
            _events.send(
                when (result) {
                    is ApiResult.Success -> Event.Sent
                    is ApiResult.Failure -> Event.Error(result.message)
                },
            )
        }
    }

    companion object {
        const val DEFAULT_DURATION = 60
        val DURATIONS = listOf(30, 60, 90, 120, 180)
    }
}

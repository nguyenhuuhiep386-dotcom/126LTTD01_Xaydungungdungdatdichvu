package vn.servio.customer.ui.partner

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.customer.data.model.PartnerPublic
import vn.servio.customer.data.model.PartnerReview
import vn.servio.customer.data.model.QuoteSort
import vn.servio.customer.data.model.QuoteStatus
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.data.repository.PartnerRepository
import vn.servio.customer.data.repository.ServiceRequestRepository
import javax.inject.Inject

/**
 * CS-17 Hồ sơ đối tác: profile (#27) and the newest [PartnerTexts.REVIEW_PAGE_SIZE] reviews (#28, one page).
 *
 * Opened from CS-16 with [ARG_REQUEST_ID] and [ARG_QUOTE_ID] when that quote could be chosen: the screen then reads
 * the request (#38) and its quotes (#42) itself and shows the "Chọn báo giá này" bar only while the quote is still
 * PENDING, the request OPEN and the quote belongs to this partner. If that check fails the bar stays hidden; the
 * profile is not blocked by it.
 */
@HiltViewModel
class PartnerProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val partners: PartnerRepository,
    private val requests: ServiceRequestRepository,
) : ViewModel() {

    val partnerId: String = savedStateHandle.get<String>(ARG_PARTNER_ID).orEmpty()
    val requestId: String = savedStateHandle.get<String>(ARG_REQUEST_ID).orEmpty()
    val quoteId: String = savedStateHandle.get<String>(ARG_QUOTE_ID).orEmpty()

    /** The quote that can still be chosen from this screen. */
    data class Booking(val requestCode: String, val amount: Long)

    /**
     * [notFound]: #27 answered 404, the same for "no such partner" and "not related to you" (the server does not tell
     * them apart). [error]: any other failure of #27 (full screen with "Thử lại"). [reviews] is null until #28 has
     * answered; [reviewsError] only hides the list, the profile stays.
     */
    data class State(
        val profile: PartnerPublic? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val notFound: Boolean = false,
        val reviews: List<PartnerReview>? = null,
        val reviewsLoading: Boolean = false,
        val reviewsError: String? = null,
        val booking: Booking? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var reviewsJob: Job? = null

    init {
        if (partnerId.isBlank()) _state.update { it.copy(loading = false, notFound = true) } else load()
    }

    /** Profile, reviews and the bottom bar together. Also "Thử lại" of the full-screen error. */
    fun load() {
        loadJob?.cancel()
        reviewsJob?.cancel()
        _state.update { it.copy(loading = true, error = null, notFound = false, reviewsLoading = true, reviewsError = null) }
        loadJob = viewModelScope.launch {
            coroutineScope {
                val booking = async { fetchBooking() }
                val reviews = async { partners.getReviews(partnerId, 1, PartnerTexts.REVIEW_PAGE_SIZE) }
                when (val profile = partners.getPublicProfile(partnerId)) {
                    is ApiResult.Success -> {
                        _state.update { it.copy(profile = profile.data, loading = false) }
                        applyReviews(reviews.await())
                        _state.update { it.copy(booking = booking.await()) }
                    }
                    is ApiResult.Failure -> {
                        reviews.cancel()
                        booking.cancel()
                        _state.update {
                            it.copy(
                                loading = false,
                                reviewsLoading = false,
                                notFound = profile.code == NOT_FOUND,
                                error = profile.message.takeUnless { profile.code == NOT_FOUND },
                            )
                        }
                    }
                }
            }
        }
    }

    /** "Thử lại" under "Đánh giá gần đây" when only #28 failed. */
    fun loadReviews() {
        if (_state.value.profile == null || reviewsJob?.isActive == true) return
        _state.update { it.copy(reviewsLoading = true, reviewsError = null) }
        reviewsJob = viewModelScope.launch { applyReviews(partners.getReviews(partnerId, 1, PartnerTexts.REVIEW_PAGE_SIZE)) }
    }

    private fun applyReviews(result: ApiResult<PagedResult<PartnerReview>>) {
        _state.update {
            when (result) {
                is ApiResult.Success -> it.copy(reviews = result.data.items, reviewsLoading = false, reviewsError = null)
                is ApiResult.Failure -> it.copy(reviewsLoading = false, reviewsError = result.message)
            }
        }
    }

    /** Null (no bar) unless the quote is still bookable; a failed check also means no bar. */
    private suspend fun fetchBooking(): Booking? {
        if (requestId.isBlank() || quoteId.isBlank()) return null
        return coroutineScope {
            val detailCall = async { requests.getDetail(requestId) }
            val quotes = requests.getQuotes(requestId, QuoteSort.TIME)
            val detail = detailCall.await()
            if (detail !is ApiResult.Success || quotes !is ApiResult.Success) return@coroutineScope null
            val quote = quotes.data.items.firstOrNull { it.id == quoteId } ?: return@coroutineScope null
            val bookable = detail.data.status == ServiceRequestStatus.OPEN && quote.status == QuoteStatus.PENDING &&
                quote.partner.id.equals(partnerId, ignoreCase = true)
            if (bookable) Booking(detail.data.code, quote.amount) else null
        }
    }

    companion object {
        const val ARG_PARTNER_ID = "partnerId"
        const val ARG_REQUEST_ID = "requestId"
        const val ARG_QUOTE_ID = "quoteId"
        const val NOT_FOUND = "NOT_FOUND"
    }
}

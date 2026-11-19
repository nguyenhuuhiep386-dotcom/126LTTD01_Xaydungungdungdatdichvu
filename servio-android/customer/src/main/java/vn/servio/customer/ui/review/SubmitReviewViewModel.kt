package vn.servio.customer.ui.review

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
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
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.PendingReview
import vn.servio.customer.data.repository.OrderRepository
import vn.servio.customer.data.repository.ReviewRepository
import vn.servio.customer.ui.review.ReviewForm.Criterion
import javax.inject.Inject

/**
 * CS-26 Đánh giá đối tác for one assignment: #63 (partner, code, title; optional, #100 has the name and code too) and
 * #100 (is it still open, until when). The stars and the comment are kept in [SavedStateHandle], so a rotation (or the
 * app being restored) keeps them. #95 sends the comment trimmed (null when empty), no tags, no photos.
 */
@HiltViewModel
class SubmitReviewViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val orderRepository: OrderRepository,
    private val reviewRepository: ReviewRepository,
) : ViewModel() {

    val orderId: String = savedStateHandle.get<String>(ARG_ORDER_ID).orEmpty()
    val assignmentId: String = savedStateHandle.get<String>(ARG_ASSIGNMENT_ID).orEmpty()

    /**
     * [pending] null while #100 has not answered; [closed]: #100 does not have this assignment (already reviewed, past
     * the deadline) or #95 said the window closed: the form is hidden. [error]: #100 failed (full-screen retry).
     */
    data class State(
        val detail: OrderDetail? = null,
        val pending: PendingReview? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val closed: Boolean = false,
        val rating: Int = 0,
        val criteria: Map<Criterion, Int> = emptyMap(),
        val comment: String = "",
        val sending: Boolean = false,
    ) {
        val canSubmit: Boolean get() = !closed && pending != null && ReviewForm.canSubmit(rating, sending)
    }

    sealed interface Event {
        /** Leave with this message for the screen below ("Đã gửi đánh giá", "Bạn đã đánh giá đơn này"). */
        data class Leave(@StringRes val message: Int) : Event

        /** A snackbar on CS-26: a text of the app ([res]) or the server's [text]. */
        data class Message(@StringRes val res: Int? = null, val text: String? = null) : Event
    }

    private val _state = MutableStateFlow(
        State(
            rating = savedStateHandle[KEY_RATING] ?: 0,
            criteria = Criterion.entries.associateWith { savedStateHandle.get<Int>(KEY_CRITERION + it.name) ?: 0 }.filterValues { it > 0 },
            comment = savedStateHandle[KEY_COMMENT] ?: "",
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            val order = async { orderRepository.getOrder(orderId) }
            val pending = reviewRepository.pendingReviews()
            val detail = (order.await() as? ApiResult.Success)?.data
            when (pending) {
                is ApiResult.Success -> {
                    val row = ReviewForm.pendingFor(pending.data, orderId, assignmentId)
                    _state.update { it.copy(detail = detail ?: it.detail, pending = row, closed = row == null, loading = false, error = null) }
                }
                is ApiResult.Failure -> _state.update { it.copy(detail = detail ?: it.detail, loading = false, error = pending.message) }
            }
        }
    }

    fun setRating(rating: Int) {
        savedStateHandle[KEY_RATING] = rating
        _state.update { it.copy(rating = rating) }
    }

    fun setCriterion(criterion: Criterion, rating: Int) {
        savedStateHandle[KEY_CRITERION + criterion.name] = rating
        _state.update { it.copy(criteria = it.criteria + (criterion to rating)) }
    }

    fun setComment(text: String) {
        if (text == _state.value.comment) return
        savedStateHandle[KEY_COMMENT] = text
        _state.update { it.copy(comment = text) }
    }

    /** #95; ignored until a star is chosen, while it runs, or once the form is closed. */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            val body = ReviewForm.body(assignmentId, current.rating, current.criteria, current.comment)
            when (val result = reviewRepository.submit(orderId, body)) {
                is ApiResult.Success -> _events.send(Event.Leave(R.string.review_sent))
                is ApiResult.Failure -> {
                    _state.update { it.copy(sending = false) }
                    when (ReviewForm.failure(result.code)) {
                        ReviewForm.Failure.WINDOW_CLOSED -> {
                            _state.update { it.copy(closed = true) }
                            _events.send(Event.Message(res = R.string.review_window_closed))
                        }
                        ReviewForm.Failure.ALREADY_SUBMITTED -> _events.send(Event.Leave(R.string.review_already))
                        ReviewForm.Failure.OTHER -> _events.send(Event.Message(text = result.message))
                    }
                }
            }
        }
    }

    companion object {
        const val ARG_ORDER_ID = "orderId"
        const val ARG_ASSIGNMENT_ID = "assignmentId"
        private const val KEY_RATING = "rating"
        private const val KEY_CRITERION = "criterion_"
        private const val KEY_COMMENT = "comment"

        fun args(orderId: String, assignmentId: String): Bundle = bundleOf(ARG_ORDER_ID to orderId, ARG_ASSIGNMENT_ID to assignmentId)
    }
}

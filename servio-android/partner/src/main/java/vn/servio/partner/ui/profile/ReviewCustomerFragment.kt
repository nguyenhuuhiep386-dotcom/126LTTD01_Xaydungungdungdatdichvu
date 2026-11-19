package vn.servio.partner.ui.profile

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.core.utils.toUiState
import vn.servio.partner.R
import vn.servio.partner.data.model.CreateReviewRequest
import vn.servio.partner.data.model.OrderDetail
import vn.servio.partner.data.repository.AccountRepository
import vn.servio.partner.data.repository.OrderRepository
import vn.servio.partner.databinding.FragmentReviewCustomerBinding
import vn.servio.partner.ui.order.OrderDetailFragment
import javax.inject.Inject

@HiltViewModel
class ReviewCustomerViewModel @Inject constructor(
    private val orderRepository: OrderRepository,
    private val accountRepository: AccountRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val orderId: String = checkNotNull(savedStateHandle[OrderDetailFragment.ARG_ORDER_ID])

    private val _state = MutableStateFlow<UiState<OrderDetail>>(UiState.Loading)
    val state: StateFlow<UiState<OrderDetail>> = _state.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** null = sent, otherwise an error message. */
    private val _results = Channel<String?>(Channel.BUFFERED)
    val results: Flow<String?> = _results.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch { _state.value = orderRepository.get(orderId).toUiState() }
    }

    fun submit(rating: Int, tags: List<String>, comment: String) {
        val assignmentId = (_state.value as? UiState.Content)?.data?.assignment?.id ?: return
        if (_sending.value || rating !in 1..5) return
        _sending.value = true
        viewModelScope.launch {
            val request = CreateReviewRequest(assignmentId, rating, comment.trim().ifEmpty { null }, tags.ifEmpty { null })
            val result = accountRepository.reviewCustomer(orderId, request)
            _sending.value = false
            _results.send((result as? ApiResult.Failure)?.message)
        }
    }
}

/** PS-27 Đánh giá khách hàng (#95): stars, quick tags, comment; within 5 days of completion. */
@AndroidEntryPoint
class ReviewCustomerFragment : Fragment(R.layout.fragment_review_customer) {

    private val viewModel: ReviewCustomerViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentReviewCustomerBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        TAGS.forEach { (code, label) ->
            val chip = layoutInflater.inflate(R.layout.item_filter_chip, binding.tagChips, false) as Chip
            chip.text = getString(label)
            chip.tag = code
            binding.tagChips.addView(chip)
        }
        binding.ratingBar.setOnRatingBarChangeListener { _, rating, _ ->
            binding.sendButton.isEnabled = rating >= 1 && !viewModel.sending.value
        }
        binding.sendButton.isEnabled = false
        binding.sendButton.setOnClickListener {
            val tags = (0 until binding.tagChips.childCount)
                .map { binding.tagChips.getChildAt(it) as Chip }
                .filter { it.isChecked }
                .map { it.tag as String }
            viewModel.submit(binding.ratingBar.rating.toInt(), tags, binding.commentInput.text?.toString().orEmpty())
        }

        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isVisible = state is UiState.Loading
            binding.errorText.isVisible = state is UiState.Error
            binding.content.isVisible = state is UiState.Content
            if (state is UiState.Error) binding.errorText.text = state.message
            if (state is UiState.Content) {
                binding.customer.text = getString(R.string.review_customer_line, state.data.customer.displayName, state.data.code)
            }
        }
        collectWhenStarted(viewModel.sending) { sending ->
            binding.sendButton.isEnabled = !sending && binding.ratingBar.rating >= 1
        }
        collectWhenStarted(viewModel.results) { error ->
            if (error == null) {
                Snackbar.make(view, R.string.review_sent, Snackbar.LENGTH_LONG).show()
                // Back to the order list when we came from there, otherwise just one step back.
                if (!findNavController().popBackStack(R.id.ps13, false)) findNavController().navigateUp()
            } else {
                Snackbar.make(view, error, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        val TAGS = listOf(
            "PAID_ON_TIME" to R.string.review_tag_paid,
            "ACCURATE_DESCRIPTION" to R.string.review_tag_accurate,
            "FRIENDLY" to R.string.review_tag_friendly,
        )
    }
}

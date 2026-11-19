package vn.servio.customer.ui.review

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.databinding.FragmentSubmitReviewBinding
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.ui.order.OrderRoutes
import vn.servio.customer.ui.order.OrderTexts
import vn.servio.customer.ui.requestdetail.QuoteFormat
import vn.servio.customer.ui.requestdetail.QuoteTexts
import vn.servio.customer.ui.review.ReviewForm.Criterion
import javax.inject.Inject

/**
 * CS-26 Đánh giá đối tác (after CS-23, or "Đánh giá đối tác" on a completed CS-20). Back leaves without sending; a
 * sent review goes back with "Đã gửi đánh giá" shown by the screen below ([OrderRoutes]).
 */
@AndroidEntryPoint
class SubmitReviewFragment : Fragment(R.layout.fragment_submit_review) {

    @Inject lateinit var httpClient: OkHttpClient

    private val viewModel: SubmitReviewViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSubmitReviewBinding.bind(view)
        val images = RemoteImages(httpClient, viewLifecycleOwner.lifecycleScope, (RemoteImages.IMAGE_EDGE_DP * resources.displayMetrics.density).toInt())
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.submitButton.setOnClickListener { viewModel.submit() }

        binding.overallStars.setStarSize(StarRatingView.LARGE_DP)
        binding.overallStars.onRatingChanged = viewModel::setRating
        val criteria = mapOf(
            Criterion.PUNCTUALITY to binding.punctualityStars,
            Criterion.QUALITY to binding.qualityStars,
            Criterion.ATTITUDE to binding.attitudeStars,
            Criterion.PRICE to binding.priceStars,
        )
        criteria.forEach { (criterion, stars) -> stars.onRatingChanged = { viewModel.setCriterion(criterion, it) } }

        // The view model keeps the comment; the field is only filled from it when they differ (restored, first show).
        val saved = viewModel.state.value.comment
        if (binding.commentInput.text?.toString() != saved) binding.commentInput.setText(saved)
        binding.commentInput.doAfterTextChanged { viewModel.setComment(it?.toString().orEmpty()) }

        var shownAvatar: String? = null
        collectWhenStarted(viewModel.state) { state ->
            val ready = state.pending != null || state.closed
            binding.progress.isVisible = state.loading && !ready
            binding.errorGroup.isVisible = !ready && state.error != null
            binding.errorText.text = state.error
            binding.content.isVisible = ready
            binding.bottomBar.isVisible = ready && !state.closed

            val partner = state.detail?.assignments?.firstOrNull { it.id.equals(viewModel.assignmentId, ignoreCase = true) }?.partner
            val name = partner?.displayName?.takeIf { it.isNotBlank() } ?: state.pending?.revieweeName.orEmpty()
            binding.partnerName.text = name
            binding.avatarInitials.text = QuoteTexts.initials(name)
            if (partner?.avatarUrl != shownAvatar) {
                shownAvatar = partner?.avatarUrl
                images.load(shownAvatar, binding.avatarImage)
            }
            val code = state.detail?.code ?: state.pending?.orderCode
            binding.orderText.text = listOfNotNull(
                code?.takeIf { it.isNotBlank() }?.let { getString(R.string.review_order, it) },
                state.detail?.title?.takeIf { it.isNotBlank() },
            ).joinToString(getString(R.string.order_step_separator))

            binding.closedText.isVisible = state.closed
            binding.form.isVisible = !state.closed
            binding.overallStars.rating = state.rating
            binding.ratingLabel.setText(ReviewForm.ratingLabel(state.rating))
            criteria.forEach { (criterion, stars) -> stars.rating = state.criteria[criterion] ?: 0 }
            val deadline = OrderTexts.moment(state.pending?.deadlineAt, QuoteFormat.weekdays(requireContext()))
            binding.deadlineText.isVisible = deadline != null
            binding.deadlineText.text = deadline?.let { getString(R.string.review_deadline, it) }
            binding.submitButton.isEnabled = state.canSubmit
            binding.commentInput.isEnabled = !state.sending
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is SubmitReviewViewModel.Event.Leave -> {
                    if (findNavController().currentDestination?.id == R.id.cs26) OrderRoutes.leaveWith(this, event.message)
                }
                is SubmitReviewViewModel.Event.Message -> {
                    val text = event.res?.let(::getString) ?: event.text
                    text?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
                }
            }
        }

        if (savedInstanceState == null || (viewModel.state.value.pending == null && !viewModel.state.value.closed)) viewModel.load()
    }
}

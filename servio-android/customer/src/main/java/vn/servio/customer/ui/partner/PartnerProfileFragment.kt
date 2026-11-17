package vn.servio.customer.ui.partner

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.PartnerPublic
import vn.servio.customer.data.model.PartnerReview
import vn.servio.customer.databinding.FragmentPartnerProfileBinding
import vn.servio.customer.databinding.ItemPartnerReviewBinding
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.ui.common.showSkills
import vn.servio.customer.ui.confirmquote.ConfirmQuoteFragment
import vn.servio.customer.ui.partner.PartnerProfileViewModel.State
import vn.servio.customer.ui.requestdetail.QuoteFormat
import vn.servio.customer.ui.requestdetail.QuoteTexts
import javax.inject.Inject

/**
 * CS-17 Hồ sơ đối tác. Opened by "Xem hồ sơ đầy đủ" on CS-16 (which closes itself), so Back returns to CS-15.
 * "Chọn báo giá này" opens CS-18 with the same requestId and quoteId as "Chọn" on CS-15/CS-16.
 */
@AndroidEntryPoint
class PartnerProfileFragment : Fragment(R.layout.fragment_partner_profile) {

    @Inject lateinit var httpClient: OkHttpClient

    private val viewModel: PartnerProfileViewModel by viewModels()

    /** What the views show now, so a state change that does not touch them does not rebuild them. */
    private var shownSkills: List<String>? = null
    private var shownReviews: List<PartnerReview>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentPartnerProfileBinding.bind(view)
        val images = RemoteImages(httpClient, viewLifecycleOwner.lifecycleScope, (RemoteImages.IMAGE_EDGE_DP * resources.displayMetrics.density).toInt())
        shownSkills = null
        shownReviews = null

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.notFoundBackButton.setOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.reviewsRetryButton.setOnClickListener { viewModel.loadReviews() }
        binding.selectButton.setOnClickListener { openConfirm() }

        collectWhenStarted(viewModel.state) { state -> binding.bind(state, images) }
    }

    private fun FragmentPartnerProfileBinding.bind(state: State, images: RemoteImages) {
        val profile = state.profile
        progress.isVisible = state.loading && profile == null
        errorGroup.isVisible = state.error != null && !state.loading
        errorText.text = state.error
        notFoundGroup.isVisible = state.notFound && !state.loading
        content.isVisible = profile != null && state.error == null && !state.notFound
        val booking = state.booking
        bottomBar.isVisible = content.isVisible && booking != null
        if (booking != null) {
            bookingLabel.text = getString(R.string.partner_profile_quote_for, booking.requestCode)
            bookingAmount.text = QuoteFormat.money(requireContext(), booking.amount)
        }
        if (profile == null) return
        bindHeader(profile, images)
        bindReviews(state)
    }

    private fun FragmentPartnerProfileBinding.bindHeader(profile: PartnerPublic, images: RemoteImages) {
        avatarInitials.text = QuoteTexts.initials(profile.fullName)
        // Loading again would restart a download that is still running, so only when the url changes.
        if (avatarImage.getTag(R.id.avatarImage) != profile.avatarUrl) {
            avatarImage.setTag(R.id.avatarImage, profile.avatarUrl)
            images.load(profile.avatarUrl, avatarImage)
        }
        nameText.text = profile.fullName

        // Same "4,5" as the quote card (CS-15), the sheet (CS-16) and CS-18; no review → "–" / "Chưa có đánh giá", never 0,0.
        val score = QuoteTexts.rating(profile.averageRating, profile.totalReviews)
        ratingValue.text = score ?: getString(R.string.partner_profile_no_rating)
        ratingLabel.text = if (score != null) {
            getString(R.string.partner_profile_review_count, profile.totalReviews)
        } else {
            getString(R.string.partner_profile_no_rating_label)
        }
        completedValue.text = profile.completedOrders.toString()
        experienceValue.text = when (val experience = PartnerTexts.experience(profile.yearsOfExperience)) {
            is PartnerTexts.Experience.Years -> getString(R.string.partner_profile_years, experience.years)
            PartnerTexts.Experience.LessThanOneYear -> getString(R.string.partner_profile_years_new)
        }

        val bio = profile.bio?.trim().orEmpty()
        bioText.isVisible = bio.isNotEmpty()
        bioText.text = bio
        skillGroup.isVisible = profile.skills.isNotEmpty()
        if (profile.skills != shownSkills) {
            shownSkills = profile.skills
            skillGroup.showSkills(profile.skills)
        }
        aboutSection.isVisible = bio.isNotEmpty() || profile.skills.isNotEmpty()
    }

    private fun FragmentPartnerProfileBinding.bindReviews(state: State) {
        val reviews = state.reviews
        reviewsProgress.isVisible = state.reviewsLoading && reviews == null
        reviewsRetryButton.isVisible = state.reviewsError != null && reviews == null
        reviewsMessage.isVisible = if (reviews == null) state.reviewsError != null else reviews.isEmpty()
        reviewsMessage.text = if (reviews == null) state.reviewsError else getString(R.string.partner_profile_no_reviews)
        if (reviews == null || reviews == shownReviews) return
        shownReviews = reviews
        reviewList.removeAllViews()
        val inflater = LayoutInflater.from(reviewList.context)
        reviews.forEach { review -> ItemPartnerReviewBinding.inflate(inflater, reviewList, true).bind(review) }
    }

    private fun ItemPartnerReviewBinding.bind(review: PartnerReview) {
        val context = root.context
        avatar.text = QuoteTexts.initials(review.reviewerName)
        name.text = review.reviewerName
        date.text = PartnerTexts.reviewDate(review.createdAt).orEmpty()
        val filled = PartnerTexts.filledStars(review.rating)
        val on = ColorStateList.valueOf(context.getColor(vn.servio.core.R.color.servio_text_accent))
        val off = ColorStateList.valueOf(context.getColor(vn.servio.core.R.color.servio_divider))
        stars.children.filterIsInstance<ImageView>().forEachIndexed { index, star ->
            ImageViewCompat.setImageTintList(star, if (index < filled) on else off)
        }
        stars.contentDescription = getString(R.string.partner_profile_stars, filled)
        val text = review.comment?.trim().orEmpty()
        comment.isVisible = text.isNotEmpty()
        comment.text = text
    }

    private fun openConfirm() {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs17 || viewModel.state.value.booking == null) return
        navController.navigate(R.id.cs18, ConfirmQuoteFragment.args(viewModel.requestId, viewModel.quoteId))
    }

    companion object {
        /** Arguments of cs17. requestId and quoteId only when the quote can be chosen (bottom bar). */
        fun args(partnerId: String, requestId: String? = null, quoteId: String? = null): Bundle = bundleOf(
            PartnerProfileViewModel.ARG_PARTNER_ID to partnerId,
            PartnerProfileViewModel.ARG_REQUEST_ID to requestId.orEmpty(),
            PartnerProfileViewModel.ARG_QUOTE_ID to quoteId.orEmpty(),
        )
    }
}

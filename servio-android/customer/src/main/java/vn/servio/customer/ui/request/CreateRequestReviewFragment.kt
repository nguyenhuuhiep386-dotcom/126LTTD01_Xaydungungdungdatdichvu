package vn.servio.customer.ui.request

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.navigation.navOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.Address
import vn.servio.customer.databinding.FragmentCreateRequestReviewBinding
import vn.servio.customer.databinding.ItemReviewImageBinding
import vn.servio.customer.ui.request.CreateRequestViewModel.Event
import vn.servio.customer.ui.request.CreateRequestViewModel.Step
import vn.servio.customer.ui.request.ReviewTexts.ExpiryNote
import vn.servio.customer.ui.request.ReviewTexts.Requirement
import vn.servio.customer.ui.request.ReviewTexts.TimeLine
import java.text.NumberFormat
import java.util.Locale

/**
 * CS-11 Xem lại yêu cầu: the draft of CS-08 → CS-10 in four sections. "Sửa" pops back to that step (the customer comes
 * back with Tiếp tục / Xem lại); "Đăng yêu cầu" calls #36 and opens CS-15. While posting, Back and "Sửa" do nothing.
 */
@AndroidEntryPoint
class CreateRequestReviewFragment : Fragment(R.layout.fragment_create_request_review) {

    private val viewModel: CreateRequestViewModel by navGraphViewModels(R.id.create_request_graph) {
        defaultViewModelProviderFactory
    }

    private var binding: FragmentCreateRequestReviewBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCreateRequestReviewBinding.bind(view)
        this.binding = binding
        val thumbnails = ImageThumbnails(requireContext().contentResolver, viewLifecycleOwner.lifecycleScope, THUMBNAIL_EDGE)

        // Enabled only while posting: swallows Back so the post cannot be left half way.
        val blockBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = Unit
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, blockBack)
        binding.toolbar.setNavigationOnClickListener { if (!viewModel.posting.value) findNavController().navigateUp() }

        binding.serviceHeader.label.setText(R.string.create_request_review_service_section)
        binding.describeHeader.label.setText(R.string.create_request_review_describe_section)
        binding.addressTimeHeader.label.setText(R.string.create_request_review_address_time_section)
        binding.budgetHeader.label.setText(R.string.create_request_review_budget_section)
        binding.serviceHeader.editButton.setOnClickListener { edit(Step.DESCRIBE) }
        binding.describeHeader.editButton.setOnClickListener { edit(Step.DESCRIBE) }
        binding.addressTimeHeader.editButton.setOnClickListener { edit(Step.ADDRESS_TIME) }
        binding.budgetHeader.editButton.setOnClickListener { edit(Step.BUDGET) }
        binding.serviceRetryButton.setOnClickListener { viewModel.loadCategory() }
        binding.addressRetryButton.setOnClickListener { viewModel.retryAddress() }
        binding.postButton.setOnClickListener { viewModel.post() }

        // After process death on CS-11 nothing has loaded the address yet.
        viewModel.loadReviewData()

        collectWhenStarted(viewModel.category) { state -> binding.bindService(state) }
        collectWhenStarted(viewModel.address) { state -> binding.bindAddress(state) }
        collectWhenStarted(combine(viewModel.draft, viewModel.category, ::Pair)) { (draft, category) ->
            binding.bindDraft(draft, viewModel.certificateFor(draft, category))
        }
        collectWhenStarted(viewModel.draft.map { it.images }.distinctUntilChanged()) { images ->
            binding.bindImages(images, thumbnails)
        }
        collectWhenStarted(viewModel.imagesLost) { lost -> binding.imagesLostText.isVisible = lost }
        collectWhenStarted(viewModel.posting) { posting ->
            blockBack.isEnabled = posting
            binding.bindPosting(posting)
        }
        collectWhenStarted(viewModel.events) { event -> onEvent(event) }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    // region Sections

    private fun FragmentCreateRequestReviewBinding.bindService(state: UiState<CategoryChoice>) {
        serviceText.text = when (state) {
            UiState.Loading -> getString(R.string.create_request_service_loading)
            is UiState.Error -> state.message
            is UiState.Content -> getString(R.string.create_request_review_service, state.data.group.name, state.data.service.name)
        }
        serviceRetryButton.isVisible = state is UiState.Error
    }

    private fun FragmentCreateRequestReviewBinding.bindAddress(state: UiState<Address?>) {
        addressText.text = when (state) {
            UiState.Loading -> getString(R.string.create_request_address_loading)
            is UiState.Error -> state.message
            is UiState.Content -> state.data?.fullAddress ?: getString(R.string.create_request_address_empty)
        }
        addressRetryButton.isVisible = state is UiState.Error
    }

    private fun FragmentCreateRequestReviewBinding.bindDraft(draft: RequestDraft, certificate: CertificateState) {
        titleText.text = draft.title.trim()
        descriptionText.text = draft.description.trim()

        timeText.text = when (val line = ReviewTexts.timeLine(draft)) {
            TimeLine.Now -> getString(R.string.create_request_review_time_now)
            is TimeLine.Scheduled -> getString(
                R.string.create_request_review_time_scheduled,
                ScheduleLabels.fullDay(requireContext(), line.date),
                ScheduleLabels.slot(requireContext(), line.startHour),
            )
            null -> getString(R.string.create_request_review_time_missing)
        }

        budgetText.text = ReviewTexts.budgetRange(draft)
            ?.let { getString(R.string.create_request_review_budget_range, it) }
            ?: getString(R.string.create_request_budget_quote)

        val labels = ReviewTexts.requirements(draft, certificate).map { requirement ->
            when (requirement) {
                Requirement.EXPERIENCE -> getString(R.string.create_request_review_experience, RequestDraft.EXPERIENCE_YEARS)
                Requirement.CERTIFICATE -> getString(R.string.create_request_require_certificate)
                Requirement.HIGH_RATING -> getString(R.string.create_request_require_rating, RATING_FORMAT.format(RequestDraft.MIN_RATING))
            }
        }
        requirementsText.text = ReviewTexts.requirementsLine(
            labels,
            none = getString(R.string.create_request_review_no_requirement),
            radius = getString(R.string.create_request_review_radius, draft.searchRadiusKm),
        )

        expiryText.setText(
            when (ReviewTexts.expiryNote(draft.scheduleType)) {
                ExpiryNote.NOW_TWO_HOURS -> R.string.create_request_review_expiry_now
                ExpiryNote.SCHEDULED_DAY_OR_START -> R.string.create_request_review_expiry_scheduled
            },
        )
    }

    /** Read-only tiles; a tile whose uri cannot be decoded keeps the "Ảnh N" placeholder. */
    private fun FragmentCreateRequestReviewBinding.bindImages(images: List<DraftImage>, thumbnails: ImageThumbnails) {
        imageRow.removeAllViews()
        images.forEachIndexed { index, image ->
            val tile = ItemReviewImageBinding.inflate(layoutInflater, imageRow, false)
            tile.label.text = getString(R.string.create_request_review_image, index + 1)
            tile.root.contentDescription = tile.label.text
            thumbnails.load(image.uri, tile.thumbnail)
            imageRow.addView(tile.root)
        }
        imageScroll.isVisible = images.isNotEmpty()
    }

    private fun FragmentCreateRequestReviewBinding.bindPosting(posting: Boolean) {
        postButton.isEnabled = !posting
        postButton.setText(if (posting) R.string.create_request_posting else R.string.create_request_post)
        listOf(serviceHeader, describeHeader, addressTimeHeader, budgetHeader).forEach { it.editButton.isEnabled = !posting }
        serviceRetryButton.isEnabled = !posting
        addressRetryButton.isEnabled = !posting
    }

    // endregion

    // region Events

    private fun onEvent(event: Event) {
        when (event) {
            is Event.Posted -> openPosted(event.requestId)
            is Event.Failed -> when (val failure = event.failure) {
                is PostFailure.BackToStep -> {
                    viewModel.leaveNotice(
                        when (failure.notice) {
                            PostFailure.StepNotice.CONTACT_INFO -> getString(R.string.create_request_post_contact_info)
                            PostFailure.StepNotice.IMAGES_NOT_READY -> getString(R.string.create_request_post_images_not_ready)
                            PostFailure.StepNotice.SERVER -> failure.serverMessage
                        },
                    )
                    backTo(failure.step)
                }
                is PostFailure.Dialog -> showDialog(failure)
                is PostFailure.Message -> showMessage(failure.message)
            }
            is Event.Incomplete -> {
                // An expired slot is told by CS-09 itself when it resumes: one message only.
                if (!event.slotExpired) viewModel.leaveNotice(getString(R.string.create_request_post_incomplete))
                backTo(event.step)
            }
            Event.NotReady -> showMessage(getString(R.string.create_request_post_not_ready))
        }
    }

    /**
     * CS-15 replaces the whole flow and CS-07 with it, so Back from CS-15 goes to the screen CS-07 was opened from
     * (every way into create_request_graph goes through CS-07). The ViewModel is cleared and drops the camera files.
     */
    private fun openPosted(requestId: String) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs11) return
        navController.navigate(
            R.id.cs15,
            bundleOf(ARG_REQUEST_ID to requestId),
            navOptions { popUpTo(R.id.cs07) { inclusive = true } },
        )
    }

    private fun showDialog(failure: PostFailure.Dialog) {
        val title = when (failure.kind) {
            PostFailure.DialogKind.PROFILE_INCOMPLETE -> R.string.create_request_post_profile_title
            PostFailure.DialogKind.OUTSTANDING_DEBT -> R.string.create_request_post_debt_title
            PostFailure.DialogKind.POSTING_RESTRICTED -> R.string.create_request_post_restricted_title
        }
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(failure.message)
            .setPositiveButton(R.string.action_close, null)
        if (failure.kind == PostFailure.DialogKind.OUTSTANDING_DEBT) {
            // CS-30 "Phí huỷ và giao dịch" opens on top: the draft is kept.
            builder.setNeutralButton(R.string.create_request_post_view_fee) { _, _ -> findNavController().navigate(R.id.cs30) }
        }
        builder.show()
    }

    // endregion

    // region Navigation

    /** "Sửa": back to the step without a new copy of it; ignored while posting. */
    private fun edit(step: Step) {
        if (viewModel.posting.value) return
        backTo(step)
    }

    private fun backTo(step: Step) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs11) return
        navController.popBackStack(destinationOf(step), false)
    }

    private fun destinationOf(step: Step) = when (step) {
        Step.DESCRIBE -> R.id.cs08
        Step.ADDRESS_TIME -> R.id.cs09
        Step.BUDGET -> R.id.cs10
    }

    private fun showMessage(message: String) {
        val binding = binding ?: return
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).setAnchorView(binding.postButton).show()
    }

    // endregion

    private companion object {
        /** Tiles are 72dp: about 216 px on a 3x screen. */
        const val THUMBNAIL_EDGE = 240

        const val ARG_REQUEST_ID = "requestId"

        /** "4,5" whatever the device language. */
        val RATING_FORMAT: NumberFormat = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"))
    }
}

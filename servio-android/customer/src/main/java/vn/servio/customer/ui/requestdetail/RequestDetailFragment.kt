package vn.servio.customer.ui.requestdetail

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.CancelRequestBody
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.QuoteSort
import vn.servio.customer.data.model.ScheduleType
import vn.servio.customer.data.model.ServiceRequestDetail
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.databinding.DialogCancelRequestBinding
import vn.servio.customer.databinding.FragmentRequestDetailBinding
import vn.servio.customer.databinding.ItemReviewImageBinding
import vn.servio.customer.ui.request.MoneyInput
import vn.servio.customer.ui.requestdetail.RequestDetailViewModel.Event
import vn.servio.customer.ui.requestdetail.RequestDetailViewModel.State
import javax.inject.Inject

/**
 * The ViewModel of CS-15, kept in the cs15 back stack entry so the CS-16 sheet (a dialog destination on top of it)
 * gets the same instance. Throws when cs15 is not on the back stack.
 */
internal fun Fragment.requestDetailViewModel(): Lazy<RequestDetailViewModel> = viewModels(
    ownerProducer = { findNavController().getBackStackEntry(R.id.cs15) },
    factoryProducer = { defaultViewModelProviderFactory },
)

/**
 * CS-15 Chi tiết yêu cầu & báo giá (#38, #42, #40). Reloads every time the screen is opened or returned to.
 * Menu: only "Huỷ yêu cầu"; "Nhắn tin" comes with KH-4; "Chọn" hands over to CS-18.
 */
@AndroidEntryPoint
class RequestDetailFragment : Fragment(R.layout.fragment_request_detail) {

    @Inject lateinit var httpClient: OkHttpClient

    private val viewModel by requestDetailViewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentRequestDetailBinding.bind(view)
        val images = RemoteImages(httpClient, viewLifecycleOwner.lifecycleScope, (RemoteImages.IMAGE_EDGE_DP * resources.displayMetrics.density).toInt())
        val adapter = QuoteAdapter(images, onOpen = ::openQuote, onSelect = ::selectQuote)
        binding.quoteList.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_cancel_request) showCancelDialog()
            true
        }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.sortGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            SORT_CHIPS.entries.firstOrNull { it.value == checkedIds.firstOrNull() }?.let { viewModel.setSort(it.key) }
        }

        collectWhenStarted(viewModel.state) { state -> binding.bind(state, adapter, images) }
        collectWhenStarted(viewModel.events) { event ->
            val text = when (event) {
                is Event.Message -> event.text
                Event.Cancelled -> getString(R.string.request_detail_cancelled)
                Event.CancelConflict -> getString(R.string.request_detail_cancel_conflict)
                Event.QuoteGone -> getString(R.string.quote_gone)
            }
            Snackbar.make(view, text, Snackbar.LENGTH_LONG).show()
        }

        if (viewModel.requestId.isBlank()) {
            // Opened without an id (a placeholder link): nothing to ask the server for.
            binding.swipeRefresh.isEnabled = false
            binding.errorGroup.isVisible = true
            binding.errorText.setText(R.string.request_detail_not_found)
            binding.retryButton.isVisible = false
        } else {
            viewModel.load()
        }
    }

    private fun FragmentRequestDetailBinding.bind(state: State, adapter: QuoteAdapter, images: RemoteImages) {
        if (viewModel.requestId.isBlank()) return
        val detail = state.detail
        val quotes = state.quotes
        swipeRefresh.isRefreshing = state.loading
        content.isVisible = detail != null && quotes != null
        errorGroup.isVisible = state.error != null
        errorText.text = state.error
        toolbar.title = detail?.code ?: getString(R.string.request_detail_title)
        toolbar.menu.findItem(R.id.action_cancel_request).isVisible = detail?.canCancel == true && !state.cancelling
        if (detail == null || quotes == null) return

        bindRequest(detail, images)
        quotesTitle.text = getString(R.string.request_detail_quotes_title, quotes.size)
        quotesEmpty.isVisible = quotes.isEmpty()
        // Sorting one quote changes nothing.
        sortGroup.isVisible = quotes.size > 1
        SORT_CHIPS.getValue(state.sort).let { if (sortGroup.checkedChipId != it) sortGroup.check(it) }
        adapter.submitList(quotes.map { QuoteAdapter.Row(it, state.canSelect(it)) })
    }

    private fun FragmentRequestDetailBinding.bindRequest(detail: ServiceRequestDetail, images: RemoteImages) {
        statusText.setText(statusLabel(detail.status))
        val expiry = QuoteTexts.moment(detail.expiresAt).takeIf { detail.status == ServiceRequestStatus.OPEN }
        expiryText.text = expiry?.let { getString(R.string.request_detail_expires, it) }
        titleText.text = detail.title
        categoryText.text = detail.categoryName
        descriptionText.text = detail.description
        addressText.text = detail.addressSnapshot
        timeText.text = when (detail.scheduleType) {
            ScheduleType.NOW -> QuoteTexts.moment(detail.scheduledEndAt)
                ?.let { getString(R.string.request_detail_time_now_until, it) }
                ?: getString(R.string.request_detail_time_now)
            ScheduleType.SCHEDULED -> QuoteTexts.window(detail.scheduledStartAt, detail.scheduledEndAt)
                ?: getString(R.string.request_detail_time_unknown)
        }
        val min = detail.budgetMin
        val max = detail.budgetMax
        budgetText.text = if (min != null && max != null) {
            getString(R.string.create_request_review_budget_range, "${MoneyInput.format(min)} – ${MoneyInput.format(max)}")
        } else {
            getString(R.string.create_request_budget_quote)
        }
        cancelReasonText.isVisible = !detail.cancelReason.isNullOrBlank()
        cancelReasonText.text = getString(R.string.request_detail_cancel_reason, detail.cancelReason.orEmpty())

        if (imageRow.tag != detail.images) {
            imageRow.tag = detail.images
            imageRow.removeAllViews()
            detail.images.forEachIndexed { index, url ->
                val tile = ItemReviewImageBinding.inflate(layoutInflater, imageRow, false)
                tile.label.text = getString(R.string.create_request_review_image, index + 1)
                tile.root.contentDescription = tile.label.text
                images.load(url, tile.thumbnail)
                imageRow.addView(tile.root)
            }
        }
        imageScroll.isVisible = detail.images.isNotEmpty()
    }

    private fun statusLabel(status: ServiceRequestStatus) = when (status) {
        ServiceRequestStatus.DRAFT -> R.string.requests_status_draft
        ServiceRequestStatus.OPEN -> R.string.requests_status_open
        ServiceRequestStatus.MATCHED -> R.string.requests_status_matched
        ServiceRequestStatus.EXPIRED -> R.string.requests_status_expired
        ServiceRequestStatus.CANCELLED -> R.string.requests_status_cancelled
        ServiceRequestStatus.REJECTED_BY_MODERATION -> R.string.requests_status_rejected
    }

    private fun openQuote(quote: CustomerQuote) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs15) return
        navController.navigate(R.id.cs16, bundleOf(QuoteDetailSheet.ARG_QUOTE_ID to quote.id))
    }

    private fun selectQuote(quote: CustomerQuote) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs15) return
        val revision = viewModel.state.value.detail?.revision ?: return
        navController.navigate(R.id.cs18, selectionArgs(this, viewModel.requestId, quote, revision))
    }

    private fun showCancelDialog() {
        val form = DialogCancelRequestBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Servio_AlertDialog_Destructive)
            .setTitle(R.string.request_detail_cancel_title)
            .setMessage(R.string.request_detail_cancel_message)
            .setView(form.root)
            .setNegativeButton(R.string.action_close, null)
            .setPositiveButton(R.string.request_detail_menu_cancel) { _, _ ->
                viewModel.cancel(form.reasonInput.text?.toString()?.take(CancelRequestBody.MAX_REASON_LENGTH))
            }
            .show()
    }

    companion object {
        private val SORT_CHIPS = mapOf(
            QuoteSort.TIME to R.id.sortTime,
            QuoteSort.PRICE to R.id.sortPrice,
            QuoteSort.RATING to R.id.sortRating,
        )

        /**
         * What CS-18 needs to call #43: the quote with its rowVersion and the request revision the customer saw.
         * "description" only feeds the CS-18 placeholder until that screen is built.
         */
        fun selectionArgs(fragment: Fragment, requestId: String, quote: CustomerQuote, revision: Int): Bundle = bundleOf(
            "requestId" to requestId,
            "quoteId" to quote.id,
            "rowVersion" to quote.rowVersion,
            "revision" to revision,
            "description" to fragment.getString(R.string.quote_selection_placeholder, requestId, quote.id, quote.rowVersion, revision),
        )
    }
}

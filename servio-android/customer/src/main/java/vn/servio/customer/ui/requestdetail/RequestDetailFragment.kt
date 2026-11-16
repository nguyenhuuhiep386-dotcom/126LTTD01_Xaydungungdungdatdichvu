package vn.servio.customer.ui.requestdetail

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.PopupMenu
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
import vn.servio.customer.data.model.ServiceRequestDetail
import vn.servio.customer.data.model.ServiceRequestStatus
import vn.servio.customer.databinding.DialogCancelRequestBinding
import vn.servio.customer.databinding.FragmentRequestDetailBinding
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.ui.common.RequestStatusChip
import vn.servio.customer.ui.confirmquote.ConfirmQuoteFragment
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
 * The card follows Figma, so the description and the photos after the first one are not shown here.
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
        binding.sortButton.setOnClickListener { showSortMenu(it) }

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

        bindRequest(detail, state.categoryGroup, images)
        quotesTitle.text = getString(R.string.request_detail_quotes_title, quotes.size)
        quotesEmpty.isVisible = quotes.isEmpty()
        sortButton.isVisible = quotes.isNotEmpty()
        sortButton.setText(sortLabel(state.sort))
        adapter.submitList(quotes.map { QuoteAdapter.Row(it, state.canSelect(it)) })
    }

    private fun FragmentRequestDetailBinding.bindRequest(detail: ServiceRequestDetail, categoryGroup: String?, images: RemoteImages) {
        // Loading again would restart a download that is still running, so only when the photo changes.
        val photo = detail.images.firstOrNull()
        if (thumbnail.getTag(R.id.thumbnail) != photo) {
            thumbnail.setTag(R.id.thumbnail, photo)
            images.load(photo, thumbnail)
        }
        titleText.text = detail.title
        categoryText.text = categoryGroup
            ?.let { getString(R.string.request_detail_category, it, detail.categoryName) }
            ?: detail.categoryName
        RequestStatusChip.bind(statusText, detail.status, openLabel = R.string.request_detail_status_open)

        timeText.text = QuoteFormat.schedule(requireContext(), detail.scheduleType, detail.scheduledStartAt, detail.scheduledEndAt)
        addressText.text = detail.addressSnapshot
        val min = detail.budgetMin
        val max = detail.budgetMax
        budgetText.text = if (min != null && max != null) {
            getString(R.string.request_detail_budget, MoneyInput.format(min), MoneyInput.format(max))
        } else {
            getString(R.string.create_request_budget_quote)
        }
        // Only an open request is still counting down; the others say what happened in the status chip.
        val expiry = QuoteFormat.expiry(requireContext(), detail.expiresAt).takeIf { detail.status == ServiceRequestStatus.OPEN }
        expiryText.isVisible = expiry != null
        expiryText.text = expiry
        cancelReasonText.isVisible = !detail.cancelReason.isNullOrBlank()
        cancelReasonText.text = getString(R.string.request_detail_cancel_reason, detail.cancelReason.orEmpty())
    }

    /** The three orders #42 knows; there is no "newest first" on the server. */
    private fun sortLabel(sort: QuoteSort) = when (sort) {
        QuoteSort.TIME -> R.string.request_detail_sort_time
        QuoteSort.PRICE -> R.string.request_detail_sort_price
        QuoteSort.RATING -> R.string.request_detail_sort_rating
    }

    private fun showSortMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            QuoteSort.entries.forEachIndexed { index, sort -> menu.add(0, index, index, sortLabel(sort)) }
            setOnMenuItemClickListener { item ->
                viewModel.setSort(QuoteSort.entries[item.itemId])
                true
            }
        }.show()
    }

    private fun openQuote(quote: CustomerQuote) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs15) return
        navController.navigate(R.id.cs16, bundleOf(QuoteDetailSheet.ARG_QUOTE_ID to quote.id))
    }

    private fun selectQuote(quote: CustomerQuote) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs15) return
        navController.navigate(R.id.cs18, ConfirmQuoteFragment.args(viewModel.requestId, quote.id))
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
}

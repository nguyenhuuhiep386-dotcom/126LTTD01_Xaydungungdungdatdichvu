package vn.servio.partner.ui.quote

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import vn.servio.core.ui.chat.ChatFragment
import vn.servio.core.utils.Money
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.model.PartnerRequestView
import vn.servio.partner.data.model.QuoteStatus
import vn.servio.partner.databinding.FragmentRequestDetailBinding
import vn.servio.partner.ui.common.LoadStatus
import vn.servio.partner.ui.common.RemoteImages
import vn.servio.partner.ui.common.Texts
import vn.servio.partner.ui.quote.RequestDetailViewModel.Event
import javax.inject.Inject

/** PS-10 Chi tiết bài đăng. "Gửi báo giá" opens the PS-11 sheet; the own quote can be withdrawn here (#52). */
@AndroidEntryPoint
class RequestDetailFragment : Fragment(R.layout.fragment_request_detail) {

    @Inject lateinit var okHttpClient: OkHttpClient

    private val viewModel: RequestDetailViewModel by viewModels()

    private val requestId: String get() = requireArguments().getString(ARG_REQUEST_ID).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PS-11 reports a sent quote: show it here.
        setFragmentResultListener(QuoteSheet.RESULT_KEY) { _, _ ->
            view?.let { Snackbar.make(it, R.string.quote_sent, Snackbar.LENGTH_LONG).show() }
            viewModel.load(showSpinner = false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentRequestDetailBinding.bind(view)
        val images = RemoteImages(okHttpClient, viewLifecycleOwner.lifecycleScope, IMAGE_EDGE_PX)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(showSpinner = false) }
        binding.quoteButton.setOnClickListener { openQuoteSheet() }
        binding.chatButton.setOnClickListener { viewModel.openChat() }

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = false
            LoadStatus.bind(binding.status, state, binding.swipeRefresh) { viewModel.load() }
            binding.actionBar.isVisible = state is UiState.Content
            if (state is UiState.Content) {
                bind(binding, state.data, images)
                val openQuote = arguments?.getBoolean(ARG_OPEN_QUOTE) == true
                if (openQuote && !viewModel.quoteSheetOpened && state.data.canQuote) {
                    viewModel.quoteSheetOpened = true
                    openQuoteSheet()
                }
            }
        }
        collectWhenStarted(viewModel.busy) { busy ->
            binding.withdrawButton.isEnabled = !busy
            binding.chatButton.isEnabled = !busy
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is Event.Message -> Snackbar.make(view, event.text, Snackbar.LENGTH_LONG).show()
                Event.Withdrawn -> Snackbar.make(view, R.string.quote_withdrawn, Snackbar.LENGTH_LONG).show()
                is Event.OpenChat ->
                    findNavController().navigate(R.id.chat, bundleOf(ChatFragment.ARG_CONVERSATION_ID to event.conversationId))
            }
        }
    }

    private fun bind(binding: FragmentRequestDetailBinding, request: PartnerRequestView, images: RemoteImages) {
        val context = requireContext()
        binding.imagesScroll.isVisible = request.images.isNotEmpty()
        binding.images.removeAllViews()
        request.images.forEach { url ->
            val tile = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_thumbnail)
                clipToOutline = true
                contentDescription = getString(R.string.request_photo)
            }
            binding.images.addView(tile)
            tile.updateLayoutParams<android.widget.LinearLayout.LayoutParams> {
                width = resources.getDimensionPixelSize(R.dimen.request_photo_width)
                height = resources.getDimensionPixelSize(R.dimen.request_photo_height)
                marginEnd = resources.getDimensionPixelSize(vn.servio.core.R.dimen.space_s)
            }
            images.load(url, tile)
        }

        binding.category.text = getString(R.string.request_category_line, request.categoryName, Texts.ago(context, request.publishedAt))
        binding.title.text = request.title
        binding.description.text = request.description
        binding.area.text = request.distanceKm
            ?.let { getString(R.string.request_area_distance, request.areaLabel, Texts.distance(it)) }
            ?: request.areaLabel
        binding.schedule.text = Texts.schedule(context, request.scheduleType, request.scheduledStartAt, request.scheduledEndAt)
        binding.budget.text = Money.range(request.budgetMin, request.budgetMax)
            ?.let { getString(R.string.request_budget, it) }
            ?: getString(R.string.feed_budget_open)
        val requirements = buildList {
            request.requireExperienceYears?.takeIf { it > 0 }?.let { add(getString(R.string.request_require_years, it)) }
            if (request.requireCertificate) add(getString(R.string.request_require_certificate))
            request.requireMinRating?.let { add(getString(R.string.request_require_rating, it)) }
        }
        binding.requirements.isVisible = requirements.isNotEmpty()
        binding.requirements.text = requirements.joinToString(" · ")

        val customer = request.customer
        binding.customerName.text = customer.displayName
        binding.customerInitial.text = customer.displayName.split(' ').filter { it.isNotBlank() }
            .take(2).joinToString("") { it.take(1).uppercase() }
        binding.customerStats.text = customer.rating
            ?.let { getString(R.string.request_customer_stats, it, customer.totalOrders) }
            ?: getString(R.string.request_customer_new, customer.totalOrders)
        binding.quoteCount.text = if (request.quoteCount == 0) {
            getString(R.string.feed_no_quotes)
        } else {
            getString(R.string.feed_quotes, request.quoteCount)
        }

        val own = request.ownQuote
        binding.ownQuoteCard.isVisible = own != null
        if (own != null) {
            binding.ownQuoteAmount.text = getString(R.string.request_own_quote_amount, Money.format(own.amount), getString(QuoteTexts.status(own.status)))
            binding.ownQuoteTime.text = QuoteTexts.arrival(context, own)
            binding.withdrawButton.isVisible = own.status == QuoteStatus.PENDING
            binding.withdrawButton.setOnClickListener {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.quote_withdraw_title)
                    .setMessage(R.string.quote_withdraw_message)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.quote_withdraw) { _, _ -> viewModel.withdraw(own.id) }
                    .show()
            }
        }
        binding.quoteButton.isEnabled = request.canQuote
        binding.quoteButton.setText(
            when {
                own?.status == QuoteStatus.PENDING -> R.string.feed_quoted
                request.canQuote -> R.string.quote_send
                else -> R.string.quote_not_allowed
            },
        )
        // The customer can be contacted while the post is open, or about an existing quote.
        binding.chatButton.isVisible = request.canQuote || own?.conversationId != null
    }

    private fun openQuoteSheet() {
        findNavController().navigate(R.id.ps11, bundleOf(ARG_REQUEST_ID to requestId))
    }

    companion object {
        const val ARG_REQUEST_ID = "requestId"
        const val ARG_OPEN_QUOTE = "openQuote"
        private const val IMAGE_EDGE_PX = 720
    }
}

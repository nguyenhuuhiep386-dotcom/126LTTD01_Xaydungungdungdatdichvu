package vn.servio.customer.ui.requestdetail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.QuoteStatus
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.databinding.SheetQuoteDetailBinding
import vn.servio.customer.ui.requestdetail.RequestDetailViewModel.State
import javax.inject.Inject

/**
 * CS-16 Chi tiết báo giá. Makes no call of its own: it shows the quote [ARG_QUOTE_ID] from the CS-15 list and
 * follows that list. When the quote is not there (withdrawn before a reload, or the list failed to load after the
 * app was restored) the sheet closes itself and CS-15 says why.
 */
@AndroidEntryPoint
class QuoteDetailSheet : BottomSheetDialogFragment() {

    @Inject lateinit var httpClient: OkHttpClient

    private var closing = false

    override fun getTheme() = R.style.ThemeOverlay_Servio_BottomSheetDialog

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetQuoteDetailBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = SheetQuoteDetailBinding.bind(view)
        // Without CS-15 underneath (opened from a placeholder link) there is no list to read from.
        val viewModel = runCatching { requestDetailViewModel().value }.getOrNull() ?: return close()
        val quoteId = requireArguments().getString(ARG_QUOTE_ID).orEmpty()
        val images = RemoteImages(httpClient, viewLifecycleOwner.lifecycleScope, (RemoteImages.IMAGE_EDGE_DP * resources.displayMetrics.density).toInt())

        collectWhenStarted(viewModel.state) { state ->
            val quote = state.quotes?.firstOrNull { it.id == quoteId }
            when {
                quote != null -> binding.bind(quote, state, viewModel.requestId, images)
                // Restored before CS-15 has its first answer: wait for it.
                state.quotes == null && state.loading -> {
                    binding.progress.isVisible = true
                    binding.content.isVisible = false
                }
                else -> {
                    viewModel.onQuoteGone()
                    close()
                }
            }
        }
    }

    private fun SheetQuoteDetailBinding.bind(quote: CustomerQuote, state: State, requestId: String, images: RemoteImages) {
        val context = root.context
        progress.isVisible = false
        content.isVisible = true
        partner.bind(quote.partner, images)
        val distance = QuoteTexts.distance(quote.partner.distanceKm)
        distanceText.isVisible = distance != null
        distanceText.text = distance?.let { getString(R.string.quote_distance, it) }
        priceText.text = QuoteFormat.price(context, quote.amount)
        acceptedText.isVisible = quote.status == QuoteStatus.ACCEPTED
        availableText.text = QuoteFormat.available(context, quote)
        val duration = QuoteFormat.duration(context, quote.estimatedDurationMinutes)
        durationText.text = QuoteTexts.moment(quote.estimatedEndAt)
            ?.let { getString(R.string.quote_duration_until, duration, it) }
            ?: getString(R.string.quote_duration, duration)
        noteLabel.isVisible = !quote.note.isNullOrBlank()
        noteText.isVisible = !quote.note.isNullOrBlank()
        noteText.text = quote.note

        val revision = state.detail?.revision
        selectButton.isVisible = state.canSelect(quote) && revision != null
        selectButton.setOnClickListener {
            if (revision != null) leaveTo(R.id.cs18, RequestDetailFragment.selectionArgs(this@QuoteDetailSheet, requestId, quote, revision))
        }
        profileButton.setOnClickListener {
            leaveTo(
                R.id.cs17,
                bundleOf(
                    ARG_PARTNER_ID to quote.partner.id,
                    "description" to getString(R.string.quote_partner_placeholder, quote.partner.id),
                ),
            )
        }
    }

    /** Navigating to a full screen pops this sheet, so Back from there returns to CS-15. */
    private fun leaveTo(destination: Int, args: Bundle) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs16) return
        navController.navigate(destination, args)
    }

    private fun close() {
        if (closing) return
        closing = true
        dismissAllowingStateLoss()
    }

    companion object {
        const val ARG_QUOTE_ID = "quoteId"
        const val ARG_PARTNER_ID = "partnerId"
    }
}

package vn.servio.customer.ui.requestdetail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.CustomerQuote
import vn.servio.customer.data.model.QuoteStatus
import vn.servio.customer.databinding.SheetQuoteDetailBinding
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.ui.common.showSkills
import vn.servio.customer.ui.confirmquote.ConfirmQuoteFragment
import vn.servio.customer.ui.partner.PartnerProfileFragment
import vn.servio.customer.ui.requestdetail.RequestDetailViewModel.State
import javax.inject.Inject

/**
 * CS-16 Chi tiết báo giá. Shows the quote [ARG_QUOTE_ID] from the CS-15 list and follows that list. When the quote
 * is not there (withdrawn before a reload, or the list failed to load after the app was restored) the sheet closes
 * itself and CS-15 says why.
 *
 * Its only own call is #27, for the skill chips that #42 does not carry: no chips while it loads or if it fails.
 */
@AndroidEntryPoint
class QuoteDetailSheet : BottomSheetDialogFragment() {

    @Inject lateinit var httpClient: OkHttpClient

    private var closing = false
    private var shownSkills: List<String>? = null

    override fun getTheme() = R.style.ThemeOverlay_Servio_BottomSheetDialog

    /**
     * Always fully open, also in landscape where the default peek height showed only the name: the content scrolls
     * inside the sheet, so "Chọn báo giá này" is reached without dragging. Dragging down closes it (no half state).
     */
    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetQuoteDetailBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = SheetQuoteDetailBinding.bind(view)
        // Without CS-15 underneath (opened from a placeholder link) there is no list to read from.
        val viewModel = runCatching { requestDetailViewModel().value }.getOrNull() ?: return close()
        val quoteId = requireArguments().getString(ARG_QUOTE_ID).orEmpty()
        val images = RemoteImages(httpClient, viewLifecycleOwner.lifecycleScope, (RemoteImages.IMAGE_EDGE_DP * resources.displayMetrics.density).toInt())
        binding.closeButton.setOnClickListener { close() }
        shownSkills = null

        collectWhenStarted(viewModel.state) { state ->
            val quote = state.quotes?.firstOrNull { it.id == quoteId }
            when {
                quote != null -> {
                    viewModel.loadPartnerSkills(quote.partner.id)
                    binding.bind(quote, state, viewModel.requestId, images)
                }
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
        val partner = quote.partner
        progress.isVisible = false
        content.isVisible = true

        avatarInitials.text = QuoteTexts.initials(partner.fullName)
        // Loading again would restart a download that is still running, so only when the url changes.
        if (avatarImage.getTag(R.id.avatarImage) != partner.avatarUrl) {
            avatarImage.setTag(R.id.avatarImage, partner.avatarUrl)
            images.load(partner.avatarUrl, avatarImage)
        }
        nameText.text = partner.fullName
        rating.bind(partner)
        completedText.text = getString(R.string.quote_completed, partner.completedOrders)
        bindSkills(state.partnerSkills[partner.id])

        priceText.text = QuoteFormat.money(context, quote.amount)
        availableText.text = QuoteFormat.moment(context, quote.availableFrom)
        durationText.text = getString(R.string.quote_duration_about, QuoteFormat.duration(context, quote.estimatedDurationMinutes))
        noteText.isVisible = !quote.note.isNullOrBlank()
        noteText.text = getString(R.string.quote_note, quote.note.orEmpty().trim())

        profileRow.setOnClickListener {
            // CS-17 shows "Chọn báo giá này" only for a quote that can be chosen; it checks again with #38/#42.
            val bookable = state.canSelect(quote)
            leaveTo(
                R.id.cs17,
                PartnerProfileFragment.args(
                    partner.id,
                    requestId.takeIf { bookable },
                    quote.id.takeIf { bookable },
                ),
            )
        }
        acceptedText.isVisible = quote.status == QuoteStatus.ACCEPTED
        actions.isVisible = state.canSelect(quote)
        selectButton.setOnClickListener { leaveTo(R.id.cs18, ConfirmQuoteFragment.args(requestId, quote.id)) }
    }

    /** [skills] is null until #27 has answered (or when it failed): the row is hidden, the sheet is not blocked. */
    private fun SheetQuoteDetailBinding.bindSkills(skills: List<String>?) {
        skillGroup.isVisible = !skills.isNullOrEmpty()
        if (skills == null || skills == shownSkills) return
        shownSkills = skills
        skillGroup.showSkills(skills)
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
    }
}

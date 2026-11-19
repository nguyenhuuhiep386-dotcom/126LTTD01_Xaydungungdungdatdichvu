package vn.servio.customer.ui.confirmquote

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.CreatedOrder
import vn.servio.customer.databinding.FragmentConfirmQuoteBinding
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.ui.confirmquote.ConfirmFailure.DialogKind
import vn.servio.customer.ui.confirmquote.ConfirmQuoteViewModel.Event
import vn.servio.customer.ui.confirmquote.ConfirmQuoteViewModel.State
import vn.servio.customer.ui.requestdetail.QuoteFormat
import vn.servio.customer.ui.requestdetail.QuoteTexts
import vn.servio.customer.ui.requestdetail.bind
import javax.inject.Inject

/**
 * CS-18 Xác nhận đặt dịch vụ (#43). Opened by "Chọn" on CS-15/CS-16 with requestId and quoteId only.
 * On 201 it opens the order (CS-20) and leaves the back stack, so Back from the order returns to CS-15.
 */
@AndroidEntryPoint
class ConfirmQuoteFragment : Fragment(R.layout.fragment_confirm_quote) {

    @Inject lateinit var httpClient: OkHttpClient

    private val viewModel: ConfirmQuoteViewModel by viewModels()

    /** The dialog of [State.dialog] currently on screen; dismissed (without clearing the state) with the view. */
    private var shownDialog: AlertDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentConfirmQuoteBinding.bind(view)
        val images = RemoteImages(httpClient, viewLifecycleOwner.lifecycleScope, (RemoteImages.IMAGE_EDGE_DP * resources.displayMetrics.density).toInt())

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.otherQuotesButton.setOnClickListener { backToRequest() }
        binding.confirmButton.setOnClickListener { viewModel.confirm() }
        binding.cashCard.isChecked = true

        collectWhenStarted(viewModel.state) { state ->
            binding.bind(state, images)
            showDialog(state)
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is Event.OrderCreated -> openOrder(event.order)
                is Event.Retry -> Snackbar.make(view, event.message, Snackbar.LENGTH_LONG)
                    .setAction(vn.servio.core.R.string.action_retry) { viewModel.confirm() }
                    .show()
                is Event.Message -> Snackbar.make(view, event.text, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        // Rotation: the dialog goes with the window and is shown again from the state by the new view.
        shownDialog?.setOnDismissListener(null)
        shownDialog?.dismiss()
        shownDialog = null
        super.onDestroyView()
    }

    private fun FragmentConfirmQuoteBinding.bind(state: State, images: RemoteImages) {
        val firstLoad = !state.loaded
        progress.isVisible = state.loading && firstLoad
        errorGroup.isVisible = state.error != null
        errorText.text = state.error
        unavailableGroup.isVisible = state.unavailable
        content.isVisible = state.available
        bottomBar.isVisible = state.available
        confirmButton.isEnabled = !state.submitting && !state.loading
        confirmButton.text = getString(if (state.submitting) R.string.confirm_quote_sending else R.string.confirm_quote_confirm)

        val detail = state.detail ?: return
        val quote = state.quote ?: return
        if (!state.available) return
        val partner = quote.partner
        avatarInitials.text = QuoteTexts.initials(partner.fullName)
        // Loading again would restart a download that is still running, so only when the url changes.
        if (avatarImage.getTag(R.id.avatarImage) != partner.avatarUrl) {
            avatarImage.setTag(R.id.avatarImage, partner.avatarUrl)
            images.load(partner.avatarUrl, avatarImage)
        }
        nameText.text = partner.fullName
        rating.bind(partner)

        titleText.text = detail.title
        val weekdays = resources.getStringArray(R.array.create_request_weekday_full).toList()
        arrivalText.text = getString(R.string.confirm_quote_arrival, QuoteTexts.moment(quote.availableFrom, weekdays) ?: quote.availableFrom)
        addressText.text = detail.addressSnapshot
        priceText.text = QuoteFormat.money(requireContext(), quote.amount)
        // No extra charge exists before the order: the expected total is the quoted price.
        totalText.text = QuoteFormat.money(requireContext(), quote.amount)
    }

    private fun showDialog(state: State) {
        val dialog = state.dialog
        if (dialog == null) {
            shownDialog?.setOnDismissListener(null)
            shownDialog?.dismiss()
            shownDialog = null
            return
        }
        if (shownDialog?.isShowing == true) return
        val (title, message, button) = when (dialog.kind) {
            DialogKind.CHANGED -> Triple(R.string.confirm_quote_changed_title, getString(R.string.confirm_quote_changed_message), R.string.confirm_quote_got_it)
            DialogKind.ALREADY_MATCHED -> Triple(R.string.confirm_quote_matched_title, getString(R.string.confirm_quote_matched_message), R.string.confirm_quote_back_to_request)
            DialogKind.PARTNER_UNAVAILABLE -> Triple(R.string.confirm_quote_partner_title, dialog.message, R.string.confirm_quote_other_quotes)
            DialogKind.REQUEST_CLOSED -> Triple(R.string.confirm_quote_closed_title, dialog.message, R.string.confirm_quote_back_to_request)
            DialogKind.NOT_FOUND -> Triple(R.string.confirm_quote_not_found_title, getString(R.string.confirm_quote_not_found_message), R.string.confirm_quote_back_to_request)
        }
        shownDialog = MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Servio_AlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(button) { _, _ ->
                shownDialog = null
                viewModel.onDialogClosed()
                if (dialog.kind != DialogKind.CHANGED) backToRequest()
            }
            .show()
    }

    /** CS-15 is under this screen; its view is created again and reloads. */
    private fun backToRequest() {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs18) return
        if (!navController.popBackStack(R.id.cs15, false)) navController.navigateUp()
    }

    private fun openOrder(order: CreatedOrder) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs18) return
        navController.navigate(
            R.id.cs20,
            bundleOf(
                ARG_ORDER_ID to order.id,
                "description" to getString(R.string.confirm_quote_order_placeholder, order.id, order.code),
            ),
            navOptions { popUpTo(R.id.cs18) { inclusive = true } },
        )
    }

    companion object {
        const val ARG_ORDER_ID = "orderId"

        /** Arguments of cs18: the request and the quote; rowVersion is read again from #42 on CS-18. */
        fun args(requestId: String, quoteId: String): Bundle = bundleOf(
            ConfirmQuoteViewModel.ARG_REQUEST_ID to requestId,
            ConfirmQuoteViewModel.ARG_QUOTE_ID to quoteId,
        )
    }
}

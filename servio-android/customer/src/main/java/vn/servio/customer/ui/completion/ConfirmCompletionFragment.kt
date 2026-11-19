package vn.servio.customer.ui.completion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.databinding.FragmentConfirmCompletionBinding
import vn.servio.customer.databinding.ItemCompletionPhotoBinding
import vn.servio.customer.databinding.ItemPaymentRowBinding
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.ui.order.OrderFormat
import vn.servio.customer.ui.order.OrderRoutes
import vn.servio.customer.ui.order.OrderTexts
import vn.servio.customer.ui.requestdetail.QuoteFormat
import vn.servio.customer.ui.review.SubmitReviewViewModel
import javax.inject.Inject

/**
 * CS-23 Xác nhận hoàn thành, opened from CS-20 ("Xem kết quả và xác nhận") or straight from a CS-19 card. What it
 * shows is decided in [ConfirmCompletionUi]. After #71 it is replaced by CS-26 (Back from there returns to the screen
 * that opened CS-23).
 */
@AndroidEntryPoint
class ConfirmCompletionFragment : Fragment(R.layout.fragment_confirm_completion) {

    @Inject lateinit var httpClient: OkHttpClient

    private val viewModel: ConfirmCompletionViewModel by viewModels()

    private var shownPhotos: List<ConfirmCompletionUi.Photo>? = null
    private var shownPayment: ConfirmCompletionUi.Payment? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentConfirmCompletionBinding.bind(view)
        val images = RemoteImages(httpClient, viewLifecycleOwner.lifecycleScope, (PHOTO_EDGE_DP * resources.displayMetrics.density).toInt())
        shownPhotos = null
        shownPayment = null
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.confirmButton.setOnClickListener { viewModel.confirm() }

        collectWhenStarted(viewModel.state) { state ->
            val detail = state.detail
            binding.progress.isVisible = state.loading && detail == null
            binding.errorGroup.isVisible = detail == null && state.error != null
            binding.errorText.text = state.error
            binding.content.isVisible = detail != null
            binding.bottomBar.isVisible = detail != null
            if (detail != null) bind(binding, images, detail, state)
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                ConfirmCompletionViewModel.Event.NotWaiting -> OrderRoutes.leaveWith(this, R.string.confirm_not_waiting)
                is ConfirmCompletionViewModel.Event.Confirmed -> openReview(event.assignmentId)
                is ConfirmCompletionViewModel.Event.Message -> {
                    val text = event.res?.let(::getString) ?: event.text
                    text?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
                }
            }
        }

        if (savedInstanceState == null || viewModel.state.value.detail == null) viewModel.load()
    }

    private fun bind(binding: FragmentConfirmCompletionBinding, images: RemoteImages, order: OrderDetail, state: ConfirmCompletionViewModel.State) {
        val context = requireContext()
        val weekdays = QuoteFormat.weekdays(context)

        binding.doneText.text = OrderTexts.moment(order.completedByPartnerAt, weekdays)
            ?.let { getString(R.string.confirm_done_at, it) } ?: getString(R.string.confirm_done)
        binding.autoText.text = when (val deadline = ConfirmCompletionUi.deadline(order)) {
            is ConfirmCompletionUi.Deadline.At -> OrderTexts.moment(deadline.autoConfirmAt, weekdays)?.let { getString(R.string.confirm_auto_at, it) }
            ConfirmCompletionUi.Deadline.Unknown -> null
        } ?: getString(R.string.confirm_auto_unknown)

        bindPhotos(binding, images, ConfirmCompletionUi.photos(order))
        binding.noteText.isVisible = state.note != null
        binding.noteText.text = state.note?.let { getString(R.string.confirm_note, it) }

        val payment = ConfirmCompletionUi.payment(order)
        if (payment != shownPayment) {
            shownPayment = payment
            binding.paymentRows.removeAllViews()
            val inflater = LayoutInflater.from(context)
            ItemPaymentRowBinding.inflate(inflater, binding.paymentRows, true).apply {
                label.text = getString(R.string.confirm_quoted)
                value.text = OrderFormat.money(context, payment.quoted)
            }
            payment.approved.forEach { charge ->
                ItemPaymentRowBinding.inflate(inflater, binding.paymentRows, true).apply {
                    label.text = getString(R.string.confirm_extra, charge.description.trim())
                    value.text = OrderFormat.money(context, charge.amount)
                }
            }
        }
        val total = OrderFormat.money(context, payment.total)
        binding.totalText.text = total
        binding.confirmButton.text = getString(R.string.confirm_button, total)
        binding.confirmButton.isEnabled = !state.sending
    }

    /** Tiles only rebuilt when the list changes (a reload with the same photos does not download them again). */
    private fun bindPhotos(binding: FragmentConfirmCompletionBinding, images: RemoteImages, photos: List<ConfirmCompletionUi.Photo>) {
        binding.photosTitle.isVisible = photos.isNotEmpty()
        binding.photosScroll.isVisible = photos.isNotEmpty()
        if (photos == shownPhotos) return
        shownPhotos = photos
        binding.photos.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        var before = 0
        var after = 0
        photos.forEach { photo ->
            val tile = ItemCompletionPhotoBinding.inflate(inflater, binding.photos, true)
            tile.photoTag.setText(if (photo.before) R.string.confirm_photo_before else R.string.confirm_photo_after)
            tile.image.contentDescription = if (photo.before) getString(R.string.confirm_photo_before_desc, ++before)
            else getString(R.string.confirm_photo_after_desc, ++after)
            images.load(photo.url, tile.image)
        }
    }

    /** CS-26 replaces CS-23: the order is COMPLETED now, Back from CS-26 goes to CS-19 or CS-20. */
    private fun openReview(assignmentId: String?) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs23) return
        if (assignmentId == null) {
            navController.popBackStack()
            return
        }
        navController.navigate(
            R.id.cs26,
            SubmitReviewViewModel.args(viewModel.orderId, assignmentId),
            NavOptions.Builder().setPopUpTo(R.id.cs23, true).build(),
        )
    }

    private companion object {
        /** Tiles are 112 dp; a little larger keeps them sharp. */
        const val PHOTO_EDGE_DP = 224
    }
}

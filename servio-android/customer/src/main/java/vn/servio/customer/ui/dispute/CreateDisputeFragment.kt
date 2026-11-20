package vn.servio.customer.ui.dispute

import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.databinding.FragmentCreateDisputeBinding
import vn.servio.customer.ui.dispute.DisputeRules.DescriptionError
import vn.servio.customer.ui.dispute.DisputeRules.Reason
import vn.servio.customer.ui.order.OrderRoutes
import vn.servio.customer.ui.orderdetail.OrderDetailViewModel
import vn.servio.customer.ui.request.DraftImage
import vn.servio.customer.ui.request.DraftImageAdapter
import vn.servio.customer.ui.request.ImageThumbnails

/**
 * CS-33 Gửi khiếu nại, opened from the CS-20 menu or from CS-23 with the order id. After #146 the order is DISPUTED:
 * the customer lands on CS-20 with "Đã gửi khiếu nại" (CS-23 is closed on the way, it no longer applies).
 * Photos come from the gallery (the camera of CS-08 is not offered here).
 */
@AndroidEntryPoint
class CreateDisputeFragment : Fragment(R.layout.fragment_create_dispute) {

    private val viewModel: CreateDisputeViewModel by viewModels()

    private val pickOne = registerForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addImages(listOf(uri))
    }

    /** PickMultipleVisualMedia takes its limit at registration (and needs at least 2), so one launcher per limit. */
    private val pickMany = (2..DisputeRules.MAX_IMAGES).associateWith { limit ->
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(limit)) { uris -> viewModel.addImages(uris) }
    }

    private val chips: Map<Reason, Int> = mapOf(
        Reason.WORK_QUALITY to R.id.chipWorkQuality,
        Reason.OVERCHARGE to R.id.chipOvercharge,
        Reason.BEHAVIOR to R.id.chipBehavior,
        Reason.OTHER to R.id.chipOther,
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCreateDisputeBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // The ViewModel is the only source of the text and of the chosen chip.
        binding.descriptionInput.setText(viewModel.state.value.description)
        binding.descriptionInput.doAfterTextChanged { viewModel.setDescription(it?.toString().orEmpty()) }
        binding.reasonChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val reason = chips.entries.firstOrNull { it.value == checkedIds.firstOrNull() }?.key
            if (reason != null) viewModel.selectReason(reason)
            // Once chosen, the reason cannot be un-chosen (tapping the chosen chip again keeps it).
            binding.reasonChips.isSelectionRequired = reason != null || viewModel.state.value.reason != null
        }

        val adapter = DraftImageAdapter(
            thumbnails = ImageThumbnails(requireContext().contentResolver, viewLifecycleOwner.lifecycleScope, THUMBNAIL_EDGE),
            onAdd = ::openGallery,
            onRetry = { viewModel.retryImage(it.localId) },
            onRemove = { viewModel.removeImage(it.localId) },
            maxImages = DisputeRules.MAX_IMAGES,
        )
        binding.imageList.adapter = adapter
        binding.sendButton.setOnClickListener { viewModel.send() }

        collectWhenStarted(viewModel.state) { state ->
            bindOrder(binding, state)
            state.reason?.let { reason ->
                val id = chips.getValue(reason)
                if (binding.reasonChips.checkedChipId != id) binding.reasonChips.check(id)
            }
            val reasonError = state.serverReasonError ?: if (state.missingReason) getString(R.string.dispute_reason_required) else null
            binding.reasonError.isVisible = reasonError != null
            binding.reasonError.text = reasonError
            binding.descriptionLayout.error = state.serverDescriptionError ?: state.descriptionError?.let(::descriptionMessage)

            adapter.submitImages(state.images)
            binding.imagesLabel.text = getString(R.string.dispute_images_label, state.images.size, DisputeRules.MAX_IMAGES)
            val failed = state.images.any { it.status == DraftImage.Status.FAILED }
            binding.imagesError.isVisible = failed
            binding.imagesError.setText(R.string.create_request_images_failed)

            val config = state.config
            binding.infoCard.isVisible = config != null
            if (config != null) {
                binding.infoText.text = getString(R.string.dispute_info, config.disputeResponseHours, config.disputeWindowHours)
            }
            binding.sendButton.isEnabled = state.canSend
            binding.sendButton.setText(if (state.sending) R.string.dispute_sending else R.string.dispute_send)
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                CreateDisputeViewModel.Event.Sent -> leave()
                is CreateDisputeViewModel.Event.Message -> Snackbar.make(binding.root, event.text, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun bindOrder(binding: FragmentCreateDisputeBinding, state: CreateDisputeViewModel.State) {
        val order = state.order
        binding.orderCard.isVisible = order != null
        if (order == null) return
        binding.orderCode.text = getString(R.string.dispute_order_code, order.code)
        val partner = order.primary?.partner?.displayName?.trim().orEmpty()
        val title = order.title.trim()
        binding.orderLine.text = when {
            title.isNotEmpty() && partner.isNotEmpty() -> getString(R.string.dispute_order_line, title, partner)
            else -> title.ifEmpty { partner }
        }
        binding.orderLine.isVisible = binding.orderLine.text.isNotEmpty()
    }

    private fun descriptionMessage(error: DescriptionError): String = when (error) {
        DescriptionError.EMPTY -> getString(R.string.dispute_description_required)
        DescriptionError.TOO_SHORT -> getString(R.string.dispute_description_too_short, DisputeRules.DESCRIPTION_MIN)
        DescriptionError.TOO_LONG -> getString(R.string.dispute_description_too_long, DisputeRules.DESCRIPTION_MAX)
        DescriptionError.CONTACT_INFO -> getString(R.string.create_request_contact_info)
    }

    private fun openGallery() {
        val free = viewModel.state.value.freeSlots
        val request = PickVisualMediaRequest(PickVisualMedia.ImageOnly)
        when {
            free <= 0 -> Unit
            free == 1 -> pickOne.launch(request)
            else -> pickMany.getValue(free).launch(request)
        }
    }

    /**
     * Back to CS-20 with "Đã gửi khiếu nại". From CS-20 that is one step back; from CS-23 (opened from CS-19 or CS-20)
     * CS-23 is closed too, and CS-20 is opened when it is not below it.
     */
    private fun leave() {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs33) return
        if (navController.previousBackStackEntry?.destination?.id == R.id.cs20) {
            OrderRoutes.leaveWith(this, R.string.dispute_sent)
            return
        }
        navController.popBackStack(R.id.cs33, true)
        if (navController.currentDestination?.id == R.id.cs23) navController.popBackStack()
        if (navController.currentDestination?.id != R.id.cs20) {
            navController.navigate(R.id.cs20, bundleOf(OrderDetailViewModel.ARG_ORDER_ID to viewModel.orderId))
        }
        OrderRoutes.leaveForCurrent(navController, R.string.dispute_sent)
    }

    private companion object {
        const val THUMBNAIL_EDGE = 480
    }
}

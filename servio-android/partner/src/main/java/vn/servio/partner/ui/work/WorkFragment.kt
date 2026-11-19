package vn.servio.partner.ui.work

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import vn.servio.core.utils.Money
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.data.image.CameraCaptureFiles
import vn.servio.partner.data.model.ExtraChargeStatus
import vn.servio.partner.data.model.OrderDetail
import vn.servio.partner.data.model.OrderStatus
import vn.servio.partner.databinding.FragmentWorkBinding
import vn.servio.partner.databinding.ItemStatusRowBinding
import vn.servio.partner.ui.common.LoadStatus
import vn.servio.partner.ui.common.PhotoPicker
import vn.servio.partner.ui.common.Texts
import vn.servio.partner.ui.common.bindPhotoStrip
import vn.servio.partner.ui.order.OrderDetailFragment
import vn.servio.partner.ui.order.OrderTexts
import vn.servio.partner.ui.work.WorkViewModel.Event
import javax.inject.Inject

/** PS-16 Thực hiện công việc (ARRIVED → IN_PROGRESS) and PS-16a while working. */
@AndroidEntryPoint
class WorkFragment : Fragment(R.layout.fragment_work) {

    @Inject lateinit var captureFiles: CameraCaptureFiles

    private val viewModel: WorkViewModel by viewModels()
    private val photoPicker = PhotoPicker(this, { captureFiles }) { uri -> viewModel.beforePhotos.add(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFragmentResultListener(ExtraChargeSheet.RESULT_KEY) { _, _ ->
            view?.let { Snackbar.make(it, R.string.extra_sent, Snackbar.LENGTH_LONG).show() }
            viewModel.load(showSpinner = false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentWorkBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.noShowButton.setOnClickListener { askNoShow() }

        collectWhenStarted(viewModel.state) { state ->
            LoadStatus.bind(binding.status, state, binding.content) { viewModel.load() }
            binding.actionBar.root.isVisible = state is UiState.Content
            if (state is UiState.Content) bind(binding, state.data)
        }
        collectWhenStarted(viewModel.beforePhotos.photos.combine(viewModel.state) { photos, state -> photos to state }) { (photos, state) ->
            val order = (state as? UiState.Content)?.data ?: return@collectWhenStarted
            if (order.assignment?.status == OrderStatus.ARRIVED) {
                bindPhotoStrip(binding.beforeStrip, photos, viewModel.beforePhotos.max, onAdd = { photoPicker.show() },
                    onRemove = viewModel.beforePhotos::remove)
                binding.actionBar.primaryButton.isEnabled =
                    photos.any { it.url != null } && photos.none { it.uploading } && !viewModel.busy.value
            }
        }
        collectWhenStarted(viewModel.beforePhotos.errors) { error ->
            if (error != null) {
                Snackbar.make(view, error, Snackbar.LENGTH_LONG).show()
                viewModel.beforePhotos.consumeError()
            }
        }
        collectWhenStarted(viewModel.busy) { busy -> if (busy) binding.actionBar.primaryButton.isEnabled = false }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is Event.Message -> Snackbar.make(view, event.text, Snackbar.LENGTH_LONG).show()
                Event.Started -> Snackbar.make(view, R.string.work_started, Snackbar.LENGTH_LONG).show()
                Event.NoShowReported -> {
                    Snackbar.make(view, R.string.work_no_show_done, Snackbar.LENGTH_LONG).show()
                    findNavController().navigateUp()
                }
            }
        }
    }

    private fun bind(binding: FragmentWorkBinding, order: OrderDetail) {
        val assignment = order.assignment ?: return
        val status = assignment.status
        binding.stateTitle.setText(OrderTexts.status(status))
        binding.stateNote.text = when (status) {
            OrderStatus.ARRIVED -> getString(R.string.work_arrived_at, Texts.dateTime(assignment.arrivedAt), order.code)
            OrderStatus.IN_PROGRESS -> getString(R.string.work_started_at, Texts.dateTime(assignment.startedAt))
            else -> getString(R.string.order_code, order.code)
        }

        val arrived = status == OrderStatus.ARRIVED
        binding.beforeStrip.isVisible = arrived
        binding.beforeHint.text = if (arrived) {
            getString(R.string.work_before_hint)
        } else {
            getString(R.string.work_before_count, assignment.beforeImages.size)
        }
        binding.noShowButton.isVisible = arrived

        val working = status == OrderStatus.IN_PROGRESS
        binding.extraGroup.isVisible = working || order.extraCharges.isNotEmpty()
        binding.addExtraButton.isVisible = working
        binding.addExtraButton.setOnClickListener {
            findNavController().navigate(
                R.id.ps17,
                bundleOf(OrderDetailFragment.ARG_ORDER_ID to order.id, ExtraChargeSheet.ARG_ASSIGNMENT_ID to assignment.id),
            )
        }
        binding.extraEmpty.isVisible = order.extraCharges.isEmpty()
        binding.extraList.removeAllViews()
        order.extraCharges.forEach { charge ->
            val state = when (charge.status) {
                ExtraChargeStatus.PENDING -> getString(R.string.extra_status_pending)
                ExtraChargeStatus.APPROVED -> getString(R.string.extra_status_approved)
                ExtraChargeStatus.REJECTED -> getString(R.string.extra_status_rejected)
            }
            addRow(binding.extraList, "${charge.description} · $state", Money.format(charge.amount))
        }

        binding.pricing.removeAllViews()
        addRow(binding.pricing, getString(R.string.order_price_quote), Money.format(order.pricing.subTotal))
        if (order.pricing.extraChargeTotal > 0) {
            addRow(binding.pricing, getString(R.string.order_price_extra), Money.format(order.pricing.extraChargeTotal))
        }
        addRow(binding.pricing, getString(R.string.order_price_payment), getString(R.string.order_payment_cash))

        val primary = binding.actionBar.primaryButton
        when (status) {
            OrderStatus.ARRIVED -> {
                primary.setText(R.string.work_start)
                primary.setOnClickListener { viewModel.startWork() }
            }
            OrderStatus.IN_PROGRESS -> {
                val pending = order.extraCharges.any { it.status == ExtraChargeStatus.PENDING }
                primary.setText(if (pending) R.string.work_complete_waiting_extra else R.string.work_complete)
                primary.isEnabled = !pending
                primary.setOnClickListener {
                    findNavController().navigate(R.id.ps18, bundleOf(OrderDetailFragment.ARG_ORDER_ID to order.id))
                }
            }
            else -> {
                primary.setText(R.string.work_go_cash)
                primary.isEnabled = true
                primary.setOnClickListener {
                    findNavController().navigate(R.id.ps19, bundleOf(OrderDetailFragment.ARG_ORDER_ID to order.id))
                }
            }
        }
    }

    private fun addRow(container: ViewGroup, label: String, value: String) {
        val row = ItemStatusRowBinding.inflate(layoutInflater, container, false)
        row.label.text = label
        row.value.text = value
        container.addView(row.root)
    }

    /** #180 needs: arrived by GPS, waited 15 minutes, messaged the customer. The server says which one is missing. */
    private fun askNoShow() {
        val context = requireContext()
        val layout = TextInputLayout(context).apply {
            val padding = resources.getDimensionPixelSize(vn.servio.core.R.dimen.screen_padding)
            setPadding(padding, 0, padding, 0)
            hint = getString(R.string.work_no_show_note)
        }
        val input = TextInputEditText(layout.context)
        layout.addView(input)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.work_no_show)
            .setMessage(R.string.work_no_show_message)
            .setView(layout)
            .setNegativeButton(R.string.action_close, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.reportNoShow(input.text?.toString().orEmpty()) }
            .show()
    }
}

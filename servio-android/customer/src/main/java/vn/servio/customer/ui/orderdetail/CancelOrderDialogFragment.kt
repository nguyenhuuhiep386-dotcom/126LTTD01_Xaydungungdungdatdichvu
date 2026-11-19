package vn.servio.customer.ui.orderdetail

import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import vn.servio.customer.R
import vn.servio.customer.databinding.DialogCancelOrderBinding
import vn.servio.customer.ui.order.OrderFormat

/**
 * CS-20c Huỷ đơn, shown by CS-20 in its child fragment manager. Asks #151 on every opening; answers CS-20 with a
 * fragment result ([RESULT_KEY]): the fee of #72, or [RESULT_NOT_CANCELLABLE]. "Giữ đơn" and Back just close.
 */
@AndroidEntryPoint
class CancelOrderDialogFragment : DialogFragment() {

    private val viewModel: CancelOrderViewModel by viewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogCancelOrderBinding.inflate(layoutInflater)
        val code = requireArguments().getString(ARG_CODE).orEmpty()
        val reasons = resources.getStringArray(R.array.order_cancel_reasons).toList()

        binding.reason.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, reasons))
        binding.reason.setOnItemClickListener { _, _, position, _ ->
            viewModel.selectReason(position)
            // Back to the normal outline once a reason is picked (Figma), the menu still opens on a tap.
            binding.reason.clearFocus()
        }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.keepButton.setOnClickListener { dismiss() }
        binding.confirmButton.setOnClickListener {
            val index = viewModel.state.value.reasonIndex ?: return@setOnClickListener
            reasons.getOrNull(index)?.let(viewModel::confirm)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { bind(binding, it, reasons) } }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is CancelOrderViewModel.Event.Cancelled -> finish(bundleOf(RESULT_FEE to event.fee))
                            CancelOrderViewModel.Event.NotCancellable -> finish(bundleOf(RESULT_NOT_CANCELLABLE to true))
                        }
                    }
                }
            }
        }

        return MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Servio_AlertDialog_CancelOrder)
            .setTitle(getString(R.string.order_cancel_title, code))
            .setView(binding.root)
            .create()
    }

    private fun bind(binding: DialogCancelOrderBinding, state: CancelOrderViewModel.State, reasons: List<String>) {
        val context = requireContext()
        val ui = state.ui
        val loaded = state.preview != null
        binding.progress.isVisible = state.loading && !loaded
        binding.loadErrorGroup.isVisible = !loaded && !state.loading && state.loadError != null
        binding.loadError.text = state.loadError
        binding.form.isVisible = loaded

        binding.lead.setText(
            when {
                !ui.showFee -> R.string.order_cancel_lead_free
                ui.feeReason == CancelDialogUi.FeeReason.ARRIVED -> R.string.order_cancel_lead_arrived
                ui.feeReason == CancelDialogUi.FeeReason.ON_THE_WAY -> R.string.order_cancel_lead_on_the_way
                else -> R.string.order_cancel_lead_fee
            },
        )
        binding.feeBox.isVisible = ui.showFee
        binding.feeLabel.setText(
            when (ui.feeReason) {
                CancelDialogUi.FeeReason.ARRIVED -> R.string.order_cancel_fee_arrived
                CancelDialogUi.FeeReason.ON_THE_WAY -> R.string.order_cancel_fee_on_the_way
                null -> R.string.order_cancel_fee
            },
        )
        val money = ui.fee?.let { OrderFormat.money(context, it) }
        binding.feeAmount.text = money
        binding.debtNote.isVisible = ui.showFee
        binding.debtNote.text = ui.debtLimit
            ?.let { getString(R.string.order_cancel_debt_note, OrderFormat.money(context, it)) }
            ?: getString(R.string.order_cancel_debt_note_no_limit)
        binding.feeChanged.isVisible = state.feeChanged

        val chosen = state.reasonIndex?.let(reasons::getOrNull)
        if (chosen != null && binding.reason.text.toString() != chosen) binding.reason.setText(chosen, false)

        binding.sendError.isVisible = state.sendError != null
        binding.sendError.text = state.sendError
        binding.confirmButton.text = if (ui.confirmWithFee && money != null) getString(R.string.order_cancel_confirm_fee, money) else getString(R.string.order_cancel_confirm)
        binding.confirmButton.isEnabled = ui.confirmEnabled
        binding.confirmButton.alpha = if (ui.confirmEnabled) 1f else DISABLED_ALPHA
        binding.keepButton.isEnabled = !state.sending
        binding.reasonLayout.isEnabled = !state.sending
    }

    private fun finish(result: Bundle) {
        setFragmentResult(RESULT_KEY, result)
        dismissAllowingStateLoss()
    }

    companion object {
        const val TAG = "cancelOrder"
        const val RESULT_KEY = "cancelOrderResult"
        const val RESULT_FEE = "fee"
        const val RESULT_NOT_CANCELLABLE = "notCancellable"
        private const val ARG_CODE = "code"
        private const val DISABLED_ALPHA = 0.38f

        fun newInstance(orderId: String, code: String) = CancelOrderDialogFragment().apply {
            arguments = bundleOf(CancelOrderViewModel.ARG_ORDER_ID to orderId, ARG_CODE to code)
        }
    }
}

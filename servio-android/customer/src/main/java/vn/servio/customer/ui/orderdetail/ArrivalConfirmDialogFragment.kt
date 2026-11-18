package vn.servio.customer.ui.orderdetail

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.servio.customer.R
import vn.servio.customer.data.model.ArrivalConfirmation
import vn.servio.customer.databinding.DialogArrivalConfirmBinding
import vn.servio.customer.ui.order.OrderTexts

/**
 * CS-20b, shown by [ArrivalPrompt] on CS-20 and CS-21 (in their child fragment manager). A tap outside does nothing;
 * Back closes it but the request stays kept, so the screen shows it again on its next resume while it is valid.
 * Answers the host with a fragment result ([RESULT_KEY], [RESULT_OUTCOME] = one of the OUTCOME_ values).
 */
@AndroidEntryPoint
class ArrivalConfirmDialogFragment : DialogFragment() {

    private val viewModel: ArrivalConfirmViewModel by viewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogArrivalConfirmBinding.inflate(layoutInflater)
        val args = requireArguments()
        val partner = args.getString(ARG_PARTNER).orEmpty().ifBlank { getString(R.string.arrival_partner_fallback) }
        val address = args.getString(ARG_ADDRESS).orEmpty().ifBlank { getString(R.string.arrival_address_fallback) }
        binding.body.text = getString(R.string.arrival_body, partner, address)
        binding.denyButton.setOnClickListener { viewModel.answer(confirmed = false) }
        binding.confirmButton.setOnClickListener { viewModel.answer(confirmed = true) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.denyButton.isEnabled = state.buttonsEnabled
                        binding.confirmButton.isEnabled = state.buttonsEnabled
                        binding.error.isVisible = state.error != null
                        binding.error.text = state.error
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        val outcome = when (event) {
                            ArrivalConfirmViewModel.Event.Confirmed -> OUTCOME_CONFIRMED
                            ArrivalConfirmViewModel.Event.Denied -> OUTCOME_DENIED
                            ArrivalConfirmViewModel.Event.Expired -> OUTCOME_EXPIRED
                            ArrivalConfirmViewModel.Event.Conflict -> OUTCOME_CONFLICT
                        }
                        setFragmentResult(RESULT_KEY, bundleOf(RESULT_OUTCOME to outcome))
                        dismissAllowingStateLoss()
                    }
                }
                launch {
                    while (true) {
                        val left = viewModel.secondsLeft() ?: 0
                        binding.countdown.text = getString(R.string.arrival_expires_in, OrderTexts.countdown(left))
                        if (left <= 0) {
                            viewModel.expired()
                            break
                        }
                        delay(1_000L - System.currentTimeMillis() % 1_000L)
                    }
                }
            }
        }

        return MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Servio_AlertDialog)
            .setView(binding.root)
            .create()
            .apply { setCanceledOnTouchOutside(false) }
    }

    /** Back: closed without an answer; the host does not show this request again until it resumes. */
    override fun onCancel(dialog: DialogInterface) {
        setFragmentResult(RESULT_KEY, bundleOf(RESULT_OUTCOME to OUTCOME_CLOSED, RESULT_REQUEST_ID to viewModel.request.requestId))
        super.onCancel(dialog)
    }

    companion object {
        const val TAG = "arrivalConfirm"
        const val RESULT_KEY = "arrivalConfirmResult"
        const val RESULT_OUTCOME = "outcome"
        const val RESULT_REQUEST_ID = "requestId"
        const val OUTCOME_CONFIRMED = "confirmed"
        const val OUTCOME_DENIED = "denied"
        const val OUTCOME_EXPIRED = "expired"
        const val OUTCOME_CONFLICT = "conflict"
        const val OUTCOME_CLOSED = "closed"
        private const val ARG_PARTNER = "partner"
        private const val ARG_ADDRESS = "address"

        fun newInstance(request: ArrivalConfirmation, partnerName: String?, address: String?) = ArrivalConfirmDialogFragment().apply {
            arguments = bundleOf(
                ArrivalConfirmViewModel.ARG_ORDER_ID to request.orderId,
                ArrivalConfirmViewModel.ARG_ASSIGNMENT_ID to request.assignmentId,
                ArrivalConfirmViewModel.ARG_REQUEST_ID to request.requestId,
                ArrivalConfirmViewModel.ARG_EXPIRES_AT to request.expiresAt,
                ARG_PARTNER to partnerName,
                ARG_ADDRESS to address,
            )
        }
    }
}

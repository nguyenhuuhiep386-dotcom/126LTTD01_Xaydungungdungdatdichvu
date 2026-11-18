package vn.servio.customer.ui.orderdetail

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.ExtraCharge
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.databinding.SheetExtraChargeBinding
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.ui.order.OrderFormat
import vn.servio.customer.ui.order.OrderTexts
import javax.inject.Inject

/**
 * CS-22 Duyệt chi phí phát sinh, a child of CS-20 ([OrderDetailFragment]) sharing its view model, so the charge, the
 * running #74 and its error survive a rotation. Shows the charge [ARG_CHARGE_ID] while #63 still has it PENDING and
 * closes itself (without counting as closed by the customer) once it is answered, no longer pending or held after a
 * 409/404. Closing it by hand (Back, outside, swipe down) tells the view model, so it is not opened again by itself.
 */
@AndroidEntryPoint
class ExtraChargeSheet : BottomSheetDialogFragment() {

    @Inject lateinit var httpClient: OkHttpClient

    private val viewModel: OrderDetailViewModel by viewModels(ownerProducer = { requireParentFragment() })

    private var closing = false
    private var shownImage: String? = null

    private val chargeId: String get() = requireArguments().getString(ARG_CHARGE_ID).orEmpty()

    override fun getTheme() = R.style.ThemeOverlay_Servio_BottomSheetDialog

    /** Always fully open, also in landscape (as CS-16); dragging down closes it. */
    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetExtraChargeBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = SheetExtraChargeBinding.bind(view)
        val edge = (EVIDENCE_EDGE_DP * resources.displayMetrics.density).toInt()
        val images = RemoteImages(httpClient, viewLifecycleOwner.lifecycleScope, edge)
        shownImage = null
        binding.rejectButton.setOnClickListener { viewModel.answerCharge(chargeId, approved = false) }
        binding.approveButton.setOnClickListener { viewModel.answerCharge(chargeId, approved = true) }

        collectWhenStarted(viewModel.state) { state ->
            val order = state.detail
            val charge = order?.let { d -> OrderDetailUi.pendingCharges(d).firstOrNull { it.id.equals(chargeId, ignoreCase = true) } }
            if (order == null || charge == null || charge.id.equals(state.heldCharge, ignoreCase = true)) {
                close()
                return@collectWhenStarted
            }
            binding.bind(order, charge, images)
            val answering = state.answeringCharge != null
            binding.rejectButton.isEnabled = !answering
            binding.approveButton.isEnabled = !answering
            binding.errorText.isVisible = state.chargeError != null
            binding.errorText.text = state.chargeError
        }
    }

    private fun SheetExtraChargeBinding.bind(order: OrderDetail, charge: ExtraCharge, images: RemoteImages) {
        val context = root.context
        val partner = order.assignments.firstOrNull { it.id.equals(charge.assignmentId, ignoreCase = true) }?.partner ?: order.primary?.partner
        subtitleText.text = listOfNotNull(
            getString(R.string.extra_charge_order, order.code),
            partner?.displayName?.takeIf { it.isNotBlank() },
            OrderTexts.time(charge.createdAt),
        ).joinToString(getString(R.string.order_step_separator))

        val url = charge.evidenceImageUrl?.takeIf { it.isNotBlank() }
        evidenceFrame.isVisible = url != null
        if (url != null && url != shownImage) {
            shownImage = url
            images.load(url, evidenceImage)
        }
        val amount = OrderFormat.money(context, charge.amount)
        amountText.text = amount
        descriptionText.text = charge.description.trim()

        val bill = OrderDetailUi.bill(order, charge)
        quotedText.text = OrderFormat.money(context, bill.quoted)
        approvedRow.isVisible = bill.approvedExtras != null
        approvedText.text = bill.approvedExtras?.let { getString(R.string.extra_charge_plus, OrderFormat.money(context, it)) }
        thisText.text = getString(R.string.extra_charge_plus, OrderFormat.money(context, bill.charge))
        totalText.text = OrderFormat.money(context, bill.newTotal)
        approveButton.text = getString(R.string.extra_charge_approve, amount)
    }

    /** Back, a tap outside or a swipe down: the customer put this charge aside for now. */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (!closing) viewModel.chargeClosed(chargeId)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Another pending charge may be waiting; CS-20 decides (it only opens one while resumed).
        (parentFragment as? OrderDetailFragment)?.onChargeSheetGone()
    }

    private fun close() {
        if (closing) return
        closing = true
        dismissAllowingStateLoss()
    }

    companion object {
        const val TAG = "cs22"
        const val ARG_CHARGE_ID = "extraChargeId"

        /** The photo is shown at 88 dp; kept a little larger so it stays sharp. */
        private const val EVIDENCE_EDGE_DP = 176

        fun newInstance(chargeId: String) = ExtraChargeSheet().apply { arguments = bundleOf(ARG_CHARGE_ID to chargeId) }
    }
}

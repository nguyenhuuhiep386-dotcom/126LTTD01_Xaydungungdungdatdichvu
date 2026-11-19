package vn.servio.customer.ui.orderdetail

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import vn.servio.customer.R
import vn.servio.customer.data.repository.OrderUpdates

/**
 * Shows CS-20b on its host (CS-20 or CS-21) while [OrderUpdates] keeps a valid request for [orderId]: when the host
 * resumes and when a request comes while it is resumed. A request closed with Back is not shown again until the next
 * resume. Outcomes become a snackbar on the host; [reload] runs after a confirmation or a 409.
 *
 * Call [attach] from onViewCreated.
 */
class ArrivalPrompt(
    private val host: Fragment,
    private val orderUpdates: OrderUpdates,
    private val orderId: () -> String,
    /** Partner name and order address for the sentence; null while #63 has not answered. */
    private val names: () -> Pair<String?, String?>?,
    private val reload: () -> Unit,
) {
    private var closedRequestId: String? = null

    fun attach() {
        val owner = host.viewLifecycleOwner
        host.childFragmentManager.setFragmentResultListener(ArrivalConfirmDialogFragment.RESULT_KEY, owner) { _, result ->
            val message = when (result.getString(ArrivalConfirmDialogFragment.RESULT_OUTCOME)) {
                ArrivalConfirmDialogFragment.OUTCOME_CONFIRMED -> {
                    reload()
                    null
                }
                ArrivalConfirmDialogFragment.OUTCOME_DENIED -> R.string.arrival_denied
                ArrivalConfirmDialogFragment.OUTCOME_EXPIRED -> R.string.arrival_expired
                ArrivalConfirmDialogFragment.OUTCOME_CONFLICT -> {
                    reload()
                    R.string.arrival_conflict
                }
                else -> {
                    closedRequestId = result.getString(ArrivalConfirmDialogFragment.RESULT_REQUEST_ID)
                    null
                }
            }
            message?.let { text -> host.view?.let { Snackbar.make(it, text, Snackbar.LENGTH_LONG).show() } }
        }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                closedRequestId = null
                // StateFlow: the current version on every resume, then each change.
                orderUpdates.arrivalChanges().collect { showIfPending() }
            }
        }
    }

    /** Also called by the host once #63 answered (the sentence needs the partner's name and the address). */
    fun showIfPending() {
        if (!host.isResumed) return
        val manager = host.childFragmentManager
        if (manager.findFragmentByTag(ArrivalConfirmDialogFragment.TAG) != null || manager.isStateSaved) return
        val request = orderUpdates.pendingArrival(orderId()) ?: return
        if (request.requestId == closedRequestId) return
        val (partner, address) = names() ?: return
        ArrivalConfirmDialogFragment.newInstance(request, partner, address).show(manager, ArrivalConfirmDialogFragment.TAG)
    }
}

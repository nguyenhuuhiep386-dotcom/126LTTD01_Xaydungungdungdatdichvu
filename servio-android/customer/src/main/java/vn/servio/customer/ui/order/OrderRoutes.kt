package vn.servio.customer.ui.order

import android.view.View
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderStatus

/** Where an order opens, and the message a closing order screen leaves for the screen below it. */
object OrderRoutes {

    /** CS-19 card: an order waiting for the customer's confirmation opens CS-23 directly, every other one CS-20. */
    @IdRes
    fun cardDestination(status: OrderStatus): Int = if (status == OrderStatus.COMPLETED_BY_PARTNER) R.id.cs23 else R.id.cs20

    private const val KEY_MESSAGE = "orderMessage"

    /** Go back from CS-23 / CS-26, leaving [message] for the screen below (CS-19 or CS-20) to show as a snackbar. */
    fun leaveWith(fragment: Fragment, @StringRes message: Int) {
        val navController = fragment.findNavController()
        navController.previousBackStackEntry?.savedStateHandle?.set(KEY_MESSAGE, message)
        navController.popBackStack()
    }

    /** Called by CS-19 and CS-20 ([destination]) from onViewCreated: shows a message left for them, once. */
    fun listen(fragment: Fragment, @IdRes destination: Int, anchor: () -> View?) {
        val entry = runCatching { fragment.findNavController().getBackStackEntry(destination) }.getOrNull() ?: return
        entry.savedStateHandle.getLiveData<Int>(KEY_MESSAGE).observe(fragment.viewLifecycleOwner) { message ->
            if (message == null || message == 0) return@observe
            entry.savedStateHandle.remove<Int>(KEY_MESSAGE)
            anchor()?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
        }
    }
}

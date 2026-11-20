package vn.servio.customer.ui.notifications

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.AppNotification
import vn.servio.customer.databinding.FragmentNotificationsBinding
import vn.servio.customer.ui.notifications.NotificationTexts.Target
import vn.servio.customer.ui.orderdetail.OrderDetailViewModel
import vn.servio.customer.ui.requestdetail.QuoteFormat
import vn.servio.customer.ui.requestdetail.RequestDetailViewModel

/**
 * CS-31 Thông báo: grouped by VN day ("Hôm nay", "Hôm qua", "T5 19/11"), paged, pull to refresh, "Đọc hết". A tap
 * marks the row read and opens its order (CS-20), request (CS-15) or wallet (CS-30); other links are only marked read.
 */
@AndroidEntryPoint
class NotificationsFragment : Fragment(R.layout.fragment_notifications) {

    private val viewModel: NotificationsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentNotificationsBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        val readAllButton = binding.toolbar.menu.findItem(R.id.action_read_all).actionView as View
        readAllButton.setOnClickListener { viewModel.readAll() }

        val adapter = NotificationAdapter { notification ->
            viewModel.markRead(notification)
            open(notification)
        }
        binding.list.adapter = adapter
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lastVisible = (recyclerView.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
                if (dy > 0 && lastVisible >= adapter.itemCount - LOAD_MORE_BEFORE_END) viewModel.loadMore()
            }
        })
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        // The list is not the direct child of the SwipeRefreshLayout, so tell it when the list can still scroll up.
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ -> binding.list.canScrollVertically(-1) }
        binding.retryButton.setOnClickListener { viewModel.load() }

        val weekdays = QuoteFormat.weekdays(view.context)
        collectWhenStarted(viewModel.state) { state ->
            readAllButton.isEnabled = state.canReadAll
            // Widget.Servio.Button.Text has one text colour for every state, so show "disabled" by fading it.
            readAllButton.alpha = if (state.canReadAll) 1f else DISABLED_ALPHA
            binding.swipeRefresh.isRefreshing = state.refreshing
            binding.progress.isVisible = state.loading && !state.refreshing
            val empty = !state.loading && state.error == null && state.items.isEmpty()
            binding.list.isVisible = state.error == null
            binding.stateGroup.isVisible = state.error != null || empty
            binding.message.text = getString(if (state.error != null) R.string.notifications_load_failed else R.string.notifications_empty)
            binding.retryButton.isVisible = state.error != null
            adapter.submitList(NotificationTexts.lines(state.items, NotificationTexts.today(), weekdays))
        }
        collectWhenStarted(viewModel.events) { event ->
            val text = when (event) {
                NotificationsViewModel.Event.ReadAllFailed -> R.string.notifications_read_all_failed
                NotificationsViewModel.Event.RefreshFailed -> R.string.notifications_load_failed
            }
            Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun open(notification: AppNotification) {
        val nav = findNavController()
        when (val target = NotificationTexts.target(notification)) {
            is Target.Order -> nav.navigate(R.id.cs20, bundleOf(OrderDetailViewModel.ARG_ORDER_ID to target.orderId))
            is Target.Request -> nav.navigate(R.id.cs15, bundleOf(RequestDetailViewModel.ARG_REQUEST_ID to target.requestId))
            Target.Wallet -> nav.navigate(R.id.cs30)
            Target.None -> Unit
        }
    }

    private companion object {
        const val LOAD_MORE_BEFORE_END = 4
        const val DISABLED_ALPHA = 0.38f
    }
}

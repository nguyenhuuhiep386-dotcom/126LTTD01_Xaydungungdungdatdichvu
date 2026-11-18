package vn.servio.customer.ui.order

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderListFilter
import vn.servio.customer.databinding.FragmentMyOrdersBinding
import vn.servio.customer.ui.common.RemoteImages
import vn.servio.customer.ui.order.MyOrdersViewModel.State
import vn.servio.customer.ui.orderdetail.OrderDetailViewModel
import javax.inject.Inject

/**
 * CS-19 Đơn hàng (#62). Reloads every time the screen is opened or returned to; the selected tab is kept (the
 * ViewModel lives while CS-20 is on top). Live changes come through the ViewModel.
 */
@AndroidEntryPoint
class MyOrdersFragment : Fragment(R.layout.fragment_my_orders) {

    @Inject lateinit var httpClient: OkHttpClient

    private val viewModel: MyOrdersViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentMyOrdersBinding.bind(view)
        val images = RemoteImages(httpClient, viewLifecycleOwner.lifecycleScope, (RemoteImages.IMAGE_EDGE_DP * resources.displayMetrics.density).toInt())
        val adapter = OrderAdapter(images) { order -> openOrder(order.id) }
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
        binding.retryButton.setOnClickListener { viewModel.refresh() }

        val tabs = OrderListFilter.entries
        val inflater = LayoutInflater.from(view.context)
        tabs.forEach { tab ->
            val label = inflater.inflate(R.layout.view_request_tab, binding.tabs, false)
            binding.tabs.addTab(binding.tabs.newTab().setCustomView(label), tab == viewModel.state.value.selected)
        }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = viewModel.selectTab(tabs[tab.position])
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = binding.list.scrollToPosition(0)
        })

        collectWhenStarted(viewModel.state) { state ->
            bindTabs(binding, state)
            val current = state.current
            val empty = !current.loading && current.error == null && current.items.isEmpty()
            binding.swipeRefresh.isRefreshing = current.loading
            val atTop = !binding.list.canScrollVertically(-1)
            adapter.submitList(current.items) { if (atTop) binding.list.scrollToPosition(0) }
            binding.list.isVisible = current.error == null
            binding.stateGroup.isVisible = current.error != null || empty
            binding.message.text = getString(if (current.error != null) R.string.orders_load_failed else emptyText(state.selected))
            binding.retryButton.isVisible = current.error != null
        }

        viewModel.refresh()
    }

    private fun openOrder(orderId: String) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.cs19) return
        navController.navigate(R.id.cs20, bundleOf(OrderDetailViewModel.ARG_ORDER_ID to orderId))
    }

    /** Only "Đang diễn ra" carries its number, once #62 answered; the selected label is bold and dark. */
    private fun bindTabs(binding: FragmentMyOrdersBinding, state: State) {
        OrderListFilter.entries.forEachIndexed { index, tab ->
            val tabItem = binding.tabs.getTabAt(index) ?: return@forEachIndexed
            val label = tabItem.customView as? TextView ?: return@forEachIndexed
            val count = state.tabs.getValue(tab).totalCount.takeIf { tab == OrderListFilter.ACTIVE }
            val text = if (count == null) getString(tabTitle(tab)) else getString(R.string.orders_tab_with_count, getString(tabTitle(tab)), count)
            if (label.text.toString() != text) {
                label.text = text
                // Set the custom view again so the tab (and its indicator) takes the new width.
                tabItem.customView = null
                tabItem.customView = label
            }
            val selected = tab == state.selected
            label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            label.setTextColor(
                requireContext().getColor(if (selected) vn.servio.core.R.color.servio_text_primary else vn.servio.core.R.color.servio_text_secondary),
            )
        }
    }

    private fun tabTitle(tab: OrderListFilter) = when (tab) {
        OrderListFilter.ACTIVE -> R.string.orders_tab_active
        OrderListFilter.COMPLETED -> R.string.orders_tab_completed
        OrderListFilter.CANCELLED -> R.string.orders_tab_cancelled
    }

    private fun emptyText(tab: OrderListFilter) = when (tab) {
        OrderListFilter.ACTIVE -> R.string.orders_empty_active
        OrderListFilter.COMPLETED -> R.string.orders_empty_completed
        OrderListFilter.CANCELLED -> R.string.orders_empty_cancelled
    }

    private companion object {
        const val LOAD_MORE_BEFORE_END = 5
    }
}

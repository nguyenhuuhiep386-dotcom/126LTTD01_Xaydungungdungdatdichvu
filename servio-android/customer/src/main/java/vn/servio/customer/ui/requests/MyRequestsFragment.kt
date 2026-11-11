package vn.servio.customer.ui.requests

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.databinding.FragmentMyRequestsBinding
import vn.servio.customer.ui.requests.MyRequestsViewModel.Tab

/** CS-14 Yêu cầu của tôi (#37). Reloads every time the screen is opened or returned to. */
@AndroidEntryPoint
class MyRequestsFragment : Fragment(R.layout.fragment_my_requests) {

    private val viewModel: MyRequestsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentMyRequestsBinding.bind(view)
        val adapter = RequestAdapter { request ->
            findNavController().navigate(R.id.cs15, bundleOf("requestId" to request.id))
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
        binding.retryButton.setOnClickListener { viewModel.refresh() }

        val tabs = Tab.entries
        tabs.forEach { binding.tabs.addTab(binding.tabs.newTab().setText(tabTitle(it)), it == viewModel.state.value.selected) }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = viewModel.selectTab(tabs[tab.position])
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = binding.list.scrollToPosition(0)
        })

        collectWhenStarted(viewModel.state) { state ->
            tabs.forEachIndexed { index, tab ->
                val count = state.tabs.getValue(tab).totalCount
                binding.tabs.getTabAt(index)?.text =
                    if (count == null) getString(tabTitle(tab)) else getString(R.string.requests_tab_with_count, getString(tabTitle(tab)), count)
            }
            val current = state.current
            val empty = !current.loading && current.error == null && current.items.isEmpty()
            binding.swipeRefresh.isRefreshing = current.loading
            adapter.submitList(current.items)
            binding.list.isVisible = current.error == null
            binding.stateGroup.isVisible = current.error != null || empty
            binding.message.text = current.error ?: getString(emptyText(state.selected))
            binding.retryButton.isVisible = current.error != null
        }

        viewModel.refresh()
    }

    private fun tabTitle(tab: Tab) = when (tab) {
        Tab.OPEN -> R.string.requests_tab_open
        Tab.MATCHED -> R.string.requests_tab_matched
        Tab.HISTORY -> R.string.requests_tab_history
    }

    private fun emptyText(tab: Tab) = when (tab) {
        Tab.OPEN -> R.string.requests_empty_open
        Tab.MATCHED -> R.string.requests_empty_matched
        Tab.HISTORY -> R.string.requests_empty_history
    }

    private companion object {
        const val LOAD_MORE_BEFORE_END = 5
    }
}

package vn.servio.customer.ui.requests

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
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
import vn.servio.customer.ui.requests.MyRequestsViewModel.State
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
        binding.newRequestButton.setOnClickListener { findNavController().navigate(R.id.cs07) }
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
            // A request posted meanwhile is inserted above the first card: stay at the top instead of keeping
            // the old first card in place (the new one would sit half hidden under the tabs).
            val atTop = !binding.list.canScrollVertically(-1)
            adapter.submitList(state.cards) { if (atTop) binding.list.scrollToPosition(0) }
            binding.list.isVisible = current.error == null
            binding.stateGroup.isVisible = current.error != null || empty
            binding.message.text = current.error ?: getString(emptyText(state.selected))
            binding.retryButton.isVisible = current.error != null
        }

        viewModel.refresh()
    }

    /** Figma: only "Đang mở" carries its number; the selected label is bold and dark, the others grey. */
    private fun bindTabs(binding: FragmentMyRequestsBinding, state: State) {
        Tab.entries.forEachIndexed { index, tab ->
            val tabItem = binding.tabs.getTabAt(index) ?: return@forEachIndexed
            val label = tabItem.customView as? TextView ?: return@forEachIndexed
            val count = state.tabs.getValue(tab).totalCount.takeIf { tab == Tab.OPEN }
            val text = if (count == null) getString(tabTitle(tab)) else getString(R.string.requests_tab_with_count, getString(tabTitle(tab)), count)
            if (label.text.toString() != text) {
                label.text = text
                // The tab view keeps the width of a longer label ("Đang mở (5)" → "Đang mở" after a failed reload)
                // until its custom view is set again; the indicator follows the new width.
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

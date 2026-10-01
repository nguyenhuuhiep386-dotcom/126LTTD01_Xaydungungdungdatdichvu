package vn.servio.customer.ui.requests

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.databinding.FragmentMyRequestsBinding

/** CS-14 Yêu cầu của tôi — sample data from FakeServiceRequestRepository until #37 is ready. */
@AndroidEntryPoint
class MyRequestsFragment : Fragment(R.layout.fragment_my_requests) {

    private val viewModel: MyRequestsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentMyRequestsBinding.bind(view)
        val adapter = RequestAdapter { request ->
            findNavController().navigate(R.id.cs15, bundleOf("requestId" to request.id))
        }
        binding.list.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }

        val tabs = MyRequestsViewModel.Tab.entries
        binding.tabs.getTabAt(tabs.indexOf(viewModel.selectedTab.value))?.select()
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = viewModel.selectTab(tabs[tab.position])
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = state is UiState.Loading
            binding.message.isVisible = state is UiState.Error || (state is UiState.Content && state.data.isEmpty())
            when (state) {
                UiState.Loading -> Unit
                is UiState.Error -> binding.message.text = state.message
                is UiState.Content -> {
                    adapter.submitList(state.data)
                    binding.message.setText(R.string.requests_empty)
                }
            }
        }
    }
}

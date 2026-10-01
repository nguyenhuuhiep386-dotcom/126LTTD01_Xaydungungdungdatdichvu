package vn.servio.partner.ui.feed

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.partner.R
import vn.servio.partner.databinding.FragmentFeedBinding

/** PS-07 Newsfeed — sample data from FakeFeedRepository until #46 and the NewPost hub event are ready. */
@AndroidEntryPoint
class FeedFragment : Fragment(R.layout.fragment_feed) {

    private val viewModel: FeedViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentFeedBinding.bind(view)
        val adapter = FeedAdapter { item ->
            findNavController().navigate(R.id.ps10, bundleOf("requestId" to item.requestId))
        }
        binding.list.adapter = adapter
        binding.filterButton.setOnClickListener { findNavController().navigate(R.id.ps08) }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = state is UiState.Loading
            binding.message.isVisible = state is UiState.Error || (state is UiState.Content && state.data.isEmpty())
            when (state) {
                UiState.Loading -> Unit
                is UiState.Error -> binding.message.text = state.message
                is UiState.Content -> {
                    adapter.submitList(state.data)
                    binding.message.setText(R.string.feed_empty)
                }
            }
        }
    }
}

package vn.servio.customer.ui.home

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.databinding.FragmentHomeBinding
import vn.servio.customer.ui.category.CategoryListViewModel
import vn.servio.customer.ui.notifications.NotificationTexts

/** CS-06 Trang chủ (demo version): greeting, "Đăng yêu cầu" and categories from GET /service-categories (#29). */
@AndroidEntryPoint
class HomeFragment : Fragment(R.layout.fragment_home) {

    private val viewModel: HomeViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentHomeBinding.bind(view)
        val adapter = CategoryAdapter { category ->
            findNavController().navigate(R.id.cs07, bundleOf(CategoryListViewModel.ARG_CATEGORY_ID to category.id))
        }
        binding.categoryList.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.categoryList.adapter = adapter

        binding.postRequestButton.setOnClickListener { findNavController().navigate(R.id.cs07) }
        binding.seeAllButton.setOnClickListener { findNavController().navigate(R.id.cs07) }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }
        binding.bellButton.setOnClickListener { findNavController().navigate(R.id.cs31) }

        collectWhenStarted(viewModel.unread) { unread ->
            val badge = NotificationTexts.badge(unread)
            binding.bellBadge.isVisible = badge != null
            binding.bellBadge.text = badge
            binding.bellButton.contentDescription =
                if (badge == null) getString(R.string.home_notifications) else getString(R.string.home_notifications_unread, badge)
        }

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = state is UiState.Loading
            binding.errorGroup.isVisible = state is UiState.Error
            binding.categoryList.isVisible = state is UiState.Content
            when (state) {
                UiState.Loading -> Unit
                is UiState.Error -> binding.errorText.text = state.message
                is UiState.Content -> {
                    val name = state.data.firstName
                    binding.greeting.text = if (name.isBlank()) getString(R.string.app_name) else getString(R.string.home_greeting, name)
                    adapter.submitList(state.data.categories)
                }
            }
        }
    }

    /** Opening CS-06 again (tab, back from CS-31 or any other screen) asks #104 again. */
    override fun onStart() {
        super.onStart()
        viewModel.refreshUnread()
    }
}

package vn.servio.customer.ui.category

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.databinding.FragmentCategoryListBinding
import vn.servio.customer.ui.request.CreateRequestViewModel

/**
 * CS-07 Danh mục dịch vụ. Nav arguments: `categoryId` (level-1 group to preselect) and `keyword` (prefills search),
 * both optional. Picking a level-2 service opens create_request_graph (CS-08 → CS-11) with that service id.
 * With `pickResult` = true (CS-08 "Đổi") the picked id goes back to the previous destination through
 * savedStateHandle[RESULT_SERVICE_ID] instead, and no new flow is opened.
 */
@AndroidEntryPoint
class CategoryListFragment : Fragment(R.layout.fragment_category_list) {

    private val viewModel: CategoryListViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCategoryListBinding.bind(view)
        val pickResult = arguments?.getBoolean(ARG_PICK_RESULT) ?: false
        val groupAdapter = CategoryGroupAdapter { viewModel.selectGroup(it.id) }
        val serviceAdapter = ServiceAdapter { service ->
            val navController = findNavController()
            if (pickResult) {
                navController.previousBackStackEntry?.savedStateHandle?.set(RESULT_SERVICE_ID, service.id)
                navController.popBackStack()
            } else {
                navController.navigate(
                    R.id.create_request_graph,
                    bundleOf(CreateRequestViewModel.ARG_CATEGORY_ID to service.id),
                )
            }
        }
        binding.groupList.adapter = groupAdapter
        binding.serviceList.adapter = serviceAdapter

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.searchInput.setText(viewModel.initialKeyword)
        binding.searchInput.doAfterTextChanged { viewModel.setKeyword(it?.toString().orEmpty()) }

        // Scroll the right column back to the top only when what it lists changes, not on every emission.
        var shownList: String? = null
        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isVisible = state is UiState.Loading
            binding.errorGroup.isVisible = state is UiState.Error
            binding.content.isVisible = state is UiState.Content
            when (state) {
                UiState.Loading -> Unit
                is UiState.Error -> binding.errorText.text = state.message
                is UiState.Content -> {
                    val content = state.data
                    // Picking a group clears the keyword in the ViewModel; mirror it in the box.
                    if (binding.searchInput.text?.toString().orEmpty() != content.keyword) {
                        binding.searchInput.setText(content.keyword)
                    }
                    groupAdapter.submitList(
                        content.groups.map { CategoryGroupAdapter.Item(it, it.id == content.selectedGroup?.id) },
                    )
                    serviceAdapter.submitList(content.services)
                    bindHeader(binding, content)

                    val listKey = if (content.isSearching) "search:${content.keyword}" else content.selectedGroup?.id
                    if (listKey != shownList) {
                        shownList = listKey
                        binding.serviceScroll.scrollTo(0, 0)
                    }
                }
            }
        }
    }

    private fun bindHeader(binding: FragmentCategoryListBinding, content: CategoryListViewModel.Content) {
        val empty = content.services.isEmpty()
        binding.serviceEmpty.isVisible = empty
        if (content.isSearching) {
            binding.serviceHeader.text = getString(R.string.category_search_results)
            binding.serviceSubtitle.isVisible = !empty
            binding.serviceSubtitle.text =
                resources.getQuantityString(R.plurals.category_search_count, content.services.size, content.services.size)
            binding.serviceEmpty.text = getString(R.string.category_search_empty, content.keyword.trim())
        } else {
            binding.serviceHeader.text = content.selectedGroup?.name
            binding.serviceSubtitle.isVisible = true
            binding.serviceSubtitle.text = getString(R.string.category_group_hint)
            binding.serviceEmpty.text = getString(R.string.category_group_empty)
        }
    }

    companion object {
        const val ARG_PICK_RESULT = "pickResult"
        const val RESULT_SERVICE_ID = "picked_service_id"
    }
}

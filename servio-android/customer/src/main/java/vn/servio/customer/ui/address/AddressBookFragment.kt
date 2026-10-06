package vn.servio.customer.ui.address

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.R
import vn.servio.customer.data.model.Address
import vn.servio.customer.data.repository.AddressRepository
import vn.servio.customer.databinding.FragmentAddressBookBinding

/**
 * CS-13 Sổ địa chỉ — sample data from FakeAddressRepository until #12–#15 are wired.
 * With the nav argument `selectMode` = true the screen picks an address and returns its id to the previous
 * destination through savedStateHandle[RESULT_SELECTED_ADDRESS_ID].
 */
@AndroidEntryPoint
class AddressBookFragment : Fragment(R.layout.fragment_address_book) {

    private val viewModel: AddressBookViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentAddressBookBinding.bind(view)
        val selectMode = arguments?.getBoolean(ARG_SELECT_MODE) ?: false
        val adapter = AddressAdapter(
            selectMode = selectMode,
            onClick = { address -> if (selectMode) viewModel.select(address.id) else edit(address) },
            onEdit = ::edit,
            onLongClick = { anchor, address -> showActions(anchor, address, binding.list.adapter?.itemCount ?: 0) },
        )
        binding.list.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.addButton.setOnClickListener { findNavController().navigate(R.id.cs12) }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }
        binding.useSelectedButton.setOnClickListener {
            val selectedId = (viewModel.state.value as? UiState.Content)?.data?.selectedId ?: return@setOnClickListener
            findNavController().previousBackStackEntry?.savedStateHandle?.set(RESULT_SELECTED_ADDRESS_ID, selectedId)
            findNavController().navigateUp()
        }

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = state is UiState.Loading
            binding.errorGroup.isVisible = state is UiState.Error
            when (state) {
                UiState.Loading -> Unit
                is UiState.Error -> {
                    binding.content.isVisible = false
                    binding.selectBar.isVisible = false
                    binding.errorText.text = state.message
                }
                is UiState.Content -> {
                    val content = state.data
                    adapter.submitList(
                        content.addresses.map { AddressAdapter.Item(it, selectMode && it.id == content.selectedId) },
                    )
                    binding.content.isVisible = true
                    binding.emptyText.isVisible = content.addresses.isEmpty()
                    binding.addButton.isVisible = content.canAdd
                    binding.limitNote.text = getString(
                        if (content.canAdd) R.string.address_book_limit_note else R.string.address_book_limit_reached,
                        AddressRepository.MAX_ADDRESSES,
                    )
                    binding.selectBar.isVisible = selectMode && content.addresses.isNotEmpty()
                    binding.useSelectedButton.isEnabled = content.selectedId != null
                }
            }
        }
        collectWhenStarted(viewModel.messages) { message ->
            Snackbar.make(view, message, Snackbar.LENGTH_LONG).show()
        }

        // Reload on every return to this screen: CS-12 may have added or changed an address.
        viewModel.load()
    }

    private fun edit(address: Address) {
        findNavController().navigate(R.id.cs12, bundleOf(ARG_ADDRESS_ID to address.id))
    }

    private fun showActions(anchor: View, address: Address, addressCount: Int) {
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.address_actions, menu)
            menu.findItem(R.id.action_set_default).isVisible = !address.isDefault
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_set_default -> viewModel.setDefault(address.id)
                    R.id.action_delete -> confirmDelete(address, addressCount)
                }
                true
            }
        }.show()
    }

    private fun confirmDelete(address: Address, addressCount: Int) {
        // The default address can only be deleted when it is the last one.
        if (address.isDefault && addressCount > 1) {
            Snackbar.make(requireView(), R.string.address_book_delete_default_blocked, Snackbar.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Servio_AlertDialog_Destructive)
            .setMessage(getString(R.string.address_book_delete_confirm, address.label))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.delete(address.id) }
            .show()
    }

    companion object {
        const val ARG_SELECT_MODE = "selectMode"
        const val ARG_ADDRESS_ID = "addressId"
        const val RESULT_SELECTED_ADDRESS_ID = "selected_address_id"
    }
}

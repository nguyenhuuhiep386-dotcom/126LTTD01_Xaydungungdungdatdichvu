package vn.servio.customer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.ui.auth.navigateToLogin
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.BuildConfig
import vn.servio.customer.R
import vn.servio.customer.databinding.FragmentSettingsBinding
import vn.servio.customer.ui.settings.SettingsViewModel.Event
import vn.servio.customer.ui.settings.SettingsViewModel.LogoutState
import vn.servio.customer.ui.settings.SettingsViewModel.Page

/**
 * CS-32 Cài đặt: app version, "Quyền riêng tư" (the two pages, withdrawing the consent, deleting the account —
 * Luật 91/2025) and logout (#6). "Đăng xuất khỏi mọi thiết bị" of the Figma is not shown: the backend has no endpoint
 * that revokes the other sessions.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val viewModel: SettingsViewModel by viewModels()

    private var binding: FragmentSettingsBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSettingsBinding.bind(view).also { binding = it }
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.version.text = getString(R.string.settings_version_value, BuildConfig.VERSION_NAME)

        binding.privacyRow.setOnClickListener { viewModel.openPage(Page.PRIVACY) }
        binding.termsRow.setOnClickListener { viewModel.openPage(Page.TERMS) }
        binding.withdrawRow.setOnClickListener { confirmWithdraw() }
        binding.deleteAccountRow.setOnClickListener { explainDeletion() }
        binding.logoutRow.setOnClickListener {
            destructiveDialog()
                .setMessage(R.string.settings_logout_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(vn.servio.core.R.string.action_logout) { _, _ -> viewModel.logout() }
                .show()
        }

        collectWhenStarted(viewModel.logoutState) { state ->
            val idle = state == LogoutState.IDLE
            listOf(binding.logoutRow, binding.withdrawRow, binding.deleteAccountRow).forEach { it.isEnabled = idle }
            if (state == LogoutState.DONE) findNavController().navigateToLogin()
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is Event.OpenPage -> open(event.url)
                is Event.DeletionBlocked -> destructiveDialog()
                    .setTitle(R.string.settings_delete_blocked_title)
                    .setMessage(event.reason)
                    .setPositiveButton(R.string.action_close, null)
                    .show()
                is Event.Message -> showMessage(event.text)
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun confirmWithdraw() {
        destructiveDialog()
            .setTitle(R.string.settings_withdraw_title)
            .setMessage(R.string.settings_withdraw_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_withdraw_confirm) { _, _ -> viewModel.withdrawConsent() }
            .show()
    }

    /** Step 1: what is erased and what is kept anonymised; "Tiếp tục" leads to the final confirmation. */
    private fun explainDeletion() {
        destructiveDialog()
            .setTitle(R.string.settings_delete_title)
            .setMessage(R.string.settings_delete_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(vn.servio.core.R.string.action_continue) { _, _ -> confirmDeletion() }
            .show()
    }

    /** Step 2: the red "Xoá vĩnh viễn" sends DELETE /users/me. */
    private fun confirmDeletion() {
        destructiveDialog()
            .setTitle(R.string.settings_delete_confirm_title)
            .setMessage(R.string.settings_delete_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_delete_confirm) { _, _ -> viewModel.deleteAccount() }
            .show()
    }

    /** One overlay for all four privacy dialogs (withdraw, delete steps 1 and 2, blocked) so they look the same. */
    private fun destructiveDialog() =
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Servio_AlertDialog_Destructive)

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.settings_link_unavailable))
        }
    }

    private fun showMessage(text: String) {
        val root = binding?.root ?: return
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
    }
}

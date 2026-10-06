package vn.servio.customer.ui.settings

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
import vn.servio.customer.ui.settings.SettingsViewModel.LogoutState

/** CS-32 Cài đặt: app version and logout (#6, real API). */
@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSettingsBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.version.text = getString(R.string.settings_version_value, BuildConfig.VERSION_NAME)

        binding.logoutRow.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Servio_AlertDialog_Destructive)
                .setMessage(R.string.settings_logout_confirm)
                .setNegativeButton(R.string.settings_logout_cancel, null)
                .setPositiveButton(vn.servio.core.R.string.action_logout) { _, _ -> viewModel.logout() }
                .show()
        }
        // The backend only revokes the current session (#6); there is no "log out everywhere" endpoint yet.
        binding.logoutAllRow.setOnClickListener {
            Snackbar.make(view, R.string.settings_coming_soon, Snackbar.LENGTH_SHORT).show()
        }

        collectWhenStarted(viewModel.logoutState) { state ->
            binding.logoutRow.isEnabled = state == LogoutState.IDLE
            binding.logoutAllRow.isEnabled = state == LogoutState.IDLE
            if (state == LogoutState.DONE) findNavController().navigateToLogin()
        }
    }
}

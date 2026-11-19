package vn.servio.customer.ui.profile

import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.BuildConfig
import vn.servio.customer.R
import vn.servio.customer.data.model.ProfileSummary
import vn.servio.customer.databinding.FragmentProfileBinding
import vn.servio.customer.databinding.ItemProfileMenuBinding
import vn.servio.customer.ui.order.OrderFormat

/**
 * CS-29 Tài khoản: #9 and #12, plus the fee (#87) and unread (#104) counters, all reloaded on every resume.
 * No "Sửa" button of the Figma: it stays hidden until profile editing (#10) is built.
 */
@AndroidEntryPoint
class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private val viewModel: ProfileViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentProfileBinding.bind(view)
        binding.feeRow.setUp(R.drawable.ic_profile_wallet, R.string.account_fee_title, R.id.cs30)
        binding.addressRow.setUp(R.drawable.ic_profile_address, R.string.account_address_title, R.id.cs13)
        binding.notificationRow.setUp(R.drawable.ic_profile_notification, R.string.account_notification_title, R.id.cs31)
        binding.supportRow.setUp(R.drawable.ic_profile_support, R.string.account_support_title, R.id.cs34)
        binding.settingsRow.setUp(R.drawable.ic_profile_settings, R.string.account_settings_title, R.id.cs32)
        binding.supportRow.subtitle.setText(R.string.account_support_subtitle)
        binding.settingsRow.subtitle.setText(R.string.account_settings_subtitle)
        binding.version.text = getString(R.string.account_version, BuildConfig.VERSION_NAME)

        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }

        collectWhenStarted(combine(viewModel.state, viewModel.refreshing, ::Pair)) { (state, refreshing) ->
            binding.swipeRefresh.isRefreshing = state is UiState.Loading || refreshing
            binding.errorGroup.isVisible = state is UiState.Error
            binding.content.isVisible = state is UiState.Content
            when (state) {
                UiState.Loading -> Unit
                is UiState.Error -> binding.errorText.text = state.message
                is UiState.Content -> binding.show(state.data)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    private fun FragmentProfileBinding.show(profile: ProfileSummary) {
        // No image loader in the project yet, so avatarUrl is not rendered; initials are always shown.
        avatar.text = initialsOf(profile.fullName)
        fullName.text = profile.fullName
        phoneNumber.text = formatPhoneNumber(profile.phoneNumber)
        feeRow.subtitle.text = when (val fee = AccountLines.fee(profile.wallet)) {
            AccountLines.Fee.Unavailable -> getString(R.string.account_value_unavailable)
            is AccountLines.Fee.Outstanding -> getString(R.string.account_fee_outstanding, OrderFormat.money(requireContext(), fee.amount))
            is AccountLines.Fee.Pending -> getString(R.string.account_fee_pending, OrderFormat.money(requireContext(), fee.amount))
            AccountLines.Fee.None -> getString(R.string.account_fee_none)
        }
        addressRow.subtitle.text = if (profile.addressCount > 0) {
            resources.getQuantityString(R.plurals.account_address_count, profile.addressCount, profile.addressCount)
        } else {
            getString(R.string.account_address_none)
        }
        notificationRow.subtitle.text = when (val line = AccountLines.notifications(profile.unreadNotificationCount)) {
            AccountLines.Notifications.Unavailable -> getString(R.string.account_value_unavailable)
            is AccountLines.Notifications.Unread -> resources.getQuantityString(R.plurals.account_notification_unread, line.count, line.count)
            AccountLines.Notifications.None -> getString(R.string.account_notification_none)
        }
    }

    private fun ItemProfileMenuBinding.setUp(@DrawableRes iconRes: Int, @StringRes titleRes: Int, @IdRes destination: Int) {
        icon.setImageResource(iconRes)
        title.setText(titleRes)
        root.setOnClickListener {
            val navController = findNavController()
            if (navController.currentDestination?.id == R.id.cs29) navController.navigate(destination)
        }
    }

    /** "Nguyễn Minh Anh" → "MA": first letters of the last two words, as on the Figma avatar. */
    private fun initialsOf(fullName: String): String {
        val words = fullName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.takeLast(2).joinToString("") { it.take(1) }.uppercase()
    }

    /** "+84912345678" or "0912345678" → "+84 912 345 678"; anything else is shown unchanged. */
    private fun formatPhoneNumber(phoneNumber: String): String {
        val national = when {
            phoneNumber.startsWith("+84") -> phoneNumber.drop(3)
            phoneNumber.startsWith("0") -> phoneNumber.drop(1)
            else -> return phoneNumber
        }
        if (national.length != 9 || !national.all { it.isDigit() }) return phoneNumber
        return "+84 " + national.chunked(3).joinToString(" ")
    }
}

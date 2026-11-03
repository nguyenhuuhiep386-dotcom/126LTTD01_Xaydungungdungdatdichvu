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
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import vn.servio.core.utils.Money
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.customer.BuildConfig
import vn.servio.customer.R
import vn.servio.customer.data.model.ProfileSummary
import vn.servio.customer.databinding.FragmentProfileBinding
import vn.servio.customer.databinding.ItemProfileMenuBinding

/** CS-29 Tài khoản — real API (#9, #12); fee and notification counters show "—" until their endpoints exist. */
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

        // Editing the profile comes later with API #10.
        binding.editButton.setOnClickListener {
            Snackbar.make(view, R.string.account_edit_coming_soon, Snackbar.LENGTH_SHORT).show()
        }
        binding.retryButton.setOnClickListener { viewModel.load() }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }

        collectWhenStarted(viewModel.state) { state ->
            binding.swipeRefresh.isRefreshing = state is UiState.Loading
            binding.errorGroup.isVisible = state is UiState.Error
            binding.content.isVisible = state is UiState.Content
            when (state) {
                UiState.Loading -> Unit
                is UiState.Error -> binding.errorText.text = state.message
                is UiState.Content -> binding.show(state.data)
            }
        }
    }

    private fun FragmentProfileBinding.show(profile: ProfileSummary) {
        // No image loader in the project yet, so avatarUrl is not rendered; initials are always shown.
        avatar.text = initialsOf(profile.fullName)
        fullName.text = profile.fullName
        phoneNumber.text = formatPhoneNumber(profile.phoneNumber)
        val fee = profile.outstandingFeeVnd
        feeRow.subtitle.text = when {
            fee == null -> getString(R.string.account_value_unavailable)
            fee > 0 -> getString(R.string.account_fee_outstanding, Money.format(fee))
            else -> getString(R.string.account_fee_none)
        }
        addressRow.subtitle.text = if (profile.addressCount > 0) {
            resources.getQuantityString(R.plurals.account_address_count, profile.addressCount, profile.addressCount)
        } else {
            getString(R.string.account_address_none)
        }
        val unread = profile.unreadNotificationCount
        notificationRow.subtitle.text = when {
            unread == null -> getString(R.string.account_value_unavailable)
            unread > 0 -> resources.getQuantityString(R.plurals.account_notification_unread, unread, unread)
            else -> getString(R.string.account_notification_none)
        }
    }

    private fun ItemProfileMenuBinding.setUp(@DrawableRes iconRes: Int, @StringRes titleRes: Int, @IdRes destination: Int) {
        icon.setImageResource(iconRes)
        title.setText(titleRes)
        root.setOnClickListener { findNavController().navigate(destination) }
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

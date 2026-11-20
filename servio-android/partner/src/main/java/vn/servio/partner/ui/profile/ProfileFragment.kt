package vn.servio.partner.ui.profile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.data.repository.LegalConsentRepository
import vn.servio.core.data.repository.LegalConsents
import vn.servio.core.network.ApiResult
import vn.servio.core.realtime.Realtime
import vn.servio.core.ui.auth.navigateToLogin
import vn.servio.core.utils.UiState
import vn.servio.core.utils.collectWhenStarted
import vn.servio.core.utils.toUiState
import vn.servio.partner.R
import vn.servio.partner.data.model.ApprovalStatus
import vn.servio.partner.data.model.PartnerMe
import vn.servio.partner.data.repository.PartnerProfileRepository
import vn.servio.partner.data.repository.PresenceRepository
import vn.servio.partner.databinding.FragmentProfileBinding
import vn.servio.partner.ui.signup.SignupSkillsFragment
import vn.servio.partner.tracking.LocationTrackingService
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: PartnerProfileRepository,
    private val authRepository: AuthRepository,
    private val legalConsents: LegalConsentRepository,
    private val presence: PresenceRepository,
    private val realtime: Realtime,
) : ViewModel() {

    /** Leaving the account: logout, withdrawing the consent or deleting it. DONE goes to login. */
    enum class LeaveState { IDLE, IN_PROGRESS, DONE }

    enum class Page { TERMS, PRIVACY }

    sealed interface Event {
        data class OpenPage(val url: String) : Event

        /** 409 ACCOUNT_DELETION_BLOCKED: the server's reason (an open order, quote or debt), shown in a dialog. */
        data class DeletionBlocked(val reason: String) : Event
        data class Message(val text: String) : Event
    }

    private val _state = MutableStateFlow<UiState<PartnerMe>>(UiState.Loading)
    val state: StateFlow<UiState<PartnerMe>> = _state.asStateFlow()

    private val _leaveState = MutableStateFlow(LeaveState.IDLE)
    val leaveState: StateFlow<LeaveState> = _leaveState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch { _state.value = profileRepository.getMe().toUiState() }
    }

    /** Goes Offline first (so no new posts are matched to a logged-out phone), then #6 (best effort). */
    fun logout() = leave {
        goOffline()
        authRepository.logout()
        signedOut()
    }

    /**
     * "Rút lại đồng ý": TERMS_AND_PRIVACY granted=false, then the same logout. The next login asks again (the consent
     * screen of :core); orders in progress are not touched. A failed withdrawal keeps the partner signed in.
     */
    fun withdrawConsent() = leave {
        when (val result = legalConsents.withdrawTerms()) {
            is ApiResult.Success -> {
                goOffline()
                authRepository.logout()
                signedOut()
            }
            is ApiResult.Failure -> fail(Event.Message(result.message))
        }
    }

    /**
     * DELETE /users/me. 204: the server set Offline and revoked every session, so only this device's session is
     * dropped (no #6 or #25, they would fail). 409: the server's reason in a dialog, still signed in.
     */
    fun deleteAccount() = leave {
        val result = legalConsents.deleteAccount()
        when {
            result is ApiResult.Success -> {
                authRepository.clearLocalSession()
                signedOut()
            }
            result is ApiResult.Failure && result.code == DELETION_BLOCKED -> fail(Event.DeletionBlocked(result.message))
            result is ApiResult.Failure -> fail(Event.Message(result.message))
        }
    }

    fun openPage(page: Page) {
        viewModelScope.launch {
            when (val result = legalConsents.legal()) {
                is ApiResult.Success -> {
                    val path = if (page == Page.TERMS) result.data.termsUrl else result.data.privacyPolicyUrl
                    LegalConsents.pageUrl(path)?.let { _events.send(Event.OpenPage(it)) }
                }
                is ApiResult.Failure -> _events.send(Event.Message(result.message))
            }
        }
    }

    private suspend fun goOffline() {
        if (presence.isOnline.value) presence.goOffline()
    }

    /** One leaving action at a time; a second tap while one runs does nothing. */
    private fun leave(block: suspend () -> Unit) {
        if (_leaveState.value != LeaveState.IDLE) return
        _leaveState.value = LeaveState.IN_PROGRESS
        viewModelScope.launch { block() }
    }

    /** Stops the heartbeat even if #25 failed, and closes the hubs so the next account does not get these events. */
    private fun signedOut() {
        presence.restore(false)
        realtime.stopAll()
        _leaveState.value = LeaveState.DONE
    }

    private suspend fun fail(event: Event) {
        _leaveState.value = LeaveState.IDLE
        _events.send(event)
    }

    private companion object {
        const val DELETION_BLOCKED = "ACCOUNT_DELETION_BLOCKED"
    }
}

/**
 * PS-23 Hồ sơ đối tác: summary, skills (PS-24), working area (PS-04 in manage mode), notifications (PS-28),
 * "Quyền riêng tư" (the two pages, withdrawing the consent, deleting the account — Luật 91/2025) and logout.
 */
@AndroidEntryPoint
class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private val viewModel: ProfileViewModel by viewModels()

    private var binding: FragmentProfileBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentProfileBinding.bind(view).also { binding = it }
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.skillsRow.setOnClickListener { findNavController().navigate(R.id.ps24) }
        binding.areaRow.setOnClickListener {
            findNavController().navigate(R.id.ps04, bundleOf(SignupSkillsFragment.ARG_MANAGE to true))
        }
        binding.notificationsRow.setOnClickListener { findNavController().navigate(R.id.ps28) }
        binding.privacyRow.setOnClickListener { viewModel.openPage(ProfileViewModel.Page.PRIVACY) }
        binding.termsRow.setOnClickListener { viewModel.openPage(ProfileViewModel.Page.TERMS) }
        binding.withdrawRow.setOnClickListener { confirmWithdraw() }
        binding.deleteAccountRow.setOnClickListener { explainDeletion() }
        binding.logoutButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_logout_title)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(vn.servio.core.R.string.action_logout) { _, _ -> viewModel.logout() }
                .show()
        }

        collectWhenStarted(viewModel.leaveState) { state ->
            val idle = state == ProfileViewModel.LeaveState.IDLE
            listOf(binding.logoutButton, binding.withdrawRow, binding.deleteAccountRow).forEach { it.isEnabled = idle }
            if (state == ProfileViewModel.LeaveState.DONE) {
                LocationTrackingService.stop(requireContext())
                findNavController().navigateToLogin()
            }
        }
        collectWhenStarted(viewModel.events) { event ->
            when (event) {
                is ProfileViewModel.Event.OpenPage -> open(event.url)
                is ProfileViewModel.Event.DeletionBlocked -> MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.privacy_delete_blocked_title)
                    .setMessage(event.reason)
                    .setPositiveButton(R.string.action_close, null)
                    .show()
                is ProfileViewModel.Event.Message -> showMessage(event.text)
            }
        }

        collectWhenStarted(viewModel.state) { state ->
            binding.progress.isVisible = state is UiState.Loading
            binding.errorText.isVisible = state is UiState.Error
            binding.content.isVisible = state is UiState.Content
            if (state is UiState.Error) binding.errorText.text = state.message
            if (state !is UiState.Content) return@collectWhenStarted
            val me = state.data
            binding.initials.text = me.fullName.split(' ').filter { it.isNotBlank() }.takeLast(2)
                .joinToString("") { it.take(1).uppercase() }
            binding.name.text = me.fullName
            binding.phone.text = me.phoneNumber
            binding.rating.text = me.averageRating
                ?.let { getString(R.string.profile_rating, it, me.totalReviews) }
                ?: getString(R.string.profile_no_rating)
            binding.stats.text = getString(R.string.profile_stats, me.completedOrders, me.yearsOfExperience)
            binding.bio.isVisible = !me.bio.isNullOrBlank()
            binding.bio.text = me.bio
            val approved = me.skills.count { it.status == ApprovalStatus.APPROVED }
            binding.skillsValue.text = getString(R.string.profile_skills_value, approved, me.skills.size)
            binding.areaValue.text = getString(R.string.profile_area_value, me.serviceRadiusKm)
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun confirmWithdraw() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.privacy_withdraw_title)
            .setMessage(R.string.privacy_withdraw_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.privacy_withdraw) { _, _ -> viewModel.withdrawConsent() }
            .show()
    }

    /** Step 1: what is erased and what is kept anonymised; "Tiếp tục" leads to the final confirmation. */
    private fun explainDeletion() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.privacy_delete_title)
            .setMessage(R.string.privacy_delete_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(vn.servio.core.R.string.action_continue) { _, _ -> confirmDeletion() }
            .show()
    }

    /** Step 2: "Xoá vĩnh viễn" sends DELETE /users/me. */
    private fun confirmDeletion() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.privacy_delete_confirm_title)
            .setMessage(R.string.privacy_delete_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.privacy_delete_confirm) { _, _ -> viewModel.deleteAccount() }
            .show()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.privacy_link_unavailable))
        }
    }

    private fun showMessage(text: String) {
        val root = binding?.root ?: return
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
    }
}

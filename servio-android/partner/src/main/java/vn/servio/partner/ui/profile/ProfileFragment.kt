package vn.servio.partner.ui.profile

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.servio.core.data.repository.AuthRepository
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
    private val presence: PresenceRepository,
    private val realtime: Realtime,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PartnerMe>>(UiState.Loading)
    val state: StateFlow<UiState<PartnerMe>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch { _state.value = profileRepository.getMe().toUiState() }
    }

    /** Goes Offline first (so no new posts are matched to a logged-out phone), closes the hubs, then #6. */
    suspend fun logout() {
        if (presence.isOnline.value) presence.goOffline()
        realtime.stopAll()
        authRepository.logout()
    }
}

/** PS-23 Hồ sơ đối tác: summary, skills (PS-24), working area (PS-04 in manage mode), logout. */
@AndroidEntryPoint
class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private val viewModel: ProfileViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentProfileBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.skillsRow.setOnClickListener { findNavController().navigate(R.id.ps24) }
        binding.areaRow.setOnClickListener {
            findNavController().navigate(R.id.ps04, bundleOf(SignupSkillsFragment.ARG_MANAGE to true))
        }
        binding.notificationsRow.setOnClickListener { findNavController().navigate(R.id.ps28) }
        binding.logoutButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_logout_title)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(vn.servio.core.R.string.action_logout) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        LocationTrackingService.stop(requireContext())
                        viewModel.logout()
                        findNavController().navigateToLogin()
                    }
                }
                .show()
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
}

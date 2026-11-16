package vn.servio.partner.ui.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.servio.core.data.model.PartnerVerificationStatus
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.partner.data.model.PartnerMe
import vn.servio.partner.data.repository.PartnerProfileRepository
import javax.inject.Inject

/**
 * PS-06 Chờ duyệt: reads the verification state from #17; the admin decides on AW-04. While PENDING it checks again
 * every 15 seconds, so the screen moves on by itself when the admin approves (demo step 1, no push needed).
 */
@HiltViewModel
class VerificationStatusViewModel @Inject constructor(
    private val profileRepository: PartnerProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PartnerMe>>(UiState.Loading)
    val state: StateFlow<UiState<PartnerMe>> = _state.asStateFlow()

    private val _approved = Channel<Unit>(Channel.CONFLATED)

    /** Emits once when a PENDING profile becomes APPROVED while this screen is open. */
    val approved: Flow<Unit> = _approved.receiveAsFlow()

    init {
        load()
        viewModelScope.launch {
            while (isActive) {
                delay(POLL_MILLIS)
                val current = (_state.value as? UiState.Content)?.data?.verificationStatus
                if (current != PartnerVerificationStatus.PENDING) continue
                val result = profileRepository.getMe()
                if (result is ApiResult.Success) {
                    _state.value = UiState.Content(result.data)
                    if (result.data.verificationStatus == PartnerVerificationStatus.APPROVED) _approved.send(Unit)
                }
            }
        }
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch { _state.value = profileRepository.getMe().toUiState() }
    }

    private companion object {
        const val POLL_MILLIS = 15_000L
    }
}

package vn.servio.customer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.servio.core.data.repository.AuthRepository
import vn.servio.customer.data.repository.SessionCleanup
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionCleanup: SessionCleanup,
) : ViewModel() {

    enum class LogoutState { IDLE, IN_PROGRESS, DONE }

    private val _logoutState = MutableStateFlow(LogoutState.IDLE)
    val logoutState: StateFlow<LogoutState> = _logoutState.asStateFlow()

    /**
     * POST /auth/logout (#6), best effort: the local session is cleared even when the call fails. Then the hubs are
     * closed and the account's data dropped ([SessionCleanup]) before the screen goes to login.
     */
    fun logout() {
        if (_logoutState.value != LogoutState.IDLE) return
        _logoutState.value = LogoutState.IN_PROGRESS
        viewModelScope.launch {
            authRepository.logout()
            sessionCleanup.signedOut()
            _logoutState.value = LogoutState.DONE
        }
    }
}

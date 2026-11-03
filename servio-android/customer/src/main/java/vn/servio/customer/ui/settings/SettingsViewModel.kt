package vn.servio.customer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.servio.core.data.repository.AuthRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    enum class LogoutState { IDLE, IN_PROGRESS, DONE }

    private val _logoutState = MutableStateFlow(LogoutState.IDLE)
    val logoutState: StateFlow<LogoutState> = _logoutState.asStateFlow()

    /** POST /auth/logout (#6). Best effort: the local session is cleared even when the call fails. */
    fun logout() {
        if (_logoutState.value != LogoutState.IDLE) return
        _logoutState.value = LogoutState.IN_PROGRESS
        viewModelScope.launch {
            authRepository.logout()
            _logoutState.value = LogoutState.DONE
        }
    }
}

package vn.servio.core.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.servio.core.data.model.Me
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.data.repository.LegalConsentRepository
import vn.servio.core.network.ApiResult
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val legalConsents: LegalConsentRepository,
) : ViewModel() {

    sealed interface State {
        data object Checking : State
        data object NeedsLogin : State
        /** [needsConsent]: the terms and policy of the current version are not agreed yet (checked on every start). */
        data class LoggedIn(val user: Me, val needsConsent: Boolean) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        check()
    }

    fun check() {
        if (!authRepository.hasSession) {
            _state.value = State.NeedsLogin
            return
        }
        _state.value = State.Checking
        viewModelScope.launch {
            _state.value = when (val result = authRepository.getMe()) {
                is ApiResult.Success -> State.LoggedIn(result.data, legalConsents.needsTermsConsent())
                // Expired session: the authenticator already cleared the tokens.
                is ApiResult.Failure -> if (authRepository.hasSession) State.Error(result.message) else State.NeedsLogin
            }
        }
    }
}

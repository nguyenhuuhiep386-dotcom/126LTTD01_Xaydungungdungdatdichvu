package vn.servio.core.ui.auth

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.R
import vn.servio.core.data.model.Me
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.data.repository.LegalConsentRepository
import vn.servio.core.data.repository.LegalConsents
import vn.servio.core.network.ApiResult
import javax.inject.Inject

/**
 * The consent screen: "Đồng ý và tiếp tục" reads GET /legal for the version the user is agreeing to, then records
 * TERMS_AND_PRIVACY. A policy changed in between (409) unticks the box and asks to read again.
 */
@HiltViewModel
class LegalConsentViewModel @Inject constructor(
    private val consents: LegalConsentRepository,
    private val authRepository: AuthRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** [checked] survives rotation; it is never true on its own (the box starts unticked). */
    data class State(val checked: Boolean = false, val isLoading: Boolean = false) {
        val canAgree: Boolean get() = checked && !isLoading
    }

    sealed interface Event {
        data class Message(val text: String? = null, @StringRes val res: Int? = null) : Event
        data class OpenPage(val url: String) : Event

        /** Agreed in the login flow: the app's AuthRouter decides where to go with [user]. */
        data class Agreed(val user: Me) : Event

        /** Agreed after a CONSENT_REQUIRED: back to the screen that opened it. */
        data object Returned : Event
        data object LoggedOut : Event
    }

    enum class Page { TERMS, PRIVACY }

    private val returnToCaller: Boolean = savedStateHandle[LegalConsentFragment.ARG_RETURN_TO_CALLER] ?: false

    private val _state = MutableStateFlow(State(checked = savedStateHandle[KEY_CHECKED] ?: false))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** Recorded but GET /users/me failed afterwards: the next tap only asks for the user again. */
    private var granted = false

    fun setChecked(checked: Boolean) {
        savedStateHandle[KEY_CHECKED] = checked
        _state.update { it.copy(checked = checked) }
    }

    fun agree() {
        if (!_state.value.canAgree) return
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            if (!granted) {
                val failure = record()
                if (failure != null) {
                    _state.update { it.copy(isLoading = false) }
                    _events.send(failure)
                    return@launch
                }
                granted = true
            }
            if (returnToCaller) {
                _state.update { it.copy(isLoading = false) }
                _events.send(Event.Returned)
                return@launch
            }
            when (val me = authRepository.getMe()) {
                is ApiResult.Success -> _events.send(Event.Agreed(me.data))
                is ApiResult.Failure -> _events.send(Event.Message(me.message))
            }
            _state.update { it.copy(isLoading = false) }
        }
    }

    /** GET /legal then POST; null when recorded, otherwise what to tell the user. */
    private suspend fun record(): Event? {
        val legal = when (val result = consents.legal()) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return Event.Message(result.message)
        }
        return when (val result = consents.grantTerms(legal.policyVersion)) {
            is ApiResult.Success -> null
            is ApiResult.Failure -> if (result.code == POLICY_CHANGED) {
                consents.legal() // read again: the links now show the new version
                setChecked(false)
                Event.Message(res = R.string.legal_consent_policy_changed)
            } else {
                Event.Message(result.message)
            }
        }
    }

    fun openPage(page: Page) {
        viewModelScope.launch {
            when (val result = consents.legal()) {
                is ApiResult.Success -> {
                    val path = if (page == Page.TERMS) result.data.termsUrl else result.data.privacyPolicyUrl
                    val url = LegalConsents.pageUrl(path)
                    _events.send(if (url != null) Event.OpenPage(url) else Event.Message(res = R.string.legal_link_unavailable))
                }
                is ApiResult.Failure -> _events.send(Event.Message(result.message))
            }
        }
    }

    /** "Đăng xuất": the session ends here (best effort on the server), back to CS-03. */
    fun logout() {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            authRepository.logout()
            _events.send(Event.LoggedOut)
        }
    }

    private companion object {
        const val KEY_CHECKED = "checked"
        const val POLICY_CHANGED = "RESOURCE_VERSION_CONFLICT"
    }
}

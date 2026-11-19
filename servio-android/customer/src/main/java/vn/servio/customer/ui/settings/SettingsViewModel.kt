package vn.servio.customer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import vn.servio.customer.data.repository.SessionCleanup
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionCleanup: SessionCleanup,
    private val legalConsents: LegalConsentRepository,
) : ViewModel() {

    /** Leaving the account: logout, withdrawing the consent or deleting the account. DONE goes to CS-03. */
    enum class LogoutState { IDLE, IN_PROGRESS, DONE }

    sealed interface Event {
        data class OpenPage(val url: String) : Event

        /** 409 ACCOUNT_DELETION_BLOCKED: the server's reason, shown in a dialog. */
        data class DeletionBlocked(val reason: String) : Event
        data class Message(val text: String) : Event
    }

    enum class Page { TERMS, PRIVACY }

    private val _logoutState = MutableStateFlow(LogoutState.IDLE)
    val logoutState: StateFlow<LogoutState> = _logoutState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /**
     * POST /auth/logout (#6), best effort: the local session is cleared even when the call fails. Then the hubs are
     * closed and the account's data dropped ([SessionCleanup]) before the screen goes to login.
     */
    fun logout() = leave {
        authRepository.logout()
        signedOut()
    }

    /**
     * "Rút lại đồng ý": TERMS_AND_PRIVACY granted=false, then the same logout. The next login asks again (the consent
     * screen of :core); orders in progress are not touched. A failed withdrawal keeps the user signed in.
     */
    fun withdrawConsent() = leave {
        when (val result = legalConsents.withdrawTerms()) {
            is ApiResult.Success -> {
                authRepository.logout()
                signedOut()
            }
            is ApiResult.Failure -> fail(Event.Message(result.message))
        }
    }

    /**
     * DELETE /users/me. 204: the server already revoked every session, so only this device's session is dropped
     * (no #6, it would fail). 409: the reason in a dialog, still signed in.
     */
    fun deleteAccount() = leave {
        when (val outcome = AccountDeletion.of(legalConsents.deleteAccount())) {
            AccountDeletion.Deleted -> {
                authRepository.clearLocalSession()
                signedOut()
            }
            is AccountDeletion.Blocked -> fail(Event.DeletionBlocked(outcome.reason))
            is AccountDeletion.Failed -> fail(Event.Message(outcome.message))
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

    /** One leaving action at a time; a second tap while one runs does nothing. */
    private fun leave(block: suspend () -> Unit) {
        if (_logoutState.value != LogoutState.IDLE) return
        _logoutState.value = LogoutState.IN_PROGRESS
        viewModelScope.launch { block() }
    }

    private fun signedOut() {
        sessionCleanup.signedOut()
        _logoutState.value = LogoutState.DONE
    }

    private suspend fun fail(event: Event) {
        _logoutState.value = LogoutState.IDLE
        _events.send(event)
    }
}

/** What CS-32 does with the answer of DELETE /users/me. */
sealed interface AccountDeletion {
    data object Deleted : AccountDeletion

    /** Something to finish first (an open order or request, a fee owed…): [reason] is the server's sentence. */
    data class Blocked(val reason: String) : AccountDeletion

    /** Network or any other error: a snackbar, the user can try again. */
    data class Failed(val message: String) : AccountDeletion

    companion object {
        const val BLOCKED = "ACCOUNT_DELETION_BLOCKED"

        fun of(result: ApiResult<Unit>): AccountDeletion = when (result) {
            is ApiResult.Success -> Deleted
            is ApiResult.Failure -> if (result.code == BLOCKED) Blocked(result.message) else Failed(result.message)
        }
    }
}

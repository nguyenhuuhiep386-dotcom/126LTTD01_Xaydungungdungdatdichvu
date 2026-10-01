package vn.servio.core.ui.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.data.model.Me
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.network.ApiResult
import javax.inject.Inject

@HiltViewModel
class OtpViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    data class State(
        val isLoading: Boolean = false,
        val error: String? = null,
        val resendInSeconds: Int = 0,
    )

    private val phoneNumber: String = checkNotNull(savedStateHandle["phoneNumber"])
    private var otpId: String = checkNotNull(savedStateHandle["otpId"])

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** One-time event: logged in, the Fragment asks AuthRouter where to go. */
    private val _loggedIn = Channel<Me>(Channel.BUFFERED)
    val loggedIn: Flow<Me> = _loggedIn.receiveAsFlow()

    private var countdown: Job? = null

    init {
        startCountdown(savedStateHandle["resendAfterSeconds"] ?: DEFAULT_RESEND_SECONDS)
    }

    fun verify(code: String) {
        if (code.length != CODE_LENGTH || _state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.verifyOtp(otpId, code)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isLoading = false) }
                    _loggedIn.send(result.data.user)
                }
                is ApiResult.Failure -> _state.update { it.copy(isLoading = false, error = result.message) }
            }
        }
    }

    fun resend() {
        if (_state.value.resendInSeconds > 0 || _state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.requestOtp(phoneNumber)) {
                is ApiResult.Success -> {
                    otpId = result.data.otpId
                    _state.update { it.copy(isLoading = false) }
                    startCountdown(result.data.resendAfterSeconds)
                }
                is ApiResult.Failure -> _state.update { it.copy(isLoading = false, error = result.message) }
            }
        }
    }

    private fun startCountdown(seconds: Int) {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (remaining in seconds downTo 0) {
                _state.update { it.copy(resendInSeconds = remaining) }
                delay(1_000)
            }
        }
    }

    private companion object {
        const val CODE_LENGTH = 6
        const val DEFAULT_RESEND_SECONDS = 60
    }
}

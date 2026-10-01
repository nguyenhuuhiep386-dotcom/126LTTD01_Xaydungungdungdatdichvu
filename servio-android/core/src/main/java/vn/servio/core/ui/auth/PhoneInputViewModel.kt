package vn.servio.core.ui.auth

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
import vn.servio.core.data.model.OtpRequestResult
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.network.ApiResult
import javax.inject.Inject

@HiltViewModel
class PhoneInputViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class State(val isLoading: Boolean = false, val error: String? = null)

    /** One-time event: the OTP was sent, open CS-04. */
    data class OtpSent(val phoneNumber: String, val result: OtpRequestResult)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<OtpSent>(Channel.BUFFERED)
    val events: Flow<OtpSent> = _events.receiveAsFlow()

    fun submit(rawPhone: String) {
        if (_state.value.isLoading) return
        val phone = rawPhone.filter(Char::isDigit)
        if (!isValidPhone(phone)) {
            _state.update { it.copy(error = INVALID_PHONE) }
            return
        }
        _state.value = State(isLoading = true)
        viewModelScope.launch {
            when (val result = authRepository.requestOtp(phone)) {
                is ApiResult.Success -> {
                    _state.value = State()
                    _events.send(OtpSent(phone, result.data))
                }
                is ApiResult.Failure -> _state.value = State(error = result.message)
            }
        }
    }

    companion object {
        /** Marker so the Fragment shows its localized "invalid phone" text. */
        const val INVALID_PHONE = "INVALID_PHONE"

        /** 10 digits starting with 0 (e.g. 0901234567). The server normalizes to +84. */
        fun isValidPhone(digits: String): Boolean = digits.length == 10 && digits.startsWith("0")
    }
}

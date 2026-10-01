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
import kotlinx.coroutines.launch
import vn.servio.core.data.model.Me
import vn.servio.core.data.model.UpdateMeRequest
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.network.ApiResult
import javax.inject.Inject

@HiltViewModel
class CompleteProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class State(val isLoading: Boolean = false, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _saved = Channel<Me>(Channel.BUFFERED)
    val saved: Flow<Me> = _saved.receiveAsFlow()

    fun save(rawName: String) {
        val name = rawName.trim()
        if (name.length !in 2..100) {
            _state.value = State(error = INVALID_NAME)
            return
        }
        _state.value = State(isLoading = true)
        viewModelScope.launch {
            when (val result = authRepository.updateMe(UpdateMeRequest(fullName = name))) {
                is ApiResult.Success -> {
                    _state.value = State()
                    _saved.send(result.data)
                }
                is ApiResult.Failure -> _state.value = State(error = result.message)
            }
        }
    }

    companion object {
        const val INVALID_NAME = "INVALID_NAME"
    }
}

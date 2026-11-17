package vn.servio.partner.ui.signup

import android.net.Uri
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
import vn.servio.core.data.model.UpdateMeRequest
import vn.servio.core.data.repository.AuthRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.partner.data.model.FilePurpose
import vn.servio.partner.data.model.PartnerMe
import vn.servio.partner.data.model.UpdatePartnerRequest
import vn.servio.partner.data.repository.FileRepository
import vn.servio.partner.data.repository.PartnerProfileRepository
import javax.inject.Inject

/** PS-02 Bước 1: avatar (#10 + POST /files AVATAR), name (#10), bio and years of experience (#18). */
@HiltViewModel
class SignupInfoViewModel @Inject constructor(
    private val profileRepository: PartnerProfileRepository,
    private val authRepository: AuthRepository,
    private val fileRepository: FileRepository,
) : ViewModel() {

    data class Avatar(val localUri: Uri? = null, val remoteUrl: String? = null, val uploading: Boolean = false)

    enum class FieldError { NAME_INVALID }

    sealed interface Event {
        data object Saved : Event
        data class Error(val message: String) : Event
    }

    private val _state = MutableStateFlow<UiState<PartnerMe>>(UiState.Loading)
    val state: StateFlow<UiState<PartnerMe>> = _state.asStateFlow()

    private val _avatar = MutableStateFlow(Avatar())
    val avatar: StateFlow<Avatar> = _avatar.asStateFlow()

    private val _years = MutableStateFlow(0)
    val years: StateFlow<Int> = _years.asStateFlow()

    private val _fieldErrors = MutableStateFlow<Set<FieldError>>(emptySet())
    val fieldErrors: StateFlow<Set<FieldError>> = _fieldErrors.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** The form is filled from the server once; after that the views keep what the user typed. */
    var isFormFilled = false

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val result = profileRepository.getMe()
            if (result is ApiResult.Success) {
                _years.value = result.data.yearsOfExperience
                _avatar.value = _avatar.value.copy(remoteUrl = result.data.avatarUrl)
            }
            _state.value = result.toUiState()
        }
    }

    fun changeYears(delta: Int) {
        _years.value = (_years.value + delta).coerceIn(0, MAX_YEARS)
    }

    fun onAvatarPicked(uri: Uri) {
        _avatar.value = _avatar.value.copy(localUri = uri, uploading = true)
        viewModelScope.launch {
            when (val result = fileRepository.uploadImage(uri, FilePurpose.AVATAR)) {
                is ApiResult.Success -> _avatar.value = Avatar(localUri = uri, remoteUrl = result.data.url)
                is ApiResult.Failure -> {
                    _avatar.value = _avatar.value.copy(localUri = null, uploading = false)
                    _events.send(Event.Error(result.message))
                }
            }
        }
    }

    fun save(fullName: String, bio: String) {
        val name = fullName.trim()
        if (name.length !in NAME_LENGTH) {
            _fieldErrors.value = setOf(FieldError.NAME_INVALID)
            return
        }
        _fieldErrors.value = emptySet()
        if (_saving.value || _avatar.value.uploading) return
        _saving.value = true
        viewModelScope.launch {
            val user = authRepository.updateMe(UpdateMeRequest(fullName = name, avatarUrl = _avatar.value.remoteUrl))
            val partner = if (user is ApiResult.Success) {
                profileRepository.update(UpdatePartnerRequest(bio = bio.trim(), yearsOfExperience = _years.value))
            } else {
                user
            }
            _saving.value = false
            when (partner) {
                is ApiResult.Success -> _events.send(Event.Saved)
                is ApiResult.Failure -> _events.send(Event.Error(partner.message))
            }
        }
    }

    private companion object {
        val NAME_LENGTH = 2..100
        const val MAX_YEARS = 60
    }
}

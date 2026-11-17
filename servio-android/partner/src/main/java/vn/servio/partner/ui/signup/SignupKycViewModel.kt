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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.partner.data.model.ApprovalStatus
import vn.servio.partner.data.model.DocumentType
import vn.servio.partner.data.model.FilePurpose
import vn.servio.partner.data.model.PartnerMe
import vn.servio.partner.data.repository.FileRepository
import vn.servio.partner.data.repository.PartnerProfileRepository
import javax.inject.Inject

/** PS-05 Bước 4: three KYC photos (POST /files KYC + #19), then "Gửi xét duyệt" (#20). */
@HiltViewModel
class SignupKycViewModel @Inject constructor(
    private val profileRepository: PartnerProfileRepository,
    private val fileRepository: FileRepository,
) : ViewModel() {

    /** One photo slot on the screen. [localUri] is the photo taken in this session, shown as the thumbnail. */
    data class Slot(
        val type: DocumentType,
        val status: ApprovalStatus? = null,
        val rejectReason: String? = null,
        val localUri: Uri? = null,
        val uploading: Boolean = false,
    ) {
        val isDone: Boolean get() = status == ApprovalStatus.PENDING || status == ApprovalStatus.APPROVED
    }

    sealed interface Event {
        data object Submitted : Event
        data class Error(val message: String) : Event
    }

    private val _state = MutableStateFlow<UiState<List<Slot>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Slot>>> = _state.asStateFlow()

    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = when (val result = profileRepository.getMe()) {
                is ApiResult.Success -> UiState.Content(slotsOf(result.data))
                is ApiResult.Failure -> UiState.Error(result.message)
            }
        }
    }

    fun onPhotoPicked(type: DocumentType, uri: Uri) {
        updateSlot(type) { it.copy(localUri = uri, uploading = true) }
        viewModelScope.launch {
            val upload = fileRepository.uploadImage(uri, FilePurpose.KYC)
            val document = when (upload) {
                is ApiResult.Success -> profileRepository.addDocument(type, upload.data.url)
                is ApiResult.Failure -> upload
            }
            when (document) {
                is ApiResult.Success -> updateSlot(type) {
                    it.copy(status = document.data.status, rejectReason = null, uploading = false)
                }
                is ApiResult.Failure -> {
                    updateSlot(type) { it.copy(localUri = null, uploading = false) }
                    _events.send(Event.Error(document.message))
                }
            }
        }
    }

    fun submit() {
        val slots = (_state.value as? UiState.Content)?.data ?: return
        if (_submitting.value || slots.any { it.uploading }) return
        _submitting.value = true
        viewModelScope.launch {
            // The server lists anything still missing (name, skill, anchor...) in its message.
            val result = profileRepository.submitVerification()
            _submitting.value = false
            _events.send(
                when (result) {
                    is ApiResult.Success -> Event.Submitted
                    is ApiResult.Failure -> Event.Error(result.message)
                },
            )
        }
    }

    private fun updateSlot(type: DocumentType, transform: (Slot) -> Slot) {
        _state.update { state ->
            if (state is UiState.Content) UiState.Content(state.data.map { if (it.type == type) transform(it) else it }) else state
        }
    }

    private fun slotsOf(me: PartnerMe) = KYC_TYPES.map { type ->
        val document = me.document(type)
        Slot(type, document?.status, document?.rejectReason)
    }

    companion object {
        val KYC_TYPES = listOf(DocumentType.ID_FRONT, DocumentType.ID_BACK, DocumentType.SELFIE_WITH_ID)
    }
}

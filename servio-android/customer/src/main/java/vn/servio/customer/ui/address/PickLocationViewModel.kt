package vn.servio.customer.ui.address

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
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.network.map
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.customer.data.model.Address
import vn.servio.customer.data.repository.AddressRepository
import vn.servio.customer.data.repository.ProfileRepository
import javax.inject.Inject

@HiltViewModel
class PickLocationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val addressRepository: AddressRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    /** [address] is the entry being edited, or null when adding a new one. */
    data class Form(val address: Address?)

    data class MapCamera(val latitude: Double, val longitude: Double, val zoom: Double)

    enum class FieldError { ADDRESS_REQUIRED, LABEL_REQUIRED }

    sealed interface Event {
        data object Saved : Event
        data class Error(val message: String) : Event
    }

    private val addressId: String? = savedStateHandle[AddressBookFragment.ARG_ADDRESS_ID]

    private val _state = MutableStateFlow<UiState<Form>>(UiState.Loading)
    val state: StateFlow<UiState<Form>> = _state.asStateFlow()

    private val _fieldErrors = MutableStateFlow<Set<FieldError>>(emptySet())
    val fieldErrors: StateFlow<Set<FieldError>> = _fieldErrors.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** View state kept across view re-creation: the map position and whether the form was already filled once. */
    var camera: MapCamera? = null
    var isFormFilled = false

    init {
        load()
    }

    fun load() {
        if (addressId == null) {
            _state.value = UiState.Content(Form(null))
            return
        }
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = addressRepository.getAddress(addressId).map { Form(it) }.toUiState()
        }
    }

    /** [label] is the chosen quick label or the name typed for "Khác". The receiver is taken from the user's profile. */
    fun save(fullAddress: String, note: String, label: String, isDefault: Boolean, latitude: Double, longitude: Double) {
        val form = (_state.value as? UiState.Content)?.data ?: return
        if (_saving.value) return
        val errors = buildSet {
            if (fullAddress.isBlank()) add(FieldError.ADDRESS_REQUIRED)
            if (label.isBlank()) add(FieldError.LABEL_REQUIRED)
        }
        _fieldErrors.value = errors
        if (errors.isNotEmpty()) return

        _saving.value = true
        viewModelScope.launch {
            val existing = form.address
            val receiver = if (existing != null) {
                existing.receiverName to existing.receiverPhone
            } else {
                when (val profile = profileRepository.getProfileSummary()) {
                    is ApiResult.Success -> profile.data.fullName to profile.data.phoneNumber
                    is ApiResult.Failure -> {
                        _saving.value = false
                        _events.send(Event.Error(profile.message))
                        return@launch
                    }
                }
            }
            val result = addressRepository.saveAddress(
                Address(
                    id = existing?.id.orEmpty(),
                    label = label.trim(),
                    receiverName = receiver.first,
                    receiverPhone = receiver.second,
                    fullAddress = fullAddress.trim(),
                    latitude = latitude,
                    longitude = longitude,
                    note = note.trim().ifEmpty { null },
                    isDefault = isDefault,
                ),
            )
            _saving.value = false
            _events.send(
                when (result) {
                    is ApiResult.Success -> Event.Saved
                    is ApiResult.Failure -> Event.Error(result.message)
                },
            )
        }
    }
}

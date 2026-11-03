package vn.servio.customer.ui.address

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.customer.data.model.Address
import vn.servio.customer.data.repository.AddressRepository
import javax.inject.Inject

@HiltViewModel
class AddressBookViewModel @Inject constructor(
    private val repository: AddressRepository,
) : ViewModel() {

    /** [selectedId] is only used in select mode; it starts on the default address. */
    data class AddressBookContent(val addresses: List<Address>, val selectedId: String?) {
        val canAdd: Boolean get() = addresses.size < AddressRepository.MAX_ADDRESSES
    }

    private val _state = MutableStateFlow<UiState<AddressBookContent>>(UiState.Loading)
    val state: StateFlow<UiState<AddressBookContent>> = _state.asStateFlow()

    /** One-off error messages of "set default" and "delete". */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var selectedId: String? = null
    private var loadJob: Job? = null

    /** Called every time the screen is shown, so changes made on CS-12 appear when coming back. */
    fun load() {
        loadJob?.cancel()
        _state.value = UiState.Loading
        loadJob = viewModelScope.launch {
            _state.value = when (val result = repository.getAddresses()) {
                is ApiResult.Failure -> UiState.Error(result.message)
                is ApiResult.Success -> {
                    val addresses = result.data
                    if (addresses.none { it.id == selectedId }) selectedId = addresses.firstOrNull { it.isDefault }?.id
                    UiState.Content(AddressBookContent(addresses, selectedId))
                }
            }
        }
    }

    fun select(id: String) {
        selectedId = id
        val current = _state.value
        if (current is UiState.Content) _state.value = UiState.Content(current.data.copy(selectedId = id))
    }

    fun setDefault(id: String) = runAction { repository.setDefault(id) }

    fun delete(id: String) = runAction { repository.deleteAddress(id) }

    private fun runAction(action: suspend () -> ApiResult<Unit>) {
        viewModelScope.launch {
            when (val result = action()) {
                is ApiResult.Failure -> _messages.send(result.message)
                is ApiResult.Success -> load()
            }
        }
    }
}

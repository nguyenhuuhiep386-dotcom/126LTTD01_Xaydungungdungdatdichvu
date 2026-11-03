package vn.servio.customer.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.customer.data.model.ProfileSummary
import vn.servio.customer.data.repository.ProfileRepository
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ProfileSummary>>(UiState.Loading)
    val state: StateFlow<UiState<ProfileSummary>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch { _state.value = repository.getProfileSummary().toUiState() }
    }
}

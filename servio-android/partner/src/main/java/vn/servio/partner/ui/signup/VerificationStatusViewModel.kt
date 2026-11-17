package vn.servio.partner.ui.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.partner.data.model.PartnerMe
import vn.servio.partner.data.repository.PartnerProfileRepository
import javax.inject.Inject

/** PS-06 Chờ duyệt: reads the verification state from #17; the admin decides on AW-04. */
@HiltViewModel
class VerificationStatusViewModel @Inject constructor(
    private val profileRepository: PartnerProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PartnerMe>>(UiState.Loading)
    val state: StateFlow<UiState<PartnerMe>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch { _state.value = profileRepository.getMe().toUiState() }
    }
}

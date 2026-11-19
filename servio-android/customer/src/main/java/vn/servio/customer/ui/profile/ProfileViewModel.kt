package vn.servio.customer.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.UiState
import vn.servio.customer.data.model.ProfileSummary
import vn.servio.customer.data.repository.ProfileRepository
import javax.inject.Inject

/**
 * CS-29. [load] runs on every resume (the fee and the unread count change on other screens): once the screen has
 * content, a reload keeps it on screen and a failed reload keeps the previous data instead of the error view.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ProfileSummary>>(UiState.Loading)
    val state: StateFlow<UiState<ProfileSummary>> = _state.asStateFlow()

    /** A reload over content that is already shown (resume, pull to refresh). */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var loadJob: Job? = null

    fun load() {
        loadJob?.cancel()
        val shown: UiState.Content<ProfileSummary>? = when (val current = _state.value) {
            is UiState.Content -> current
            else -> null
        }
        if (shown == null) _state.value = UiState.Loading else _refreshing.value = true
        loadJob = viewModelScope.launch {
            val result = repository.getProfileSummary()
            _state.value = when (result) {
                is ApiResult.Success -> UiState.Content(result.data)
                is ApiResult.Failure -> shown ?: UiState.Error(result.message)
            }
            _refreshing.value = false
        }
    }
}

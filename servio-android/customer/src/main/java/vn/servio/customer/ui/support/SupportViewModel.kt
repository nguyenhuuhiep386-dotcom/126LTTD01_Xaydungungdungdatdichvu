package vn.servio.customer.ui.support

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
import vn.servio.customer.data.model.ServerConfig
import vn.servio.customer.data.repository.ServerConfigRepository
import javax.inject.Inject

/**
 * CS-34 Trung tâm hỗ trợ: everything on it comes from #107, asked again each time the screen opens. Shows the
 * session's last config right away; the full-screen error only when this session has none.
 */
@HiltViewModel
class SupportViewModel @Inject constructor(
    private val serverConfig: ServerConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ServerConfig>>(serverConfig.latest?.let { UiState.Content(it) } ?: UiState.Loading)
    val state: StateFlow<UiState<ServerConfig>> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    /** Also "Thử lại". A failure keeps what is shown (the repository answers with the session's last config). */
    fun load() {
        if (loadJob?.isActive == true) return
        if (_state.value is UiState.Error) _state.value = UiState.Loading
        loadJob = viewModelScope.launch {
            when (val result = serverConfig.getConfig()) {
                is ApiResult.Success -> _state.value = UiState.Content(result.data)
                is ApiResult.Failure -> if (_state.value !is UiState.Content) _state.value = UiState.Error(result.message)
            }
        }
    }
}

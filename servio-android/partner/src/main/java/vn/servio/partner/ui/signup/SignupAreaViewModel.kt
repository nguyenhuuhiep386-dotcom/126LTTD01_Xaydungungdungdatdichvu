package vn.servio.partner.ui.signup

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
import vn.servio.core.utils.UiState
import vn.servio.core.utils.toUiState
import vn.servio.partner.data.location.DeviceLocation
import vn.servio.partner.data.location.Fix
import vn.servio.partner.data.model.PartnerMe
import vn.servio.partner.data.model.UpdatePartnerRequest
import vn.servio.partner.data.repository.PartnerProfileRepository
import javax.inject.Inject

/** PS-04 Bước 3 (also PS-23 "Khu vực nhận việc"): anchor point and radius, saved with #18. */
@HiltViewModel
class SignupAreaViewModel @Inject constructor(
    private val profileRepository: PartnerProfileRepository,
    private val deviceLocation: DeviceLocation,
) : ViewModel() {

    data class MapCamera(val latitude: Double, val longitude: Double, val zoom: Double)

    sealed interface Event {
        data object Saved : Event
        data class Error(val message: String) : Event
        data class MoveTo(val fix: Fix) : Event
        data object LocationUnavailable : Event
    }

    private val _state = MutableStateFlow<UiState<PartnerMe>>(UiState.Loading)
    val state: StateFlow<UiState<PartnerMe>> = _state.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** Kept across rotation; null until the map has been shown once. */
    var camera: MapCamera? = null
    var radiusKm: Int = DEFAULT_RADIUS
    var isFormFilled = false

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val result = profileRepository.getMe()
            if (result is ApiResult.Success && !isFormFilled) radiusKm = result.data.serviceRadiusKm
            _state.value = result.toUiState()
        }
    }

    fun locate(showErrors: Boolean) {
        viewModelScope.launch {
            val fix = deviceLocation.current()
            when {
                fix != null -> _events.send(Event.MoveTo(fix))
                showErrors -> _events.send(Event.LocationUnavailable)
            }
        }
    }

    fun save(latitude: Double, longitude: Double) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            val request = UpdatePartnerRequest(serviceRadiusKm = radiusKm, anchorLatitude = latitude, anchorLongitude = longitude)
            val result = profileRepository.update(request)
            _saving.value = false
            _events.send(
                when (result) {
                    is ApiResult.Success -> Event.Saved
                    is ApiResult.Failure -> Event.Error(result.message)
                },
            )
        }
    }

    companion object {
        const val DEFAULT_RADIUS = 10
        val RADII = listOf(3, 5, 10, 20)
    }
}

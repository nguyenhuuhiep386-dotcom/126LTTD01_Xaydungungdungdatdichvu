package vn.servio.partner.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.servio.core.network.ApiResult
import vn.servio.core.network.apiCall
import vn.servio.core.network.apiCallNoContent
import vn.servio.core.realtime.Realtime
import vn.servio.partner.data.location.DeviceLocation
import vn.servio.partner.data.location.Fix
import vn.servio.partner.data.model.HeartbeatRequest
import vn.servio.partner.data.model.OnlineStatusRequest
import vn.servio.partner.data.remote.PresenceApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Online switch on PS-07 (F-PROF-07). Online = #25 with the current position, then a heartbeat (#26)
 * every 30 seconds with a fresh position; the server drops a partner after 10 minutes without one.
 * Only Online partners receive NewPost. ponytail: heartbeats run while the app process lives (no foreground
 * service while idle), which is enough for the demo; the order trip uses its own foreground service (DT-4).
 */
interface PresenceRepository {
    val isOnline: StateFlow<Boolean>

    /** Sets the state the server already has (from #17), without calling #25. Starts heartbeats when online. */
    fun restore(online: Boolean)

    suspend fun goOnline(fix: Fix): ApiResult<Unit>
    suspend fun goOffline(): ApiResult<Unit>
}

@Singleton
class RemotePresenceRepository @Inject constructor(
    private val api: PresenceApi,
    private val deviceLocation: DeviceLocation,
    private val realtime: Realtime,
) : PresenceRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeat: Job? = null

    private val _isOnline = MutableStateFlow(false)
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    override fun restore(online: Boolean) {
        _isOnline.value = online
        if (online) startHeartbeat() else stopHeartbeat()
    }

    override suspend fun goOnline(fix: Fix): ApiResult<Unit> {
        val result = apiCall { api.setOnline(OnlineStatusRequest(true, fix.latitude, fix.longitude)) }
        return when (result) {
            is ApiResult.Success -> {
                restore(result.data.isOnline)
                realtime.feed.start()
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    override suspend fun goOffline(): ApiResult<Unit> {
        val result = apiCall { api.setOnline(OnlineStatusRequest(false)) }
        if (result is ApiResult.Success) restore(false)
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
    }

    private fun startHeartbeat() {
        if (heartbeat?.isActive == true) return
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MILLIS)
                val fix = deviceLocation.current(maxAgeMillis = HEARTBEAT_MILLIS) ?: continue
                val result = apiCallNoContent { api.heartbeat(HeartbeatRequest(fix.latitude, fix.longitude)) }
                // The server dropped us (timeout, or toggled off elsewhere): show Offline instead of pretending.
                if (result is ApiResult.Failure && result.code == PARTNER_OFFLINE) {
                    _isOnline.value = false
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel()
        heartbeat = null
    }

    private companion object {
        const val HEARTBEAT_MILLIS = 30_000L
        const val PARTNER_OFFLINE = "PARTNER_OFFLINE"
    }
}

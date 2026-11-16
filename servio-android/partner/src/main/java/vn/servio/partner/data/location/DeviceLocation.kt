package vn.servio.partner.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** One position fix of the device, as the check-in API wants it (#68). */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val capturedAtMillis: Long,
    val isMock: Boolean,
)

/**
 * Device position through the platform LocationManager (no Google Play services, same approach as CS-12).
 * Used by PS-04 (anchor), the Online toggle and heartbeats (PS-07), and arriving at the customer (PS-15).
 */
@Singleton
class DeviceLocation @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val hasPermission: Boolean
        get() = PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    val isEnabled: Boolean
        get() = context.getSystemService<LocationManager>()?.let(LocationManagerCompat::isLocationEnabled) == true

    /**
     * A fresh fix when [maxAgeMillis] allows none of the last-known ones; null when there is no permission,
     * location is off, or nothing arrives within [timeoutMillis].
     */
    @SuppressLint("MissingPermission")
    suspend fun current(maxAgeMillis: Long = 60_000, timeoutMillis: Long = 15_000): Fix? {
        if (!hasPermission) return null
        val manager = context.getSystemService<LocationManager>() ?: return null
        if (!LocationManagerCompat.isLocationEnabled(manager)) return null
        val providers = manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
        if (providers.isEmpty()) return null

        val now = System.currentTimeMillis()
        val recent = providers
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time <= maxAgeMillis }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent.toFix()

        val provider = when {
            LocationManager.GPS_PROVIDER in providers && hasFinePermission() -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> providers.first()
        }
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    LocationManagerCompat.getCurrentLocation(
                        manager, provider, signal, ContextCompat.getMainExecutor(context),
                    ) { location -> if (continuation.isActive) continuation.resume(location?.toFix()) }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }
    }

    private fun hasFinePermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

        fun Location.toFix() = Fix(
            latitude = latitude,
            longitude = longitude,
            accuracy = if (hasAccuracy()) accuracy else 9_999f,
            capturedAtMillis = time,
            isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider,
        )
    }
}

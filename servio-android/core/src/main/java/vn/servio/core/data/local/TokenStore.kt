package vn.servio.core.data.local

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the session tokens and the installation id.
 * ponytail: plain SharedPreferences (agreed for the course demo); move to encrypted storage before a real release.
 */
@Singleton
class TokenStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("servio_session", Context.MODE_PRIVATE)

    val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)
    val hasSession: Boolean get() = refreshToken != null

    /** App-installation UUID sent as deviceId (spec: not the Android ID). Created once. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit { putString(KEY_DEVICE, id) }
        }

    /** Written synchronously: the server has already revoked the old refresh token, losing the new one means logout. */
    fun save(accessToken: String, refreshToken: String) = prefs.edit(commit = true) {
        putString(KEY_ACCESS, accessToken)
        putString(KEY_REFRESH, refreshToken)
    }

    fun clear() = prefs.edit {
        remove(KEY_ACCESS)
        remove(KEY_REFRESH)
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_DEVICE = "device_id"
    }
}

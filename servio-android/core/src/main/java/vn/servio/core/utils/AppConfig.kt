package vn.servio.core.utils

import vn.servio.core.data.model.AppFlavor

/**
 * Per-app settings that :core needs. Each app module provides one instance in its Hilt AppModule.
 * [platformHeader] is sent as X-Client-Platform (spec 6.1).
 */
data class AppConfig(
    val flavor: AppFlavor,
    val versionName: String,
) {
    val platformHeader: String
        get() = if (flavor == AppFlavor.CUSTOMER) "android-customer" else "android-partner"
}

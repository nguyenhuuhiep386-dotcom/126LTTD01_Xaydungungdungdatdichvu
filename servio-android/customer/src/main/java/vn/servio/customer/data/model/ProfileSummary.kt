package vn.servio.customer.data.model

/**
 * Data of CS-29 Tài khoản: the signed-in user (#9), the number of saved addresses (#12) and the counters under the
 * menu rows. [wallet] (#87) and [unreadNotificationCount] (#104) are null when their call failed: those rows show "–"
 * while the rest of the screen still works.
 */
data class ProfileSummary(
    val fullName: String,
    val phoneNumber: String,
    val avatarUrl: String?,
    val wallet: Wallet?,
    val addressCount: Int,
    val unreadNotificationCount: Int?,
)

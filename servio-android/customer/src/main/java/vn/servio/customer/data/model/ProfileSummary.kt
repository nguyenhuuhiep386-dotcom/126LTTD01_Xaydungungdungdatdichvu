package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/**
 * Data of CS-29 Tài khoản: the signed-in user plus the counters shown under each menu row.
 * [outstandingFeeVnd] and [unreadNotificationCount] are null while their endpoints (#156, #104) do not exist yet.
 */
@Serializable
data class ProfileSummary(
    val fullName: String,
    val phoneNumber: String,
    val avatarUrl: String?,
    val outstandingFeeVnd: Long?,
    val addressCount: Int,
    val unreadNotificationCount: Int?,
)

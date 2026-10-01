package vn.servio.core.data.model

import kotlinx.serialization.Serializable

/** #9 GET /users/me */
@Serializable
data class Me(
    val id: String,
    val phoneNumber: String,
    val fullName: String,
    val avatarUrl: String? = null,
    val email: String? = null,
    val dateOfBirth: String? = null,
    val gender: Gender? = null,
    val roles: List<UserRole> = emptyList(),
    val customerProfile: CustomerProfile? = null,
    val partnerProfile: PartnerProfileSummary? = null,
) {
    /** Same rule as the server: a name of at least 2 characters is required. */
    val needsProfileCompletion: Boolean get() = fullName.trim().length < 2
}

@Serializable
data class CustomerProfile(
    val id: String,
    val averageRating: Double? = null,
    val totalReviews: Int = 0,
    val totalOrders: Int = 0,
)

@Serializable
data class PartnerProfileSummary(
    val id: String,
    val verificationStatus: PartnerVerificationStatus,
    val verificationNote: String? = null,
    val isOnline: Boolean = false,
    val serviceRadiusKm: Int = 10,
    val averageRating: Double? = null,
    val totalReviews: Int = 0,
)

/** #10 PATCH /users/me — null fields are not changed. */
@Serializable
data class UpdateMeRequest(
    val fullName: String? = null,
    val avatarUrl: String? = null,
    val dateOfBirth: String? = null,
    val gender: Gender? = null,
    val email: String? = null,
)

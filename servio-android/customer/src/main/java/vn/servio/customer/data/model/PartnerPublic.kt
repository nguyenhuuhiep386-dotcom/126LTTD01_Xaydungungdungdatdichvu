package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/**
 * GET /partners/{id} (#27): public profile, only for users related to the partner through a quote, a chat or an
 * order (404 otherwise). [skills] are the names of the approved skills. The server sends no "identity verified"
 * flag and no work photos.
 */
@Serializable
data class PartnerPublic(
    val id: String,
    val fullName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val yearsOfExperience: Int = 0,
    val averageRating: Double? = null,
    val totalReviews: Int = 0,
    val completedOrders: Int = 0,
    val skills: List<String> = emptyList(),
    val memberSince: String? = null,
)

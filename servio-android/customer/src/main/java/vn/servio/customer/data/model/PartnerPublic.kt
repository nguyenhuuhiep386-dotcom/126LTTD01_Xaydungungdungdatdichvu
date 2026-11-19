package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/**
 * GET /partners/{id} (#27): public profile, only for users related to the partner through a quote, a chat or an
 * order (404 otherwise). [skills] are the names of the approved skills. [identityVerified] = KYC approved by an
 * admin. The server sends no work photos.
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
    val identityVerified: Boolean = false,
    val memberSince: String? = null,
)

/**
 * One row of GET /partners/{id}/reviews (#28): a published customer review, newest first. [reviewerName] is already
 * shortened by the server ("Lan N."). [tags] are not shown on CS-17.
 */
@Serializable
data class PartnerReview(
    val id: String,
    val rating: Int,
    val comment: String? = null,
    val tags: List<String> = emptyList(),
    val reviewerName: String,
    val createdAt: String,
)

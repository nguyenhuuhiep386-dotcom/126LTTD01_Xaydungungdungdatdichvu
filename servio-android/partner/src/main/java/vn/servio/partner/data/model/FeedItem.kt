package vn.servio.partner.data.model

import kotlinx.serialization.Serializable

/**
 * One post in GET /feed (#46) and the NewPost event on /hubs/feed. Subset of the server FeedItemDto
 * (unknown fields are ignored). The partner never sees the exact address or phone before ACCEPTED,
 * only an area label and the distance (spec 6.3 #38).
 */
@Serializable
data class FeedItem(
    val requestId: String,
    val title: String,
    val categoryName: String,
    val areaLabel: String,
    val distanceKm: Double,
    val budgetMin: Long? = null,
    val budgetMax: Long? = null,
    val quoteCount: Int,
    val code: String = "",
    val hasQuoted: Boolean = false,
    val imageCount: Int = 0,
    val thumbnailUrl: String? = null,
    val publishedAt: String? = null,
)

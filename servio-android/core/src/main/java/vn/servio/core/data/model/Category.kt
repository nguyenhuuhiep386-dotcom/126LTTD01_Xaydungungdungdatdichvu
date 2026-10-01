package vn.servio.core.data.model

import kotlinx.serialization.Serializable

/** #29 / #30 service category. Level-1 groups carry their level-2 services in [children]. */
@Serializable
data class Category(
    val id: String,
    val parentId: String? = null,
    val name: String,
    val slug: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val referencePriceMin: Long? = null,
    val referencePriceMax: Long? = null,
    val requiresCertificate: Boolean = false,
    val displayOrder: Int = 0,
    val children: List<Category> = emptyList(),
)

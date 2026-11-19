package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/** #104 GET /notifications/unread-count: [total] unread in this app, and per type (NEW_QUOTE, ORDER_STATUS_CHANGED …). */
@Serializable
data class UnreadCount(
    val total: Int = 0,
    val byType: Map<String, Int> = emptyMap(),
)

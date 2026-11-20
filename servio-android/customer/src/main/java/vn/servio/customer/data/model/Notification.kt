package vn.servio.customer.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** #104 GET /notifications/unread-count: [total] unread in this app, and per type (NEW_QUOTE, ORDER_STATUS_CHANGED …). */
@Serializable
data class UnreadCount(
    val total: Int = 0,
    val byType: Map<String, Int> = emptyMap(),
)

/**
 * Row of #101 GET /notifications. [type] is the server enum (NEW_QUOTE, ORDER_STATUS_CHANGED, REVIEW, PAYMENT …), kept
 * as text so a new type does not break the list. [data] is the payload, e.g. { "orderId": "…" }; [deepLink] e.g.
 * "orders/{id}", "service-requests/{id}" or "wallet". [createdAt] is ISO-8601 with offset.
 */
@Serializable
data class AppNotification(
    val id: String,
    val type: String = "",
    val title: String = "",
    val body: String = "",
    val data: JsonObject? = null,
    val deepLink: String? = null,
    val isRead: Boolean = false,
    val createdAt: String = "",
)

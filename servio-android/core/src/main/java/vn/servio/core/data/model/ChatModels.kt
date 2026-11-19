package vn.servio.core.data.model

import kotlinx.serialization.Serializable
import vn.servio.core.utils.Money

// Chat (BE-3, spec 6 #54–#61, hub /hubs/chat). Shared by CS-27/CS-28 (customer) and PS-20 (partner).

@Serializable
enum class ConversationStatus { ACTIVE, ARCHIVED, READ_ONLY, BLOCKED }

@Serializable
enum class MessageType { TEXT, IMAGE, SYSTEM }

/** Who wrote a message (server ActorType). */
@Serializable
enum class SenderRole { CUSTOMER, PARTNER, SYSTEM, ADMIN }

/** The other person: a partner sees the customer's short name, a customer sees the partner's full name. */
@Serializable
data class Counterpart(
    val partnerProfileId: String? = null,
    val displayName: String,
    val avatarUrl: String? = null,
    val rating: Double? = null,
)

@Serializable
data class LastMessage(val preview: String, val at: String, val isMine: Boolean)

/** Row of #54 and result of #55 / #56. */
@Serializable
data class Conversation(
    val id: String,
    val status: ConversationStatus,
    val canSend: Boolean,
    val serviceRequestId: String,
    val requestCode: String = "",
    val requestTitle: String = "",
    val requestStatus: String? = null,
    val orderId: String? = null,
    /** Set once the post became an order with this partner (#54/#55). */
    val orderCode: String? = null,
    val orderStatus: String? = null,
    /** Agreed price of the order, else this partner's newest quote; decimal on the server, so Double here. */
    val price: Double? = null,
    val counterpart: Counterpart,
    val lastMessage: LastMessage? = null,
    val unreadCount: Int = 0,
    val createdAt: String? = null,
) {
    /**
     * "#SV-2087 · 450.000 đ · Máy lạnh chảy nước" once ordered, else the post code, quoted price and title.
     * Title goes last so a one-line ellipsis only ever cuts the title, never the code or the price.
     */
    val contextLine: String
        get() = listOfNotNull(
            orderCode?.let { "#$it" } ?: requestCode.takeIf { it.isNotBlank() },
            price?.let { Money.format(it.toLong()) },
            requestTitle.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}

/** A message (#57, #58, hub ReceiveMessage). */
@Serializable
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val clientMessageId: String,
    val senderRole: SenderRole,
    val type: MessageType,
    val content: String = "",
    val attachmentUrl: String? = null,
    val createdAt: String,
    val readAt: String? = null,
)

/** #57: history pages newest → oldest (nextCursor), sync pages oldest → newest (syncCursor). */
@Serializable
data class MessagePage(
    val items: List<ChatMessage>,
    val nextCursor: String? = null,
    val syncCursor: String? = null,
    val hasMore: Boolean = false,
)

/** #58 body. [clientMessageId] is generated before sending, so a retry never creates a duplicate. */
@Serializable
data class SendMessageRequest(
    val clientMessageId: String,
    val type: MessageType,
    val content: String? = null,
    val attachmentUrl: String? = null,
)

/** #56 open (or get) the conversation of a post with a partner. */
@Serializable
data class OpenConversationRequest(val serviceRequestId: String, val partnerProfileId: String)

/** #59 */
@Serializable
data class MarkReadRequest(val lastReadMessageId: String)

/** Hub MessageRead: [userId] has read up to [lastReadMessageId]. */
@Serializable
data class MessageReadEvent(val conversationId: String, val userId: String, val lastReadMessageId: String)

@Serializable
data class UnreadCount(val total: Int)

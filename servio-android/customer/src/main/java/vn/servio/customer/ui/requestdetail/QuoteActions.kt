package vn.servio.customer.ui.requestdetail

/**
 * Button row of a quote on CS-15 and CS-16 (Figma): "Nhắn tin" (outlined) when the quote has a conversation,
 * "Chọn" (filled) when it can be chosen. A quote that cannot be chosen keeps only "Nhắn tin"; no button hides the row.
 */
data class QuoteActions(val chat: Boolean, val select: Boolean) {
    val row: Boolean get() = chat || select

    companion object {
        fun of(selectable: Boolean, conversationId: String?) = QuoteActions(chat = conversationId != null, select = selectable)
    }
}

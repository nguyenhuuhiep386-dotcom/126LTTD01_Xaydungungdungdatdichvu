package vn.servio.core.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.servio.core.data.model.AppFlavor
import vn.servio.core.data.model.ChatMessage
import vn.servio.core.data.model.Conversation
import vn.servio.core.data.model.MessageType
import vn.servio.core.data.model.SenderRole
import vn.servio.core.data.repository.ChatRepository
import vn.servio.core.network.ApiResult
import vn.servio.core.utils.AppConfig
import vn.servio.core.utils.UiState
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject

/**
 * CS-28 / PS-20a Phòng chat. Text messages show at once as SENDING, become SENT when #58 answers, FAILED
 * (tap to resend with the same clientMessageId) otherwise. Live messages and read receipts come from /hubs/chat.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    appConfig: AppConfig,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle[ChatFragment.ARG_CONVERSATION_ID])
    private val myRole = if (appConfig.flavor == AppFlavor.CUSTOMER) SenderRole.CUSTOMER else SenderRole.PARTNER

    enum class SendState { SENDING, SENT, READ, FAILED }

    data class Item(val message: ChatMessage, val mine: Boolean, val sendState: SendState) {
        val key: String get() = message.clientMessageId
        val isSystem: Boolean get() = message.type == MessageType.SYSTEM || message.senderRole == SenderRole.SYSTEM
    }

    data class Content(val conversation: Conversation, val items: List<Item>, val hasMore: Boolean)

    private val _state = MutableStateFlow<UiState<Content>>(UiState.Loading)
    val state: StateFlow<UiState<Content>> = _state.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)

    /** Error messages to show in a snackbar. */
    val events: Flow<String> = _events.receiveAsFlow()

    private var nextCursor: String? = null
    private var syncCursor: String? = null
    private var loadingMore = false
    private var lastMarkedRead: String? = null

    init {
        load()
        viewModelScope.launch {
            repository.incoming().filter { it.conversationId == conversationId }.collect { upsert(listOf(it)) }
        }
        viewModelScope.launch {
            repository.reads(conversationId).collect { markMineReadUpTo(it.lastReadMessageId) }
        }
        viewModelScope.launch { repository.reconnected().collect { syncMissed() } }
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val header = async { repository.conversation(conversationId) }
            val page = async { repository.history(conversationId, cursor = null) }
            val headerResult = header.await()
            val pageResult = page.await()
            _state.value = when {
                headerResult is ApiResult.Failure -> UiState.Error(headerResult.message)
                pageResult is ApiResult.Failure -> UiState.Error(pageResult.message)
                else -> {
                    val data = (pageResult as ApiResult.Success).data
                    nextCursor = data.nextCursor
                    syncCursor = data.syncCursor
                    val items = data.items.reversed().map(::itemOf)
                    UiState.Content(Content((headerResult as ApiResult.Success).data, items, data.hasMore))
                }
            }
            markRead()
        }
    }

    /** Older messages when the list reaches the top. */
    fun loadMore() {
        val cursor = nextCursor ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            val result = repository.history(conversationId, cursor)
            loadingMore = false
            if (result is ApiResult.Success) {
                nextCursor = result.data.nextCursor
                val older = result.data.items.reversed().map(::itemOf)
                updateContent { content ->
                    val known = content.items.map { it.key }.toSet()
                    content.copy(items = older.filter { it.key !in known } + content.items, hasMore = result.data.hasMore)
                }
            }
        }
    }

    fun send(text: String) {
        val content = (_state.value as? UiState.Content)?.data ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !content.conversation.canSend) return
        val clientId = UUID.randomUUID().toString()
        val pending = ChatMessage(
            id = clientId,
            conversationId = conversationId,
            clientMessageId = clientId,
            senderRole = myRole,
            type = MessageType.TEXT,
            content = trimmed.take(MAX_LENGTH),
            createdAt = OffsetDateTime.now().toString(),
        )
        updateContent { it.copy(items = it.items + Item(pending, mine = true, sendState = SendState.SENDING)) }
        deliver(pending)
    }

    /** Resends a FAILED message with its original clientMessageId (the server ignores duplicates). */
    fun retry(item: Item) {
        if (item.sendState != SendState.FAILED) return
        setSendState(item.key, SendState.SENDING)
        deliver(item.message)
    }

    private fun deliver(message: ChatMessage) {
        viewModelScope.launch {
            when (val result = repository.sendText(conversationId, message.clientMessageId, message.content)) {
                is ApiResult.Success -> upsert(listOf(result.data))
                is ApiResult.Failure -> {
                    setSendState(message.clientMessageId, SendState.FAILED)
                    _events.send(result.message)
                }
            }
        }
    }

    private fun syncMissed() {
        val cursor = syncCursor ?: return load()
        viewModelScope.launch {
            val result = repository.sync(conversationId, cursor)
            if (result is ApiResult.Success) {
                result.data.syncCursor?.let { syncCursor = it }
                upsert(result.data.items)
            }
            // The header may have changed while offline (e.g. the post closed: READ_ONLY).
            val header = repository.conversation(conversationId)
            if (header is ApiResult.Success) updateContent { it.copy(conversation = header.data) }
        }
    }

    /** Adds or replaces messages (matched by clientMessageId, so the server copy replaces the pending one). */
    private fun upsert(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        updateContent { content ->
            val items = content.items.toMutableList()
            messages.forEach { message ->
                val item = itemOf(message)
                val index = items.indexOfFirst { it.key == item.key || it.message.id == message.id }
                if (index >= 0) items[index] = item else items.add(item)
            }
            content.copy(items = items.sortedBy { instantOf(it.message.createdAt) })
        }
        markRead()
    }

    private fun markMineReadUpTo(messageId: String) {
        updateContent { content ->
            val upTo = content.items.indexOfFirst { it.message.id == messageId }
            if (upTo < 0) return@updateContent content
            content.copy(
                items = content.items.mapIndexed { index, item ->
                    if (index <= upTo && item.mine && item.sendState == SendState.SENT) item.copy(sendState = SendState.READ) else item
                },
            )
        }
    }

    /** #59 up to the newest message from the other person, once per message. */
    private fun markRead() {
        val content = (_state.value as? UiState.Content)?.data ?: return
        val last = content.items.lastOrNull { !it.mine && !it.isSystem }?.message?.id ?: return
        if (last == lastMarkedRead) return
        lastMarkedRead = last
        viewModelScope.launch { repository.markRead(conversationId, last) }
    }

    private fun setSendState(key: String, state: SendState) = updateContent { content ->
        content.copy(items = content.items.map { if (it.key == key) it.copy(sendState = state) else it })
    }

    private fun itemOf(message: ChatMessage): Item {
        val mine = message.senderRole == myRole
        val state = if (mine && message.readAt != null) SendState.READ else SendState.SENT
        return Item(message, mine, state)
    }

    private fun updateContent(transform: (Content) -> Content) {
        _state.update { state -> if (state is UiState.Content) UiState.Content(transform(state.data)) else state }
    }

    private companion object {
        const val MAX_LENGTH = 2000

        /** Server times may use another offset than the device: compare instants, not strings. */
        fun instantOf(iso: String): Instant = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrDefault(Instant.MAX)
    }
}

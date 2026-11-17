package vn.servio.core.data.repository

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import vn.servio.core.data.model.ChatMessage
import vn.servio.core.data.model.Conversation
import vn.servio.core.data.model.MarkReadRequest
import vn.servio.core.data.model.MessagePage
import vn.servio.core.data.model.MessageReadEvent
import vn.servio.core.data.model.MessageType
import vn.servio.core.data.model.OpenConversationRequest
import vn.servio.core.data.model.SendMessageRequest
import vn.servio.core.data.model.UnreadCount
import vn.servio.core.network.ApiResponse
import vn.servio.core.network.ApiResult
import vn.servio.core.network.PagedResult
import vn.servio.core.network.apiCall
import vn.servio.core.network.apiCallNoContent
import vn.servio.core.network.map
import vn.servio.core.realtime.Realtime
import javax.inject.Inject
import javax.inject.Singleton

/** #54–#61. Paths are relative to BASE_URL + "api/v1/". */
interface ChatApi {
    @GET("conversations")
    suspend fun list(
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
    ): ApiResponse<PagedResult<Conversation>>

    @GET("conversations/unread-count")
    suspend fun unreadCount(): ApiResponse<UnreadCount>

    @GET("conversations/{id}")
    suspend fun get(@Path("id") id: String): ApiResponse<Conversation>

    @POST("conversations")
    suspend fun open(@Body body: OpenConversationRequest): ApiResponse<Conversation>

    @GET("conversations/{id}/messages")
    suspend fun messages(
        @Path("id") id: String,
        @Query("cursor") cursor: String? = null,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int? = null,
    ): ApiResponse<MessagePage>

    @POST("conversations/{id}/messages")
    suspend fun send(@Path("id") id: String, @Body body: SendMessageRequest): ApiResponse<ChatMessage>

    @POST("conversations/{id}/read")
    suspend fun markRead(@Path("id") id: String, @Body body: MarkReadRequest): Response<Unit>
}

/**
 * Chat for both apps. Messages are sent over HTTP (#58, idempotent through clientMessageId) and received live
 * from /hubs/chat (ReceiveMessage, MessageRead); after a reconnect the screen syncs with `?after=syncCursor`.
 */
interface ChatRepository {
    suspend fun conversations(): ApiResult<List<Conversation>>
    suspend fun unreadCount(): ApiResult<Int>
    suspend fun conversation(id: String): ApiResult<Conversation>

    /** #56: the conversation between a post and a partner (partner: own profile id), created on first use. */
    suspend fun open(serviceRequestId: String, partnerProfileId: String): ApiResult<Conversation>

    /** Newest page when [cursor] is null, else the page before it (newest → oldest). */
    suspend fun history(conversationId: String, cursor: String?): ApiResult<MessagePage>

    /** Messages after [syncCursor], oldest → newest (after a reconnect). */
    suspend fun sync(conversationId: String, syncCursor: String): ApiResult<MessagePage>

    suspend fun sendText(conversationId: String, clientMessageId: String, text: String): ApiResult<ChatMessage>
    suspend fun markRead(conversationId: String, lastReadMessageId: String): ApiResult<Unit>

    /** Live messages of every conversation (filter by conversationId); opens the hub. */
    fun incoming(): Flow<ChatMessage>
    fun reads(conversationId: String): Flow<MessageReadEvent>
    fun reconnected(): Flow<Unit>
}

@Singleton
class RemoteChatRepository @Inject constructor(
    private val api: ChatApi,
    private val realtime: Realtime,
) : ChatRepository {

    override suspend fun conversations() = apiCall { api.list() }.map { it.items }

    override suspend fun unreadCount() = apiCall { api.unreadCount() }.map { it.total }

    override suspend fun conversation(id: String) = apiCall { api.get(id) }

    override suspend fun open(serviceRequestId: String, partnerProfileId: String) =
        apiCall { api.open(OpenConversationRequest(serviceRequestId, partnerProfileId)) }

    override suspend fun history(conversationId: String, cursor: String?) =
        apiCall { api.messages(conversationId, cursor = cursor, limit = PAGE_SIZE) }

    override suspend fun sync(conversationId: String, syncCursor: String) =
        apiCall { api.messages(conversationId, after = syncCursor, limit = 100) }

    override suspend fun sendText(conversationId: String, clientMessageId: String, text: String) =
        apiCall { api.send(conversationId, SendMessageRequest(clientMessageId, MessageType.TEXT, content = text)) }

    override suspend fun markRead(conversationId: String, lastReadMessageId: String) =
        apiCallNoContent { api.markRead(conversationId, MarkReadRequest(lastReadMessageId)) }

    override fun incoming(): Flow<ChatMessage> {
        realtime.chat.start()
        return realtime.chat.listen(EVENT_MESSAGE, ChatMessage.serializer())
    }

    override fun reads(conversationId: String): Flow<MessageReadEvent> {
        realtime.chat.start()
        return realtime.chat.listen(EVENT_READ, MessageReadEvent.serializer()).filter { it.conversationId == conversationId }
    }

    override fun reconnected(): Flow<Unit> = realtime.chat.reconnected

    private companion object {
        const val EVENT_MESSAGE = "ReceiveMessage"
        const val EVENT_READ = "MessageRead"
        const val PAGE_SIZE = 30
    }
}

/** Chat bindings, kept apart from NetworkModule/RepositoryModule so the shared files stay untouched. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {

    @Binds
    abstract fun bindChatRepository(impl: RemoteChatRepository): ChatRepository

    companion object {
        @Provides
        @Singleton
        fun provideChatApi(retrofit: Retrofit): ChatApi = retrofit.create(ChatApi::class.java)
    }
}

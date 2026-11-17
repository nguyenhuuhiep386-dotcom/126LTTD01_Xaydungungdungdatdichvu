package vn.servio.core.realtime

import kotlinx.serialization.json.Json
import vn.servio.core.BuildConfig
import vn.servio.core.data.local.TokenStore
import vn.servio.core.data.remote.UserApi
import vn.servio.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three SignalR hubs of the backend, one connection each per app process. Inject this and call
 * e.g. `realtime.feed.start()` then collect `realtime.feed.listen("NewPost", FeedItem.serializer())`.
 */
@Singleton
class Realtime @Inject constructor(
    tokenStore: TokenStore,
    json: Json,
    userApi: UserApi,
) {
    // #9 is the cheapest authenticated call; going through OkHttp lets TokenAuthenticator refresh the token.
    private val refreshSession: suspend () -> Unit = { apiCall { userApi.getMe() } }

    /** Partner: `NewPost`. */
    val feed = HubClient(BuildConfig.BASE_URL + "hubs/feed", tokenStore, json, refreshSession)

    /** Quotes and orders for both apps; `UpdateLocation` for the partner. */
    val orders = HubClient(BuildConfig.BASE_URL + "hubs/orders", tokenStore, json, refreshSession)

    /** `ReceiveMessage`, `MessageAck`, `MessageRead`; methods `SendMessage`, `MarkRead`, `JoinConversation`. */
    val chat = HubClient(BuildConfig.BASE_URL + "hubs/chat", tokenStore, json, refreshSession)

    /** Closes every hub (call on logout). */
    fun stopAll() {
        feed.stop()
        orders.stop()
        chat.stop()
    }
}

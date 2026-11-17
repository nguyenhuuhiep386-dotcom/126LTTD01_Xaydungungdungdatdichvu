package vn.servio.customer.data.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import vn.servio.core.realtime.HubClient
import vn.servio.core.realtime.Realtime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "The quotes of this request may have changed" (CS-15), from /hubs/orders: NewQuote (payload CustomerQuoteDto),
 * QuoteWithdrawn ({ serviceRequestId, quoteId }) of this request, and every (re)connect (events sent while the hub
 * was down are not replayed). The screen answers by reloading #42.
 */
interface QuoteUpdates {
    fun changes(requestId: String): Flow<Unit>

    companion object {
        const val EVENT_NEW_QUOTE = "NewQuote"
        const val EVENT_QUOTE_WITHDRAWN = "QuoteWithdrawn"

        /** Both payloads carry serviceRequestId; ids are GUIDs, compared without case. */
        fun isFor(payload: JsonObject, requestId: String): Boolean {
            val id = (payload["serviceRequestId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            return requestId.isNotEmpty() && id.equals(requestId, ignoreCase = true)
        }
    }
}

/**
 * Uses the app-wide orders hub of core. [Realtime.orders] start() is idempotent (does nothing while its loop runs),
 * so every CS-15 can call it; it is never stopped here because other screens share the connection.
 *
 * Order matters: the events are registered before start(). HubClient copies the registered names onto a new
 * connection before opening it but only keeps the connection once it is open, so a listen() that arrives while it
 * is opening is not attached until the next reconnect (core, not changed here). Collecting UNDISPATCHED runs the
 * registration synchronously, before start() below.
 */
@Singleton
class HubQuoteUpdates @Inject constructor(
    private val realtime: Realtime,
) : QuoteUpdates {

    override fun changes(requestId: String): Flow<Unit> = channelFlow {
        val hub = realtime.orders
        for (event in listOf(QuoteUpdates.EVENT_NEW_QUOTE, QuoteUpdates.EVENT_QUOTE_WITHDRAWN)) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                hub.listen(event, JsonObject.serializer())
                    .filter { QuoteUpdates.isFor(it, requestId) }
                    .collect { send(Unit) }
            }
        }
        launch(start = CoroutineStart.UNDISPATCHED) { hub.reconnected.collect { send(Unit) } }
        // reconnected skips the very first connect, but a quote sent between #42 and that connect is not replayed
        // either: reload whenever the hub becomes CONNECTED after this point (repeats with reconnected are coalesced).
        launch(start = CoroutineStart.UNDISPATCHED) {
            hub.state.drop(1).filter { it == HubClient.State.CONNECTED }.collect { send(Unit) }
        }
        hub.start()
    }
}

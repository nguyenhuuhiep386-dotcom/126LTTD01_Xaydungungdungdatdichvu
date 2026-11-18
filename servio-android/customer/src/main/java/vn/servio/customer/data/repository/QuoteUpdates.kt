package vn.servio.customer.data.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        const val EVENT_NEW_QUOTE = OrdersHub.EVENT_NEW_QUOTE
        const val EVENT_QUOTE_WITHDRAWN = OrdersHub.EVENT_QUOTE_WITHDRAWN

        /** Both payloads carry serviceRequestId; ids are GUIDs, compared without case. */
        fun isFor(payload: JsonObject, requestId: String): Boolean {
            val id = (payload["serviceRequestId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            return requestId.isNotEmpty() && id.equals(requestId, ignoreCase = true)
        }
    }
}

/**
 * Uses the app-wide orders hub through [OrdersHub], which registered both events before the hub was first started
 * (see there why). Every change of the hub to CONNECTED counts as a reconnect.
 */
@Singleton
class HubQuoteUpdates @Inject constructor(
    private val hub: OrdersHub,
) : QuoteUpdates {

    override fun changes(requestId: String): Flow<Unit> = channelFlow {
        for (event in listOf(QuoteUpdates.EVENT_NEW_QUOTE, QuoteUpdates.EVENT_QUOTE_WITHDRAWN)) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                hub.events(event, JsonObject.serializer())
                    .filter { QuoteUpdates.isFor(it, requestId) }
                    .collect { send(Unit) }
            }
        }
        // A quote sent between #42 and the connect is not replayed: reload on every (re)connect after this point.
        launch(start = CoroutineStart.UNDISPATCHED) { hub.connects().collect { send(Unit) } }
        hub.start()
    }
}

package vn.servio.customer.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import vn.servio.core.realtime.HubClient
import vn.servio.core.realtime.Realtime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The customer's single entry to /hubs/orders ([Realtime.orders]), shared by [HubQuoteUpdates] (CS-15) and
 * [HubOrderUpdates] (CS-19 … CS-21).
 *
 * Why it exists: HubClient (core, not changed) copies the registered event names onto a new connection before opening
 * it, but only keeps the connection once it is open. A listen() that arrives while the hub is CONNECTING is therefore
 * not attached until the next reconnect, and its events are lost. Before, each screen registered its own events right
 * before its own start(), so a second screen opened while the first one's connect was still running lost its events.
 * Here every event of the hub is registered once, when DI creates this singleton, and the only way to start the hub
 * goes through this class, so all registrations happen before the first start(). HubClient keeps the names for the
 * life of the process (also across logout and reconnects).
 */
@Singleton
class OrdersHub @Inject constructor(
    realtime: Realtime,
    private val json: Json,
) {
    private val hub = realtime.orders
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flows: Map<String, MutableSharedFlow<JsonObject>> = EVENTS.associateWith { MutableSharedFlow(extraBufferCapacity = BUFFER) }

    init {
        for ((name, flow) in flows) {
            // UNDISPATCHED: listen() registers the name synchronously, inside this constructor.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                hub.listen(name, JsonObject.serializer()).collect { flow.emit(it) }
            }
        }
    }

    /** Events [name] (one of [EVENTS]) decoded with [deserializer]; payloads that do not parse are skipped. */
    fun <T> events(name: String, deserializer: DeserializationStrategy<T>): Flow<T> =
        flows.getValue(name).mapNotNull { runCatching { json.decodeFromJsonElement(deserializer, it) }.getOrNull() }

    /**
     * Each time the hub becomes CONNECTED after the caller started collecting, including reconnects: events sent while
     * it was down are not replayed, so the screen reloads from REST. Repeats are coalesced by the screens.
     */
    fun connects(): Flow<Unit> = channelFlow {
        launch(start = CoroutineStart.UNDISPATCHED) { hub.reconnected.collect { send(Unit) } }
        launch(start = CoroutineStart.UNDISPATCHED) {
            hub.state.drop(1).filter { it == HubClient.State.CONNECTED }.collect { send(Unit) }
        }
    }

    /** Idempotent; the connection then stays open for the process (other screens share it), it is never stopped here. */
    fun start() = hub.start()

    companion object {
        const val EVENT_NEW_QUOTE = "NewQuote"
        const val EVENT_QUOTE_WITHDRAWN = "QuoteWithdrawn"
        const val EVENT_ORDER_STATUS_CHANGED = "OrderStatusChanged"
        const val EVENT_PARTNER_LOCATION_UPDATED = "PartnerLocationUpdated"
        const val EVENT_ARRIVAL_CONFIRMATION_REQUESTED = "ArrivalConfirmationRequested"
        const val EVENT_EXTRA_CHARGE_REQUESTED = "ExtraChargeRequested"

        /** Every event of /hubs/orders the customer app uses. A new one must be added here, never listened elsewhere. */
        val EVENTS = listOf(
            EVENT_NEW_QUOTE,
            EVENT_QUOTE_WITHDRAWN,
            EVENT_ORDER_STATUS_CHANGED,
            EVENT_PARTNER_LOCATION_UPDATED,
            EVENT_ARRIVAL_CONFIRMATION_REQUESTED,
            EVENT_EXTRA_CHARGE_REQUESTED,
        )

        private const val BUFFER = 64
    }
}

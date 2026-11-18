package vn.servio.customer.data.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * "An order may have changed" (CS-19, CS-20), from /hubs/orders: OrderStatusChanged gives the [String] id of its order;
 * every (re)connect of the hub gives null, meaning "any order", because events sent while it was down are not
 * replayed. Each screen keeps the ids it shows and reloads through [reloadCoalesced].
 */
interface OrderUpdates {
    fun changes(): Flow<String?>

    companion object {
        const val EVENT_ORDER_STATUS_CHANGED = "OrderStatusChanged"

        /** Live events closer together than this give one reload. */
        const val LIVE_RELOAD_WINDOW_MILLIS = 500L

        /** orderId of an OrderStatusChanged payload; null when it has none (the event is then ignored). */
        fun orderIdOf(payload: JsonObject): String? =
            (payload["orderId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

        /** True when a change of [changed] (null = any order) concerns [orderId]. Ids are GUIDs, compared without case. */
        fun concerns(changed: String?, orderId: String): Boolean = changed == null || changed.equals(orderId, ignoreCase = true)
    }
}

/**
 * Runs [reload] for each burst of these signals: [windowMillis] after the first one, once, then waits for it to end.
 * Signals that come during the window or during [reload] give exactly one more call afterwards; two calls never
 * overlap. Returns only when the signals end or the caller is cancelled.
 */
suspend fun Flow<Unit>.reloadCoalesced(
    windowMillis: Long = OrderUpdates.LIVE_RELOAD_WINDOW_MILLIS,
    reload: suspend () -> Unit,
) = coroutineScope {
    // Conflated: any number of signals while one is waiting leaves exactly one.
    val pending = Channel<Unit>(Channel.CONFLATED)
    val source = launch { collect { pending.trySend(Unit) } }
    source.invokeOnCompletion { pending.close() }
    for (signal in pending) {
        delay(windowMillis)
        pending.tryReceive() // answered by this call
        reload()
    }
}

/**
 * Uses the app-wide orders hub of core, like [HubQuoteUpdates]: the events are registered before start() (a listen()
 * that arrives while the hub is connecting is only attached at the next reconnect, see HubQuoteUpdates), every
 * change of the hub to CONNECTED counts as a reconnect, and the hub is never stopped here (other screens share it).
 */
@Singleton
class HubOrderUpdates @Inject constructor(
    private val realtime: Realtime,
) : OrderUpdates {

    override fun changes(): Flow<String?> = channelFlow {
        val hub = realtime.orders
        launch(start = CoroutineStart.UNDISPATCHED) {
            hub.listen(OrderUpdates.EVENT_ORDER_STATUS_CHANGED, JsonObject.serializer())
                .collect { payload -> OrderUpdates.orderIdOf(payload)?.let { send(it) } }
        }
        launch(start = CoroutineStart.UNDISPATCHED) { hub.reconnected.collect { send(null) } }
        launch(start = CoroutineStart.UNDISPATCHED) {
            hub.state.drop(1).filter { it == HubClient.State.CONNECTED }.collect { send(null) }
        }
        hub.start()
    }
}

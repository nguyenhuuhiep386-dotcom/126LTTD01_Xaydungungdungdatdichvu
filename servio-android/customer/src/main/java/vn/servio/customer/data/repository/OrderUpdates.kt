package vn.servio.customer.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import vn.servio.customer.data.model.ArrivalConfirmation
import vn.servio.customer.data.model.ArrivalConfirmationStatus
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.OrderStatusChange
import vn.servio.customer.data.model.Tracking
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live order data of /hubs/orders for CS-19 … CS-21.
 *
 * - [changes]: OrderStatusChanged and ExtraChargeRequested give the [String] id of their order (after the reload CS-20
 *   shows the new PENDING charge and opens CS-22 from it); every (re)connect of the hub gives null, meaning
 *   "any order", because events sent while it was down are not replayed. Each screen keeps the ids it shows and
 *   reloads through [reloadCoalesced] (CS-20 and CS-21 then also ask #76 again).
 * - [locations]: PartnerLocationUpdated as sent (the same model as #76).
 * - "Thợ báo đã đến" requests (ArrivalConfirmationRequested) are kept for the app's life, one per order, so a customer
 *   who is on another screen when it comes still sees it on opening CS-20: [pendingArrival], [arrivalChanges].
 */
interface OrderUpdates {
    fun changes(): Flow<String?>

    fun locations(): Flow<Tracking>

    /** The kept request of [orderId] that has not expired yet; null when there is none. */
    fun pendingArrival(orderId: String): ArrivalConfirmation?

    /** Emits the current version each time a request is kept or dropped. */
    fun arrivalChanges(): StateFlow<Int>

    /** The customer answered [requestId] (or the server said it no longer can be answered): drop it. */
    fun arrivalAnswered(requestId: String)

    companion object {
        const val EVENT_ORDER_STATUS_CHANGED = OrdersHub.EVENT_ORDER_STATUS_CHANGED

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
 * The "Thợ báo đã đến" requests the customer has not answered, at most one per order (the server allows one PENDING
 * per assignment; a newer one replaces the older). A request is dropped when it is answered, once past its expiresAt
 * (checked on every read, against [now]), or when OrderStatusChanged shows its assignment is no longer ON_THE_WAY
 * (arrived by GPS, cancelled, …). Ids are GUIDs, compared without case.
 */
class ArrivalRequests(private val now: () -> Instant = Instant::now) {
    private val byOrder = mutableMapOf<String, ArrivalConfirmation>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    @Synchronized
    fun received(request: ArrivalConfirmation) {
        if (request.status != ArrivalConfirmationStatus.PENDING || isExpired(request)) return
        byOrder[request.orderId.lowercase()] = request
        _version.value++
    }

    @Synchronized
    fun statusChanged(change: OrderStatusChange) {
        val kept = byOrder[change.orderId.lowercase()] ?: return
        if (!kept.assignmentId.equals(change.assignmentId, ignoreCase = true)) return
        if (change.assignmentStatus == OrderStatus.ON_THE_WAY) return
        byOrder.remove(change.orderId.lowercase())
        _version.value++
    }

    @Synchronized
    fun answered(requestId: String) {
        val key = byOrder.entries.firstOrNull { it.value.requestId.equals(requestId, ignoreCase = true) }?.key ?: return
        byOrder.remove(key)
        _version.value++
    }

    @Synchronized
    fun current(orderId: String): ArrivalConfirmation? {
        val key = orderId.lowercase()
        val kept = byOrder[key] ?: return null
        if (!isExpired(kept)) return kept
        byOrder.remove(key)
        _version.value++
        return null
    }

    /** An unreadable expiresAt counts as expired: the dialog could not count down to it. */
    private fun isExpired(request: ArrivalConfirmation): Boolean {
        val expiresAt = runCatching { OffsetDateTime.parse(request.expiresAt).toInstant() }.getOrNull() ?: return true
        return !expiresAt.isAfter(now())
    }
}

/**
 * Uses the app-wide orders hub through [OrdersHub] (every event registered before the hub's first start, see there).
 * Arrival requests and the status changes that drop them are collected for the life of the process.
 */
@Singleton
class HubOrderUpdates @Inject constructor(
    private val hub: OrdersHub,
) : OrderUpdates {

    private val arrivals = ArrivalRequests()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.events(OrdersHub.EVENT_ARRIVAL_CONFIRMATION_REQUESTED, ArrivalConfirmation.serializer()).collect(arrivals::received)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.events(OrdersHub.EVENT_ORDER_STATUS_CHANGED, OrderStatusChange.serializer()).collect(arrivals::statusChanged)
        }
    }

    override fun changes(): Flow<String?> = channelFlow {
        launch(start = CoroutineStart.UNDISPATCHED) {
            hub.events(OrdersHub.EVENT_ORDER_STATUS_CHANGED, JsonObject.serializer())
                .collect { payload -> OrderUpdates.orderIdOf(payload)?.let { send(it) } }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            hub.events(OrdersHub.EVENT_EXTRA_CHARGE_REQUESTED, JsonObject.serializer())
                .collect { payload -> OrderUpdates.orderIdOf(payload)?.let { send(it) } }
        }
        launch(start = CoroutineStart.UNDISPATCHED) { hub.connects().collect { send(null) } }
        hub.start()
    }

    override fun locations(): Flow<Tracking> = channelFlow {
        launch(start = CoroutineStart.UNDISPATCHED) {
            hub.events(OrdersHub.EVENT_PARTNER_LOCATION_UPDATED, Tracking.serializer()).collect { send(it) }
        }
        hub.start()
    }

    override fun pendingArrival(orderId: String): ArrivalConfirmation? = arrivals.current(orderId)

    override fun arrivalChanges(): StateFlow<Int> = arrivals.version

    override fun arrivalAnswered(requestId: String) = arrivals.answered(requestId)
}

package vn.servio.core.realtime

import com.microsoft.signalr.Action1
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import vn.servio.core.data.local.TokenStore
import java.util.concurrent.TimeUnit

/**
 * One SignalR hub (`/hubs/feed`, `/hubs/orders` or `/hubs/chat`, see huong-dan-phat-trien.md "Realtime").
 *
 * - Screens call [start] when they need live data and collect [listen]; the connection stays open for the
 *   app process and reconnects with backoff (the Java client has no automatic reconnect).
 * - Events sent while the connection was down are not replayed: collect [reconnected] and reload the list from REST.
 * - Payloads are parsed with kotlinx.serialization, so the same models serve REST and realtime.
 *
 * Get instances from [Realtime]; do not create them directly.
 */
class HubClient internal constructor(
    private val url: String,
    private val tokenStore: TokenStore,
    private val json: Json,
    /** Called after a failed connect: a REST call through OkHttp refreshes an expired access token. */
    private val refreshSession: suspend () -> Unit,
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private data class Event(val name: String, val payload: JsonElement)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventNames = mutableSetOf<String>()
    private val registered = mutableSetOf<String>()
    private var connection: HubConnection? = null
    private var loop: Job? = null

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 64)

    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits each time the connection opens again after having been down (not on the very first connect). */
    val reconnected: SharedFlow<Unit> = _reconnected.asSharedFlow()

    /** Starts connecting if not already running. Safe to call from every screen that needs the hub. */
    fun start() {
        synchronized(this) {
            if (loop?.isActive == true) return
            loop = scope.launch { runLoop() }
        }
    }

    /** Closes the connection (logout). [start] opens it again. */
    fun stop() {
        synchronized(this) {
            loop?.cancel()
            loop = null
            connection?.let { runCatching { it.stop() } }
            connection = null
            registered.clear()
        }
        _state.value = State.DISCONNECTED
    }

    /** Server events named [name], decoded with [deserializer]. Payloads that do not parse are skipped. */
    fun <T> listen(name: String, deserializer: DeserializationStrategy<T>): Flow<T> =
        events
            .onSubscription { register(name) }
            .filter { it.name == name }
            .mapNotNull { runCatching { json.decodeFromJsonElement(deserializer, it.payload) }.getOrNull() }

    /**
     * Calls a hub method that returns a value (e.g. `UpdateLocation` → Boolean).
     * Null when not connected or the call failed; arguments are plain values (String, Number, Boolean).
     */
    suspend fun <T : Any> invoke(returnType: Class<T>, method: String, vararg args: Any?): T? = withContext(Dispatchers.IO) {
        val current = connection ?: return@withContext null
        runCatching { current.invoke(returnType, method, *args).blockingGet() }.getOrNull()
    }

    /** Calls a hub method that returns nothing (e.g. `MarkRead`). False when not connected or the call failed. */
    suspend fun call(method: String, vararg args: Any?): Boolean = withContext(Dispatchers.IO) {
        val current = connection ?: return@withContext false
        runCatching { current.invoke(method, *args).blockingAwait(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    private fun register(name: String) {
        synchronized(this) {
            eventNames.add(name)
            connection?.let { attach(it, name) }
        }
    }

    /** Registers [name] on [target] once (the Java client would otherwise call the handler twice). */
    private fun attach(target: HubConnection, name: String) {
        if (!registered.add(name)) return
        target.on(
            name,
            Action1<com.google.gson.JsonElement> { value ->
                val payload = runCatching { json.parseToJsonElement(value.toString()) }.getOrNull() ?: return@Action1
                events.tryEmit(Event(name, payload))
            },
            com.google.gson.JsonElement::class.java,
        )
    }

    private suspend fun runLoop() {
        var backoffMillis = MIN_BACKOFF_MILLIS
        var wasConnected = false
        while (currentCoroutineContext().isActive) {
            if (!tokenStore.hasSession) break
            _state.value = State.CONNECTING
            val hub = HubConnectionBuilder.create(url)
                .withAccessTokenProvider(Single.defer { Single.just(tokenStore.accessToken.orEmpty()) })
                .build()
            val closed = CompletableDeferred<Unit>()
            hub.onClosed { closed.complete(Unit) }
            synchronized(this) {
                registered.clear()
                eventNames.forEach { attach(hub, it) }
            }
            val opened = runCatching { hub.start().blockingAwait(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
            if (opened) {
                synchronized(this) { connection = hub }
                _state.value = State.CONNECTED
                if (wasConnected) _reconnected.tryEmit(Unit)
                wasConnected = true
                backoffMillis = MIN_BACKOFF_MILLIS
                closed.await()
                synchronized(this) { if (connection === hub) connection = null }
            } else {
                runCatching { hub.stop() }
                refreshSession()
            }
            _state.value = State.CONNECTING
            delay(backoffMillis)
            backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
        _state.value = State.DISCONNECTED
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val CALL_TIMEOUT_SECONDS = 15L
        const val MIN_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
    }
}

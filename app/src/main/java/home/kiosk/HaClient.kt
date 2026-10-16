package home.kiosk

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class Entity(val id: String, val state: String, val attributes: JSONObject, val lastChanged: String = "") {
    val domain get() = id.substringBefore('.')
    val name get() = attributes.text("friendly_name", id)
}

enum class Status { NOT_CONFIGURED, CONNECTING, CONNECTED, AUTH_FAILED }

/** Home Assistant WebSocket API client: auth, live entity states, service calls, request/response, lovelace_updated. */
class HaClient(private val baseUrl: String, private val token: String, private val scope: CoroutineScope) {
    private val wsUrl = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/api/websocket"
    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var getStatesId = -1
    @Volatile private var stopped = false
    private var backoffMs = 1_000L

    private val _states = MutableStateFlow<Map<String, Entity>>(emptyMap())
    val states: StateFlow<Map<String, Entity>> = _states
    private val _status = MutableStateFlow(Status.CONNECTING)
    val status: StateFlow<Status> = _status
    private val _dashboardSaved = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** url_path of a dashboard someone just saved (in HA's editor or from this tablet). */
    val dashboardSaved: SharedFlow<String> = _dashboardSaved

    fun start() {
        if (stopped) return
        val request = runCatching { Request.Builder().url(wsUrl).build() }.getOrNull()
        if (baseUrl.isBlank() || token.isBlank() || request == null) {
            _status.value = Status.NOT_CONFIGURED // blank or malformed URL: Settings asks for it
            return
        }
        ws = http.newWebSocket(request, listener)
    }

    /** For good: used when the activity goes away or the URL/token changes. */
    fun stop() {
        stopped = true
        ws?.close(1000, null)
    }

    fun callService(domain: String, service: String, entityIds: List<String>, data: JSONObject? = null) {
        send("call_service") {
            put("domain", domain)
            put("service", service)
            val d = data ?: JSONObject()
            if (entityIds.isNotEmpty()) d.put("entity_id", JSONArray(entityIds))
            put("service_data", d)
        }
    }

    /** One command, awaited. Returns the `result` field; throws on error, timeout or disconnect. */
    suspend fun request(type: String, fill: JSONObject.() -> Unit = {}): Any? {
        val socket = ws ?: throw IOException("not connected")
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            socket.send(JSONObject().put("id", id).put("type", type).apply(fill).toString())
            val r = withTimeout(15_000) { reply.await() }
            if (!r.optBoolean("success")) throw IOException(r.optJSONObject("error")?.optString("message") ?: "$type failed")
            return r.opt("result").takeUnless { it == JSONObject.NULL }
        } finally {
            pending.remove(id)
        }
    }

    private fun send(type: String, fill: JSONObject.() -> Unit = {}): Int {
        val id = nextId.getAndIncrement()
        ws?.send(JSONObject().put("id", id).put("type", type).apply(fill).toString())
        return id
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            if (msg.optString("type") == "result" && !msg.optBoolean("success")) Log.w(TAG, "request failed: $text")
            when (msg.getString("type")) {
                // auth is the one message without an id
                "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                "auth_ok" -> {
                    backoffMs = 1_000L
                    // ponytail: subscribe-then-snapshot leaves a tiny window where an event older than the
                    // snapshot is overwritten; switch to subscribe_entities if that ever shows up.
                    send("subscribe_events") { put("event_type", "state_changed") }
                    send("subscribe_events") { put("event_type", "lovelace_updated") }
                    getStatesId = send("get_states")
                    _status.value = Status.CONNECTED
                }
                "auth_invalid" -> {
                    Log.e(TAG, "auth_invalid: ${msg.optString("message")}")
                    _status.value = Status.AUTH_FAILED
                    webSocket.close(1000, null) // bad token won't fix itself; no reconnect
                }
                else -> {
                    if (msg.optString("type") == "result") pending[msg.optInt("id")]?.complete(msg)
                    val event = msg.optJSONObject("event")
                    if (event?.optString("event_type") == "lovelace_updated") {
                        _dashboardSaved.tryEmit(event.optJSONObject("data")?.optString("url_path").orEmpty())
                    } else {
                        _states.update { reduce(it, msg, getStatesId) }
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failed: $t")
            reconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (_status.value != Status.AUTH_FAILED) reconnect()
        }
    }

    private fun reconnect() {
        if (stopped) return
        _status.value = Status.CONNECTING
        pending.values.forEach { it.completeExceptionally(IOException("disconnected")) }
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        scope.launch { delay(wait); start() }
    }

    companion object {
        private const val TAG = "HaClient"

        /** Pure state update for one non-auth message; the unit-tested part. */
        fun reduce(states: Map<String, Entity>, msg: JSONObject, getStatesId: Int): Map<String, Entity> {
            return when (msg.optString("type")) {
                "result" -> {
                    if (msg.optInt("id") != getStatesId || !msg.optBoolean("success")) return states
                    val arr = msg.getJSONArray("result")
                    (0 until arr.length()).associate { i -> arr.getJSONObject(i).toEntity().let { it.id to it } }
                }
                "event" -> {
                    val event = msg.getJSONObject("event")
                    if (event.optString("event_type") != "state_changed") return states
                    val data = event.getJSONObject("data")
                    val id = data.getString("entity_id")
                    val newState = data.optJSONObject("new_state")
                    if (newState == null) states - id else states + (id to newState.toEntity())
                }
                else -> states
            }
        }

        private fun JSONObject.toEntity() =
            Entity(getString("entity_id"), getString("state"), optJSONObject("attributes") ?: JSONObject(), optString("last_changed"))
    }
}

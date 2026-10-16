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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
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

/** Who the token or account belongs to. */
data class HaUser(val id: String, val name: String, val isAdmin: Boolean)

/**
 * Home Assistant WebSocket API client: auth, live entity states, service calls, request/response, lovelace_updated.
 * Authenticates with a long-lived [token], or, when signed in with an account, a [refreshToken] that's traded for
 * 30-minute access tokens as they run out.
 */
class HaClient(private val baseUrl: String, @Volatile private var token: String, private val scope: CoroutineScope, private val refreshToken: String = "") {
    private val wsUrl = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/api/websocket"
    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var getStatesId = -1
    @Volatile private var stopped = false
    private var backoffMs = 1_000L
    @Volatile private var tokenExpiresAt = 0L
    @Volatile private var authRetried = false

    private val _states = MutableStateFlow<Map<String, Entity>>(emptyMap())
    val states: StateFlow<Map<String, Entity>> = _states
    private val _user = MutableStateFlow<HaUser?>(null)
    /** The signed-in user, known shortly after connecting; null until then. */
    val user: StateFlow<HaUser?> = _user
    private val _status = MutableStateFlow(Status.CONNECTING)
    val status: StateFlow<Status> = _status
    private val _dashboardSaved = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** url_path of a dashboard someone just saved (in HA's editor or from this tablet). */
    val dashboardSaved: SharedFlow<String> = _dashboardSaved

    fun start() {
        if (stopped) return
        val request = runCatching { Request.Builder().url(wsUrl).build() }.getOrNull()
        if (baseUrl.isBlank() || (token.isBlank() && refreshToken.isBlank()) || request == null) {
            _status.value = Status.NOT_CONFIGURED // blank or malformed URL: Settings asks for it
            return
        }
        if (refreshToken.isEmpty()) { ws = http.newWebSocket(request, listener); return }
        scope.launch {
            try {
                ensureToken()
                ws = http.newWebSocket(request, listener)
            } catch (e: HaAuth.Revoked) {
                _status.value = Status.AUTH_FAILED
            } catch (e: Exception) {
                Log.w(TAG, "token refresh failed: $e")
                reconnect()
            }
        }
    }

    /** Signed in with an account: renew the access token a minute before it runs out. No-op for long-lived tokens. */
    private suspend fun ensureToken() {
        if (refreshToken.isEmpty() || System.currentTimeMillis() < tokenExpiresAt - 60_000) return
        val t = HaAuth.refresh(baseUrl, refreshToken)
        token = t.access
        tokenExpiresAt = System.currentTimeMillis() + t.expiresInSec * 1000
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

    /** A live HA subscription (render_template, weather/subscribe_forecast, todo/item/subscribe…). */
    inner class Subscription internal constructor(
        val type: String, val fill: JSONObject.() -> Unit, val onEvent: (JSONObject) -> Unit,
    ) {
        @Volatile internal var id = -1

        fun cancel() {
            subscriptions.remove(this)
            val old = id
            if (old > 0 && _status.value == Status.CONNECTED) send("unsubscribe_events") { put("subscription", old) }
        }
    }

    private val subscriptions = java.util.concurrent.CopyOnWriteArrayList<Subscription>()

    /** Subscribes now (if connected) and again after every reconnect; [onEvent] gets each message's `event`. */
    fun subscribe(type: String, fill: JSONObject.() -> Unit = {}, onEvent: (JSONObject) -> Unit): Subscription =
        Subscription(type, fill, onEvent).also {
            subscriptions += it
            if (_status.value == Status.CONNECTED) it.id = send(type, fill)
        }

    /** Authenticated REST GET against HA (calendars and other endpoints the WebSocket API lacks). */
    suspend fun getJson(path: String): Any = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        ensureToken()
        val req = Request.Builder().url(baseUrl.trimEnd('/') + path).header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GET $path: HTTP ${r.code}")
            val body = r.body!!.string()
            if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        }
    }

    /** Authenticated REST POST (mobile_app registration and webhooks). Returns the response body. */
    suspend fun postJson(path: String, body: JSONObject): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        ensureToken()
        val req = Request.Builder().url(baseUrl.trimEnd('/') + path).header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "POST $path: HTTP ${r.code}")
            r.body!!.string()
        }
    }

    class HttpException(val code: Int, message: String) : IOException(message)

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
                    authRetried = false
                    // ponytail: subscribe-then-snapshot leaves a tiny window where an event older than the
                    // snapshot is overwritten; switch to subscribe_entities if that ever shows up.
                    send("subscribe_events") { put("event_type", "state_changed") }
                    send("subscribe_events") { put("event_type", "lovelace_updated") }
                    getStatesId = send("get_states")
                    subscriptions.forEach { it.id = send(it.type, it.fill) }
                    _status.value = Status.CONNECTED
                    scope.launch {
                        runCatching { request("auth/current_user") as JSONObject }
                            .onSuccess { _user.value = HaUser(it.optString("id"), it.optString("name"), it.optBoolean("is_admin")) }
                    }
                }
                "auth_invalid" -> {
                    Log.e(TAG, "auth_invalid: ${msg.optString("message")}")
                    if (refreshToken.isNotEmpty() && !authRetried) {
                        authRetried = true
                        tokenExpiresAt = 0 // access token went stale (e.g. tablet clock jumped): renew once and retry
                        webSocket.close(1000, null)
                        return
                    }
                    _status.value = Status.AUTH_FAILED
                    webSocket.close(1000, null) // bad token won't fix itself; no reconnect
                }
                else -> {
                    if (msg.optString("type") == "result") pending[msg.optInt("id")]?.complete(msg)
                    if (msg.optString("type") == "event") {
                        val id = msg.optInt("id")
                        subscriptions.firstOrNull { it.id == id }?.let { s -> msg.optJSONObject("event")?.let(s.onEvent); return }
                    }
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

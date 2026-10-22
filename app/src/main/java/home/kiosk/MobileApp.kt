package home.kiosk

import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One sensor this tablet reports to HA. A Boolean [state] makes it a binary_sensor. */
data class Sensor(val id: String, val name: String, val state: Any, val deviceClass: String? = null, val unit: String? = null) {
    val type get() = if (state is Boolean) "binary_sensor" else "sensor"

    fun update(): JSONObject = JSONObject().put("unique_id", id).put("type", type).put("state", state)

    fun registration(): JSONObject = update().put("name", name).apply {
        deviceClass?.let { put("device_class", it) }
        unit?.let { put("unit_of_measurement", it).put("state_class", "measurement") }
    }
}

/** What Android's sticky ACTION_BATTERY_CHANGED broadcast says. */
data class Battery(val percent: Int, val pluggedIn: Boolean, val tempC: Int) {
    fun sensors() = listOf(
        Sensor("battery_level", "Battery level", percent, "battery", "%"),
        Sensor("charger", "Charger", pluggedIn, "plug"),
        Sensor("battery_temperature", "Battery temperature", tempC, "temperature", "°C"),
    )

    companion object {
        fun from(i: Intent) = of(
            i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), i.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
            i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0), i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
        )

        /** [tenthsC] is how Android reports temperature: 312 = 31.2 °C. */
        fun of(level: Int, scale: Int, plugged: Int, tenthsC: Int) =
            Battery(if (scale > 0) level * 100 / scale else level, plugged != 0, Math.round(tenthsC / 10f))
    }
}

/**
 * Reports the tablet's sensors through HA's mobile_app integration, the way the Companion app does. The tablet
 * shows up as a device, any account can do it (no admin needed), and the entities survive HA restarts.
 */
class MobileApp(private val ha: HaClient, private val settings: Settings) {
    private val lock = Mutex()
    private val registered = mutableSetOf<String>()

    suspend fun report(sensors: List<Sensor>) = lock.withLock { send(sensors, retried = false) }

    private suspend fun send(sensors: List<Sensor>, retried: Boolean) {
        if (settings.mobileWebhook.isEmpty()) registerDevice()
        sensors.filter { it.id !in registered }.forEach { webhook("register_sensor", it.registration()); registered += it.id }
        val reply = webhook("update_sensor_states", JSONArray(sensors.map { it.update() }))
        if (!retried && needsRegistering(reply)) {
            if (reply.isBlank()) settings.mobileWebhook = "" // device deleted in HA, or a different server: start over
            registered.clear()
            send(sensors, retried = true)
        }
    }

    /**
     * Lets HA's notify.mobile_app_<tablet> action reach this tablet over the WebSocket (the intercom commands).
     * Registers first if needed; older registrations get push support added. Call [listen] after this.
     */
    suspend fun enablePush() = lock.withLock {
        if (settings.mobileWebhook.isEmpty()) registerDevice()
        else webhook("update_registration", device())
    }

    /** HA's notify messages for this tablet: `message` and its `data`. Re-subscribes after reconnects. */
    fun listen(onPush: (String, JSONObject) -> Unit) =
        ha.subscribe("mobile_app/push_notification_channel", { put("webhook_id", settings.mobileWebhook) }) { e ->
            onPush(e.optString("message"), e.optJSONObject("data") ?: JSONObject())
        }

    private fun device() = JSONObject().put("app_version", BuildConfig.VERSION_NAME)
        .put("device_name", Build.MODEL).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("os_version", Build.VERSION.RELEASE)
        .put("app_data", JSONObject().put("push_websocket_channel", true))

    private suspend fun registerDevice() {
        val body = device().put("device_id", UUID.randomUUID().toString()).put("app_id", BuildConfig.APPLICATION_ID)
            .put("app_name", "LightHA").put("os_name", "Android").put("supports_encryption", false)
        settings.mobileWebhook = JSONObject(ha.postJson("/api/mobile_app/registrations", body)).getString("webhook_id")
        registered.clear()
    }

    /** HA answers an unknown webhook with an empty 200, and a deleted device's webhook with 410. Both come back "". */
    private suspend fun webhook(type: String, data: Any): String = try {
        ha.postJson("/api/webhook/${settings.mobileWebhook}", JSONObject().put("type", type).put("data", data))
    } catch (e: HaClient.HttpException) {
        if (e.code == 410) "" else throw e
    }

    companion object {
        /** True when HA no longer knows the webhook (blank reply) or one of the sensors (not_registered). */
        fun needsRegistering(reply: String): Boolean {
            if (reply.isBlank()) return true
            val o = runCatching { JSONObject(reply) }.getOrNull() ?: return false
            return o.keys().asSequence().any { o.optJSONObject(it)?.optJSONObject("error")?.optString("code") == "not_registered" }
        }
    }
}

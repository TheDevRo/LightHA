package home.kiosk

import android.content.SharedPreferences
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** On-tablet settings: Compose state that writes through to SharedPreferences. */
class Settings(private val prefs: SharedPreferences) {
    var haUrl by string("ha_url", BuildConfig.HA_URL)
    var haToken by string("ha_token", BuildConfig.HA_TOKEN)
    /** Set when signed in with an HA account instead of a long-lived token; traded for access tokens as needed. */
    var haRefresh by string("ha_refresh", "")
    /** Who's signed in, for display. */
    var haUser by string("ha_user", "")
    var keepScreenOn by bool("keep_screen_on", true)
    var fullscreen by bool("fullscreen", true)
    /** Window brightness 0.05..1, or -1 to follow the system. */
    var brightness by float("brightness", -1f)
    /** Minutes without a touch before jumping back to the first view; 0 = never. */
    var returnHomeMinutes by int("return_home_minutes", 0)
    var allowTabletEditing by bool("allow_tablet_editing", true)
    /** Minutes without a touch before the screensaver; 0 = never. */
    var screensaverMinutes by int("screensaver_minutes", 0)
    var screensaverBrightness by float("screensaver_brightness", 0.02f)
    /** An HA entity the screen follows: wakes when it turns on, sleeps when it turns off. */
    var wakeEntity by string("wake_entity", "")
    /** Wake on motion seen by the tablet's own camera. */
    var cameraMotion by bool("camera_motion", false)
    /** Percent of the picture that must change to count as motion: 15 low, 6 medium, 2 high sensitivity. */
    var motionSensitivity by int("motion_sensitivity", 6)
    /** Report battery and camera motion to HA as a mobile_app device. */
    var reportSensors by bool("report_sensors", false)
    /** The mobile_app webhook HA handed out when this tablet registered; empty = not registered yet. */
    var mobileWebhook by string("mobile_webhook", "")
    /** Mic button in the header for push-to-talk Assist. */
    var voiceButton by bool("voice_button", false)
    /** On-tablet wake word id from [WAKE_WORDS]; empty = off. */
    var wakeWord by string("wake_word", "")
    /** 1 low, 0 medium, -1 high; see [wakeCutoff]. */
    var wakeSensitivity by int("wake_sensitivity", 0)
    /** Assist pipeline id; empty = HA's preferred pipeline. */
    var voicePipeline by string("voice_pipeline", "")
    /** Empty = no PIN. */
    var pin by string("pin", "")
    private var hidden by string("hidden_views", "")
    var lastView by string("last_view", "")

    /** Views are shown by default; this records the ones switched off, per dashboard. */
    fun isViewHidden(dashboard: String, viewKey: String) = "$dashboard/$viewKey" in hidden.split('\n')
    fun setViewHidden(dashboard: String, viewKey: String, hide: Boolean) {
        val set = hidden.split('\n').filter { it.isNotEmpty() }.toMutableSet()
        if (hide) set += "$dashboard/$viewKey" else set -= "$dashboard/$viewKey"
        hidden = set.joinToString("\n")
    }

    private inner class Pref<T>(private val key: String, def: T, read: SharedPreferences.(String, T) -> T, private val write: SharedPreferences.Editor.(String, T) -> Unit) :
        ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(prefs.read(key, def))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            prefs.edit().apply { write(key, value) }.apply()
        }
    }

    private fun string(key: String, def: String) = Pref(key, def, { k, d -> getString(k, d)!! }, { k, v -> putString(k, v) })
    private fun bool(key: String, def: Boolean) = Pref(key, def, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })
    private fun int(key: String, def: Int) = Pref(key, def, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })
    private fun float(key: String, def: Float) = Pref(key, def, { k, d -> getFloat(k, d) }, { k, v -> putFloat(k, v) })
}

val LocalSettings = compositionLocalOf<Settings> { error("Settings not provided") }

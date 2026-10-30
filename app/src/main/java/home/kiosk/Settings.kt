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
    /** What the screensaver shows: 0 clock, 1 clock and weather, 2 a web page ([screensaverUrl]). */
    var screensaverStyle by int("screensaver_style", 0)
    /** Weather entity for the weather screensaver; empty = the first one HA has. */
    var screensaverWeather by string("screensaver_weather", "")
    /** Page for the web screensaver (ImmichFrame, a photo frame, anything). A path starting with / is on the HA server. */
    var screensaverUrl by string("screensaver_url", "")
    /** An HA entity the screen follows: wakes when it turns on, sleeps when it turns off. */
    var wakeEntity by string("wake_entity", "")
    /** How the screen sleeps for [wakeEntity] and outside active hours: 0 Android's screensaver, 1 off (needs [ScreenOff]). */
    var sleepAction by int("sleep_action", 0)
    /** Screen on only from [activeFrom] until [activeTo], minutes after midnight. See [offHours]. */
    var activeHours by bool("active_hours", false)
    var activeFrom by int("active_from", 7 * 60)
    var activeTo by int("active_to", 22 * 60)
    /** Wake on motion seen by the tablet's own camera. */
    var cameraMotion by bool("camera_motion", false)
    /** Percent of the picture that must change to count as motion: 15 low, 6 medium, 2 high sensitivity. */
    var motionSensitivity by int("motion_sensitivity", 6)
    /** Camera that watches for motion, as Camera.CameraInfo facing: 1 front, 0 back. Falls back to the first camera. */
    var motionCamera by int("motion_camera", 1)
    /** Report battery and camera motion to HA as a mobile_app device. */
    var reportSensors by bool("report_sensors", false)
    /** The mobile_app webhook HA handed out when this tablet registered; empty = not registered yet. */
    var mobileWebhook by string("mobile_webhook", "")
    /** HA's device registry id for this tablet, so Assist timers know where to ring; empty = not looked up yet. */
    var mobileDeviceId by string("mobile_device_id", "")
    /** Count down this tablet's running Assist timers in the header. */
    var showTimers by bool("show_timers", true)
    /** Mic button in the header for push-to-talk Assist. */
    var voiceButton by bool("voice_button", false)
    /** On-tablet wake word: an id from [WAKE_WORDS], or [WakeCatalog.PREFIX] + a downloaded slug; empty = off. */
    var wakeWord by string("wake_word", "")
    /** Display name of a downloaded wake word (the bundled ones are named in [WAKE_WORDS]). */
    var wakeWordLabel by string("wake_word_label", "")
    /**
     * Set when Android's audio service died while recording (LineageOS 14.1 on the Fire HD6 crashes it on any
     * capture). Keeps the mic closed across restarts until a wake word or the Assist button is chosen again.
     */
    var micBroken by bool("mic_broken", false)
    /** Decode spoken replies as they stream in; off = MediaPlayer, which waits seconds before it starts. */
    var streamReplies by bool("stream_replies", true)
    /** 1 low, 0 medium, -1 high; see [wakeCutoff]. */
    var wakeSensitivity by int("wake_sensitivity", 0)
    /** Assist pipeline id; empty = HA's preferred pipeline. */
    var voicePipeline by string("voice_pipeline", "")
    /** Intercom: call other tablets from the header button. Only listens for their beacons. */
    var intercomCall by bool("intercom_call", false)
    /** Intercom: take calls. Opens the call port and announces this tablet on the LAN. Separate from [intercomCall]. */
    var intercomReceive by bool("intercom_receive", false)
    /** Hear Announce to all from other tablets (needs [intercomReceive]). */
    var intercomAnnouncements by bool("intercom_announcements", true)
    /** Answer calls after a chime and a 3 second countdown. The mic still only sends while Talk is held. */
    var intercomAutoAnswer by bool("intercom_auto_answer", false)
    /** Do not disturb: 0 off, 1 on, 2 scheduled from [intercomDndFrom] to [intercomDndTo] o'clock. See [dndAt]. */
    var intercomDnd by int("intercom_dnd", 0)
    var intercomDndFrom by int("intercom_dnd_from", 22)
    var intercomDndTo by int("intercom_dnd_to", 7)
    /** This tablet's intercom id, made on first use. Paired tablets know it by this. */
    var intercomId by string("intercom_id", "")
    /** Paired tablets as JSON: `{id: {name, key}}`, key in hex. Only these can call or be called. */
    var intercomPairs by string("intercom_pairs", "{}")
    /** What other tablets see in their call list. */
    var intercomName by string("intercom_name", android.os.Build.MODEL)
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

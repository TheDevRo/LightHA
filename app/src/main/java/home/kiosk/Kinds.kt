package home.kiosk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How an HA entity is drawn and what tapping it does. The kiosk's version of HomeHabit's item types. */
enum class Kind { LIGHT, TOGGLE, THERMOSTAT, NUMBER, COVER, LOCK, MEDIA, READOUT, ACTION }

fun kindOf(e: Entity): Kind = when (e.domain) {
    "light" -> Kind.LIGHT
    "switch", "input_boolean", "fan", "automation" -> Kind.TOGGLE
    "climate" -> Kind.THERMOSTAT
    "input_number", "number" -> Kind.NUMBER
    "cover" -> Kind.COVER
    "lock" -> Kind.LOCK
    "media_player" -> Kind.MEDIA
    "scene", "script", "button", "input_button" -> Kind.ACTION
    else -> Kind.READOUT // sensor, binary_sensor, anything unknown: at least show its state
}

/** Buttons and scenes sit at "unknown" until first pressed; that's normal, not unavailable. */
val Entity.unavailable get() = state == "unavailable" || (state == "unknown" && kindOf(this) != Kind.ACTION)

/** Service a tap calls on the entity's own domain, or null for read-only tiles. */
fun tapService(e: Entity): String? = if (e.unavailable) null else when (kindOf(e)) {
    Kind.LIGHT, Kind.TOGGLE, Kind.COVER -> "toggle"
    Kind.LOCK -> if (e.state == "locked") "unlock" else "lock"
    Kind.MEDIA -> "media_play_pause"
    Kind.ACTION -> if (e.domain.endsWith("button")) "press" else "turn_on"
    Kind.THERMOSTAT, Kind.NUMBER, Kind.READOUT -> null
}

/** Highlighted tile. An unlocked lock counts: that's the state worth noticing. */
fun isActive(e: Entity): Boolean = when (kindOf(e)) {
    Kind.THERMOSTAT -> e.attributes.optString("hvac_action") in setOf("heating", "cooling")
    Kind.READOUT -> e.domain == "binary_sensor" && e.state == "on"
    else -> e.state in setOf("on", "open", "opening", "playing", "unlocked")
}

fun stateText(e: Entity): String {
    if (e.unavailable) return "Unavailable"
    val a = e.attributes
    return when (kindOf(e)) {
        Kind.LIGHT -> if (e.state == "on" && !a.isNull("brightness") && a.has("brightness")) {
            "${(a.getInt("brightness") * 100 + 127) / 255}%"
        } else cap(e.state)
        Kind.THERMOSTAT -> {
            val cur = a.optNum("current_temperature")
            val target = a.optNum("temperature")
            when {
                e.state == "off" -> "Off" + (cur?.let { " · ${deg(it)}" } ?: "")
                cur != null && target != null -> "${deg(cur)} → ${deg(target)}"
                else -> cap(e.state)
            }
        }
        Kind.COVER -> a.optNum("current_position")?.let { "${it.toInt()}% open" } ?: cap(e.state)
        Kind.MEDIA -> listOf(a.text("media_title"), a.text("media_artist"))
            .filter { it.isNotEmpty() }.joinToString(" — ").ifEmpty { cap(e.state) }
        Kind.READOUT ->
            if (e.domain == "binary_sensor") binaryText(a.text("device_class"), e.state == "on")
            else if (a.text("device_class") == "timestamp") timeText(e.state)
            else if (e.domain == "weather") weatherText(e.state)
            else e.state.toDoubleOrNull()?.let { num(it) + unit(a.text("unit_of_measurement")) } ?: cap(e.state)
        Kind.NUMBER -> e.state.toDoubleOrNull()?.let { num(it) + unit(a.text("unit_of_measurement")) } ?: cap(e.state)
        Kind.ACTION -> ""
        else -> cap(e.state)
    }
}

/** Next input_number/number value for a +/- tap, clamped to its min/max. */
fun nudgedValue(e: Entity, up: Boolean): Double? {
    val v = e.state.toDoubleOrNull() ?: return null
    val step = e.attributes.optNum("step") ?: 1.0
    val next = if (up) v + step else v - step
    return next.coerceIn(e.attributes.optNum("min") ?: next, e.attributes.optNum("max") ?: next)
}

/** Next thermostat target for a +/- tap, or null when there's no single target (off, heat_cool range). */
fun nudgedTarget(e: Entity, up: Boolean): Double? {
    val target = e.attributes.optNum("temperature") ?: return null
    val step = e.attributes.optNum("target_temp_step") ?: if (target > 40) 1.0 else 0.5 // °F vs °C default
    return if (up) target + step else target - step
}

/** "2026-10-16T13:06:27+00:00" -> local "6:06 AM", with the weekday when it isn't today. */
fun timeText(iso: String, now: Date = Date()): String {
    val date = parseIso(iso) ?: return iso
    val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    val pattern = if (day.format(date) == day.format(now)) "h:mm a" else "EEE h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}

/** HA's ISO timestamps ("2026-10-16T13:06:27.123+00:00") without java.time, which needs API 26. */
fun parseIso(iso: String): Date? {
    val clean = iso.replace(Regex("""\.\d+"""), "").replace(Regex("""([+-]\d\d):(\d\d)$"""), "$1$2").replace(Regex("Z$"), "+0000")
    return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(clean) }.getOrNull()
}

// media_player supported_features bits (homeassistant/components/media_player/const.py)
const val MP_PAUSE = 1
const val MP_SEEK = 2
const val MP_VOLUME_SET = 4
const val MP_PREVIOUS = 16
const val MP_NEXT = 32
const val MP_PLAY = 16384

fun supports(e: Entity, flag: Int) = e.attributes.optLong("supported_features") and flag.toLong() != 0L

/** Seconds into the track right now: HA only reports position at the last update, so add elapsed time while playing. */
fun mediaPosition(e: Entity, now: Date = Date()): Double? {
    val pos = e.attributes.optNum("media_position") ?: return null
    if (e.state != "playing") return pos
    val at = parseIso(e.attributes.text("media_position_updated_at")) ?: return pos
    val dur = e.attributes.optNum("media_duration") ?: Double.MAX_VALUE
    return (pos + (now.time - at.time) / 1000.0).coerceIn(0.0, dur)
}

/** Which of a card's players to show first: one that's playing, then paused, then the first. */
fun activePlayer(ids: List<String>, states: Map<String, Entity>): String? =
    ids.firstOrNull { states[it]?.state == "playing" } ?: ids.firstOrNull { states[it]?.state == "paused" } ?: ids.firstOrNull()

/** 222 -> "3:42". */
fun clockText(seconds: Double): String = "%d:%02d".format(Locale.US, seconds.toInt() / 60, seconds.toInt() % 60)

fun weatherText(state: String) = when (state) {
    "partlycloudy" -> "Partly cloudy"
    "clear-night" -> "Clear"
    "lightning-rainy" -> "Thunderstorms"
    "snowy-rainy" -> "Sleet"
    else -> cap(state.replace('-', ' '))
}

private fun binaryText(deviceClass: String, on: Boolean) = when (deviceClass) {
    "door", "window", "opening", "garage_door" -> if (on) "Open" else "Closed"
    "motion", "occupancy", "presence" -> if (on) "Detected" else "Clear"
    "moisture" -> if (on) "Wet" else "Dry"
    "lock" -> if (on) "Unlocked" else "Locked"
    else -> if (on) "On" else "Off"
}

private fun org.json.JSONObject.optNum(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

fun num(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else String.format(Locale.US, "%.1f", d)
private fun deg(d: Double) = num(d) + "°"
private fun unit(u: String) = when {
    u.isEmpty() -> ""
    u == "%" || u.startsWith("°") -> u
    else -> " $u"
}
private fun cap(s: String) = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

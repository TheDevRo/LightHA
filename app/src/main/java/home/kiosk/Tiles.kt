package home.kiosk

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DoorFront
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

val Bg = Color(0xFF0E0F12)
val TextDim = Color(0xFF8A8F98)
val CardBg = Color(0xFF17191E)
val Chip = Color(0xFF262930)
/** The app's accent: active tiles, selected chips, primary buttons. */
val Amber = Color(0xFFFFB74D)
private val Heat = Color(0xFFFF8A65)
private val Cool = Color(0xFF64B5F6)
private val Alert = Color(0xFFE57373)
private val Calm = Color(0xFF81C784)

/** A service call: (domain, service, entity ids, extra data). */
fun interface Call {
    operator fun invoke(domain: String, service: String, ids: List<String>, data: JSONObject?)
}

/**
 * One entity as a row: icon, name, state, and a kind-specific control.
 * [onTap] overrides the default tap (from the card's tap_action); null keeps the kind's default.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntityRow(
    e: Entity, label: String, call: Call, onLock: (Entity, String) -> Unit,
    onTap: (() -> Unit)? = null, onHold: (() -> Unit)? = null, onDoubleTap: (() -> Unit)? = null,
    /** Mushroom template card overrides: secondary line and icon colour (null colour = inactive grey). */
    secondary: String? = null, templateColor: Color? = null, hasTemplateColor: Boolean = false,
) {
    val kind = kindOf(e)
    val active = if (hasTemplateColor) templateColor != null else isActive(e)
    val accent = templateColor ?: accentOf(e, kind)
    val defaultTap: (() -> Unit)? = tapService(e)?.let { s ->
        { if (kind == Kind.LOCK) onLock(e, s) else call(e.domain, s, listOf(e.id), null) }
    }
    val tap = onTap ?: defaultTap

    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .combinedClickable(onClick = { tap?.invoke() }, onLongClick = onHold, onDoubleClick = onDoubleTap)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (active) accent else Chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconOf(e, kind, active), null, tint = if (active) CardBg else TextDim, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        val readout = kind == Kind.READOUT
        Column(Modifier.weight(1f)) {
            Text(label, color = if (e.unavailable) TextDim else Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!readout) (secondary ?: stateText(e)).takeIf { it.isNotEmpty() }?.let {
                Text(it, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            e.unavailable -> Unit
            readout -> Text(stateText(e), color = if (active) accent else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            kind == Kind.LIGHT || kind == Kind.TOGGLE -> Switch(
                checked = e.state == "on",
                onCheckedChange = { defaultTap?.invoke() },
                colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = CardBg, uncheckedTrackColor = Chip, uncheckedBorderColor = Chip, uncheckedThumbColor = TextDim),
            )
            kind == Kind.THERMOSTAT -> Stepper(nudgedTarget(e, false), nudgedTarget(e, true)) { t ->
                call("climate", "set_temperature", listOf(e.id), JSONObject().put("temperature", t))
            }
            kind == Kind.NUMBER -> Stepper(nudgedValue(e, false), nudgedValue(e, true)) { v ->
                call(e.domain, "set_value", listOf(e.id), JSONObject().put("value", v))
            }
            kind == Kind.MEDIA -> RoundButton(if (e.state == "playing") Icons.Rounded.Pause else Icons.Rounded.PlayArrow) { defaultTap?.invoke() }
            else -> Unit
        }
    }
}

@Composable
private fun Stepper(down: Double?, up: Double?, set: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        down?.let { RoundButton(Icons.Rounded.Remove) { set(it) } }
        up?.let { RoundButton(Icons.Rounded.Add) { set(it) } }
    }
}

@Composable
fun RoundButton(icon: ImageVector, bg: Color = Chip, tint: Color = Color.White, size: Int = 36, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.55f).dp)) }
}

/** Mushroom's named colours (and #hex). Greys and "disabled" mean "inactive" → null. */
fun mushroomColor(name: String?): Color? = when (name?.trim()?.lowercase()) {
    null, "", "grey", "gray", "dark-grey", "dark-gray", "disabled", "none", "black" -> null
    "red" -> Color(0xFFF44336); "pink" -> Color(0xFFE91E63); "purple" -> Color(0xFF9C27B0)
    "deep-purple" -> Color(0xFF673AB7); "indigo" -> Color(0xFF3F51B5); "blue" -> Color(0xFF2196F3)
    "light-blue" -> Color(0xFF03A9F4); "cyan" -> Color(0xFF00BCD4); "teal" -> Color(0xFF009688)
    "green" -> Color(0xFF4CAF50); "light-green" -> Color(0xFF8BC34A); "lime" -> Color(0xFFCDDC39)
    "yellow" -> Color(0xFFFFEB3B); "amber" -> Color(0xFFFFC107); "orange" -> Color(0xFFFF9800)
    "deep-orange" -> Color(0xFFFF5722); "brown" -> Color(0xFF795548); "blue-grey" -> Color(0xFF607D8B)
    "white" -> Color.White
    else -> name.trim().takeIf { it.startsWith("#") }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
}

/** The icon and highlight colour an entity row would use, for cards that draw entities their own way. */
fun entityIcon(e: Entity): ImageVector = iconOf(e, kindOf(e), isActive(e))
fun entityAccent(e: Entity): Color = accentOf(e, kindOf(e))

private fun accentOf(e: Entity, kind: Kind): Color = when (kind) {
    Kind.LIGHT -> e.attributes.optJSONArray("rgb_color")?.let { Color(it.getInt(0), it.getInt(1), it.getInt(2)) } ?: Amber
    Kind.THERMOSTAT -> if (e.attributes.optString("hvac_action") == "cooling") Cool else Heat
    Kind.LOCK, Kind.READOUT -> Alert // unlocked / door open / motion: things to notice
    Kind.MEDIA -> Cool
    Kind.COVER -> Calm
    else -> Amber
}

private fun iconOf(e: Entity, kind: Kind, active: Boolean): ImageVector = when (kind) {
    Kind.LIGHT -> if (active) Icons.Rounded.Lightbulb else Icons.Outlined.Lightbulb
    Kind.TOGGLE -> Icons.Rounded.Power
    Kind.THERMOSTAT -> Icons.Rounded.Thermostat
    Kind.NUMBER -> Icons.Rounded.Tune
    Kind.COVER -> Icons.Rounded.VerticalSplit
    Kind.LOCK -> if (e.state == "locked") Icons.Rounded.Lock else Icons.Rounded.LockOpen
    Kind.MEDIA -> Icons.Rounded.MusicNote
    Kind.ACTION -> Icons.Rounded.AutoAwesome
    Kind.READOUT -> when (e.attributes.optString("device_class")) {
        "temperature" -> Icons.Rounded.DeviceThermostat
        "humidity", "moisture" -> Icons.Rounded.WaterDrop
        "battery" -> Icons.Rounded.BatteryFull
        "power", "energy", "current", "voltage" -> Icons.Rounded.Bolt
        "door", "window", "opening", "garage_door" -> Icons.Rounded.DoorFront
        "motion", "occupancy", "presence" -> Icons.Rounded.DirectionsRun
        else -> Icons.Rounded.Sensors
    }
}

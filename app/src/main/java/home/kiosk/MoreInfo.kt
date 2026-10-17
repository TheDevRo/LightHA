package home.kiosk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.Locale


/** HA's "more info" dialog, natively: big controls for the entity's kind, then its attributes. Live while open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreInfoSheet(id: String, states: Map<String, Entity>, call: Call, onDismiss: () -> Unit) {
    val e = states[id] ?: return onDismiss()
    val svc = { service: String, data: JSONObject? -> call(e.domain, service, listOf(id), data) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardBg) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(e.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(stateText(e), ago(e.lastChanged)).filter { it.isNotEmpty() }.joinToString(" · "),
                color = TextDim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(20.dp))

            when (kindOf(e)) {
                Kind.LIGHT -> LightControls(e, svc)
                Kind.TOGGLE -> {
                    BigSwitch(e) { svc("toggle", null) }
                    e.attributes.optNum("percentage")?.let { pct ->
                        Labeled("Speed") { ValueSlider(pct.toFloat(), 0f..100f, 0) { svc("set_percentage", JSONObject().put("percentage", it.toInt())) } }
                    }
                }
                Kind.THERMOSTAT -> ClimateControls(e, svc)
                Kind.NUMBER -> {
                    val a = e.attributes
                    e.state.toFloatOrNull()?.let { v ->
                        ValueSlider(v, (a.optNum("min") ?: 0.0).toFloat()..(a.optNum("max") ?: 100.0).toFloat(), 0) {
                            svc("set_value", JSONObject().put("value", it.toDouble()))
                        }
                    }
                }
                Kind.COVER -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RoundButton(Icons.Rounded.ArrowUpward, size = 56) { svc("open_cover", null) }
                        RoundButton(Icons.Rounded.Stop, size = 56) { svc("stop_cover", null) }
                        RoundButton(Icons.Rounded.ArrowDownward, size = 56) { svc("close_cover", null) }
                    }
                    e.attributes.optNum("current_position")?.let { pos ->
                        Labeled("Position") { ValueSlider(pos.toFloat(), 0f..100f, 0) { svc("set_cover_position", JSONObject().put("position", it.toInt())) } }
                    }
                }
                Kind.LOCK -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RoundButton(Icons.Rounded.Lock, size = 56) { svc("lock", null) }
                    RoundButton(Icons.Rounded.LockOpen, size = 56) { svc("unlock", null) }
                }
                Kind.MEDIA -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RoundButton(Icons.Rounded.SkipPrevious, size = 56) { svc("media_previous_track", null) }
                        RoundButton(if (e.state == "playing") Icons.Rounded.Pause else Icons.Rounded.PlayArrow, Amber, CardBg, 56) { svc("media_play_pause", null) }
                        RoundButton(Icons.Rounded.SkipNext, size = 56) { svc("media_next_track", null) }
                    }
                    e.attributes.optNum("volume_level")?.let { vol ->
                        Labeled("Volume") { ValueSlider((vol * 100).toFloat(), 0f..100f, 0) { svc("volume_set", JSONObject().put("volume_level", it / 100.0)) } }
                    }
                }
                Kind.ACTION -> RoundButton(Icons.Rounded.PlayArrow, Amber, CardBg, 56) {
                    svc(if (e.domain.endsWith("button")) "press" else "turn_on", null)
                }
                Kind.READOUT -> Unit
            }

            Attributes(e.attributes)
        }
    }
}

@Composable
private fun LightControls(e: Entity, svc: (String, JSONObject?) -> Unit) {
    BigSwitch(e) { svc("toggle", null) }
    val modes = e.attributes.optJSONArray("supported_color_modes")?.strings().orEmpty()
    if (modes.any { it != "onoff" }) {
        val pct = (e.attributes.optNum("brightness") ?: 0.0) * 100 / 255
        Labeled("Brightness") { ValueSlider(if (e.state == "on") pct.toFloat() else 0f, 1f..100f, 0) { svc("turn_on", JSONObject().put("brightness_pct", it.toInt())) } }
    }
    if ("color_temp" in modes) {
        val min = e.attributes.optNum("min_color_temp_kelvin") ?: 2000.0
        val max = e.attributes.optNum("max_color_temp_kelvin") ?: 6500.0
        val k = e.attributes.optNum("color_temp_kelvin") ?: min
        Labeled("Warmth") { ValueSlider(k.toFloat(), min.toFloat()..max.toFloat(), 0) { svc("turn_on", JSONObject().put("color_temp_kelvin", it.toInt())) } }
    }
    if (modes.any { it in setOf("hs", "xy", "rgb", "rgbw", "rgbww") }) {
        Labeled("Color") { Swatches { hue -> svc("turn_on", JSONObject().put("hs_color", JSONArray().put(hue).put(100))) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClimateControls(e: Entity, svc: (String, JSONObject?) -> Unit) {
    val a = e.attributes
    val target = a.optNum("temperature")
    val low = a.optNum("target_temp_low")
    val high = a.optNum("target_temp_high")
    val step = a.optNum("target_temp_step") ?: if ((target ?: high ?: 0.0) > 40) 1.0 else 0.5

    @Composable
    fun Target(label: String, value: Double, set: (Double) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            RoundButton(Icons.Rounded.Remove, size = 52) { set(value - step) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmt(value) + "°", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Light)
                Text(label, color = TextDim, fontSize = 13.sp)
            }
            RoundButton(Icons.Rounded.Add, size = 52) { set(value + step) }
        }
    }

    when {
        target != null -> Target("Target · now ${a.optNum("current_temperature")?.let(::fmt) ?: "–"}°", target) {
            svc("set_temperature", JSONObject().put("temperature", it))
        }
        low != null && high != null -> {
            Target("Heat to", low) { svc("set_temperature", JSONObject().put("target_temp_low", it).put("target_temp_high", high)) }
            Target("Cool to", high) { svc("set_temperature", JSONObject().put("target_temp_low", low).put("target_temp_high", it)) }
        }
    }
    a.optJSONArray("hvac_modes")?.strings()?.let { modes ->
        Labeled("Mode") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { m ->
                    val on = m == e.state
                    Text(
                        m.replace('_', '/').replaceFirstChar { it.uppercase() },
                        color = if (on) CardBg else Color.White, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Amber else Chip)
                            .clickable { svc("set_hvac_mode", JSONObject().put("hvac_mode", m)) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BigSwitch(e: Entity, toggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 8.dp)) {
        Text(if (e.state == "on") "On" else "Off", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = e.state == "on", onCheckedChange = { toggle() },
            colors = SwitchDefaults.colors(checkedTrackColor = Amber, checkedThumbColor = CardBg, uncheckedTrackColor = Chip, uncheckedBorderColor = Chip),
        )
    }
}

/** A slider that only calls HA when the finger lifts, and follows HA's value otherwise. */
@Composable
private fun ValueSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onSet: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onSet(local) },
            valueRange = range, steps = steps, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Chip),
        )
        Text(fmt(local.toDouble()), color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Swatches(onHue: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(0, 30, 50, 120, 180, 220, 270, 320).forEach { hue ->
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(Color.hsv(hue.toFloat(), 0.85f, 1f))
                    .border(2.dp, CardBg, CircleShape)
                    .clickable { onHue(hue) },
            )
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(label.uppercase(), color = TextDim, fontSize = 12.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(6.dp))
    content()
}

private val HIDDEN_ATTRS = setOf("friendly_name", "icon", "entity_picture", "supported_features", "supported_color_modes", "hvac_modes", "attribution", "editable", "id")

@Composable
private fun Attributes(a: JSONObject) {
    val rows = a.keys().asSequence().filter { it !in HIDDEN_ATTRS && !a.isNull(it) }.sorted().toList()
    if (rows.isEmpty()) return
    Labeled("Attributes") {
        rows.forEach { k ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(k.replace('_', ' ').replaceFirstChar { it.uppercase() }, color = TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(attrText(a.get(k)), color = Color.White, fontSize = 14.sp, maxLines = 2)
            }
        }
    }
}

private fun attrText(v: Any): String = when (v) {
    is JSONArray -> v.strings().joinToString(", ")
    is Double -> fmt(v)
    else -> v.toString()
}

private fun JSONArray.strings() = (0 until length()).map { optString(it) }
private fun JSONObject.optNum(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null
private fun fmt(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else String.format(Locale.US, "%.1f", d)

/** "2026-10-15T22:30:00+00:00" -> "5 min ago" (relative to [now]). */
internal fun ago(iso: String, now: Date = Date()): String {
    if (iso.isEmpty()) return ""
    val t = parseIso(iso) ?: return ""
    val min = (now.time - t.time) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} h ago"
        else -> "${min / (60 * 24)} d ago"
    }
}

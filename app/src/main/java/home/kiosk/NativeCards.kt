package home.kiosk

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// HA's native cards that don't fit an entity row: glance, shortcut, alert, toggle-group, gauge,
// statistic, plant-status, humidifier, alarm-panel, picture, iframe.

private val Amber = Color(0xFFFFB74D)
private val Danger = Color(0xFFE57373)

/** The card's (or row's) tap, falling back to more-info like HA's glance and picture cards. */
private fun tapOrInfo(conf: JSONObject, id: String, ctx: CardContext): () -> Unit =
    ctx.runner(tapAct(conf, id), id) ?: { ctx.openMoreInfo(id) }

@Composable
private fun Title(text: String) {
    if (text.isNotEmpty()) Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
}

// ---- glance ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlanceCard(card: JSONObject, ctx: CardContext) {
    val rows = entityRows(card).filter { ctx.states.containsKey(it.text("entity")) }
    val cols = card.optInt("columns", 0).takeIf { it > 0 } ?: rows.size.coerceIn(1, 5)
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Title(card.text("title"))
        rows.chunked(cols).forEach { line ->
            Row {
                line.forEach { row ->
                    val e = ctx.states.getValue(row.text("entity"))
                    Column(
                        Modifier.weight(1f).combinedClickable(onClick = tapOrInfo(row, e.id, ctx), onLongClick = ctx.runner(holdAct(row, e.id), e.id)).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (card.optBoolean("show_name", true)) Text(row.text("name").ifEmpty { e.name }, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (card.optBoolean("show_icon", true)) {
                            Icon(entityIcon(e), null, tint = if (isActive(e)) entityAccent(e) else Color.White, modifier = Modifier.padding(vertical = 6.dp).size(26.dp))
                        }
                        if (card.optBoolean("show_state", true) && row.optBoolean("show_state", true)) {
                            Text(stateText(e).ifEmpty { e.state }, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                repeat(cols - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---- shortcut ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortcutCard(card: JSONObject, ctx: CardContext) {
    val color = mushroomColor(card.text("color")) ?: Amber
    Row(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { ctx.runner(tapAct(card, null), null)?.invoke() },
            onLongClick = card.optJSONObject("hold_action")?.let { ctx.runner(holdAct(card, null), null) },
            onDoubleClick = card.optJSONObject("double_tap_action")?.let { ctx.runner(doubleTapAct(card, null), null) },
        ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ChevronRight, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(card.text("label").ifEmpty { card.text("text") }.ifEmpty { "Shortcut" }, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            card.text("description").takeIf { it.isNotEmpty() }?.let { Text(it, color = TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ---- alert ----

@Composable
fun AlertCard(card: JSONObject, ctx: CardContext, top: Boolean) {
    val e = ctx.states[card.text("entity")] ?: return
    // HA: no colour (or "none") draws it plain; `pulse` breathes the tint.
    val color = card.text("color").takeIf { it.isNotEmpty() && it != "none" }?.let { mushroomColor(it) ?: Danger }
    val pulse = if (card.optBoolean("pulse")) {
        rememberInfiniteTransition(label = "pulse").animateFloat(0.12f, 0.35f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "pulse").value
    } else 0.2f
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(if (top) 24.dp else 16.dp)).background(CardBg)) {
        Box(Modifier.matchParentSize().background(color?.copy(alpha = pulse) ?: Color.Transparent))
        Row(Modifier.fillMaxWidth().clickable { ctx.openMoreInfo(e.id) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(entityIcon(e), null, tint = color ?: TextDim, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Text(card.text("name").ifEmpty { e.name }, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stateText(e).ifEmpty { e.state }, color = Color.White, fontSize = 14.sp)
        }
    }
}

// ---- toggle-group ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToggleGroupCard(card: JSONObject, ctx: CardContext) {
    val color = mushroomColor(card.text("color")) ?: Amber
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Title(card.text("title"))
        FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entityRows(card).mapNotNull { ctx.states[it.text("entity")] }.forEach { e ->
                val on = e.state == "on"
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) color else Chip)
                        .clickable { ctx.call("homeassistant", "toggle", listOf(e.id), null) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(entityIcon(e), null, tint = if (on) CardBg else TextDim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(e.name, color = if (on) CardBg else Color.White, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

// ---- gauge ----

/** HA's gauge colour: the last `segments` entry at or below the value, else the highest `severity` threshold reached. Null = default. */
fun gaugeColor(card: JSONObject, value: Double): Color? {
    card.optJSONArray("segments")?.objects()?.let { segs ->
        return segs.filter { it.optDouble("from", Double.NaN) <= value }.maxByOrNull { it.optDouble("from") }?.let { mushroomColor(it.text("color")) }
    }
    val sev = card.optJSONObject("severity") ?: return null
    return listOf("green", "yellow", "red").filter { sev.has(it) && sev.optDouble(it) <= value }
        .maxByOrNull { sev.optDouble(it) }?.let { mushroomColor(it) }
}

@Composable
fun GaugeCard(card: JSONObject, ctx: CardContext) {
    val e = ctx.states[card.text("entity")] ?: return
    val attr = card.text("attribute")
    val value = (if (attr.isNotEmpty()) e.attributes.opt(attr)?.toString() else e.state)?.toDoubleOrNull()
    val min = card.optDouble("min", 0.0)
    val max = card.optDouble("max", 100.0).takeIf { it > min } ?: (min + 1)
    val color = value?.let { gaugeColor(card, it) } ?: Amber
    val unit = card.text("unit").ifEmpty { e.attributes.text("unit_of_measurement") }
    Column(Modifier.fillMaxWidth().clickable(onClick = tapOrInfo(card, e.id, ctx)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max = 220.dp).fillMaxWidth().aspectRatio(2f), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.width * 0.1f
                val arc = Size(size.width - stroke, size.width - stroke)
                val at = Offset(stroke / 2, stroke / 2)
                drawArc(Chip, 180f, 180f, false, at, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                val f = (((value ?: min) - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
                if (value != null && f > 0f) drawArc(color, 180f, 180f * f, false, at, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(
                value?.let { num(it) + unit.let { u -> if (u.isEmpty() || u == "%" || u.startsWith("°")) u else " $u" } } ?: stateText(e),
                color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium,
            )
        }
        Text(card.text("name").ifEmpty { e.name }, color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun num(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else String.format(Locale.US, "%.1f", d)

// ---- statistic ----

/** The `recorder/statistic_during_period` request for a statistic card: stat type plus its period (calendar day by default). */
fun statisticRequest(card: JSONObject): JSONObject {
    val req = JSONObject().put("statistic_id", card.text("entity")).put("types", JSONArray().put(card.text("stat_type").ifEmpty { "change" }))
    val period = card.optJSONObject("period") ?: JSONObject().put("calendar", JSONObject().put("period", "day"))
    listOf("calendar", "fixed_period", "rolling_window").forEach { k -> period.optJSONObject(k)?.let { req.put(k, it) } }
    if (!req.has("calendar") && !req.has("fixed_period") && !req.has("rolling_window")) req.put("calendar", JSONObject().put("period", "day"))
    return req
}

@Composable
fun StatisticCard(card: JSONObject, ctx: CardContext) {
    val ha = LocalHa.current
    val id = card.text("entity")
    val e = ctx.states[id]
    val type = card.text("stat_type").ifEmpty { "change" }
    // Refreshed every 5 minutes; long-term statistics are written every 5 anyway.
    val value = produceState<Double?>(null, card.toString()) {
        while (true) {
            val req = statisticRequest(card)
            runCatching {
                ha.request("recorder/statistic_during_period") { req.keys().forEach { k -> put(k, req.get(k)) } } as? JSONObject
            }.getOrNull()?.optDouble(type)?.takeUnless { it.isNaN() }?.let { value = it }
            delay(5 * 60_000L)
        }
    }.value
    val unit = card.text("unit").ifEmpty { e?.attributes?.text("unit_of_measurement").orEmpty() }
    Column(Modifier.fillMaxWidth().clickable { ctx.openMoreInfo(id) }.padding(16.dp)) {
        Text(card.text("name").ifEmpty { e?.name ?: id }, color = TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.let { num(it) } ?: "–", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
            if (unit.isNotEmpty()) Text(" $unit", color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

// ---- plant-status ----

private val PLANT_READINGS = listOf("moisture" to "Moisture", "temperature" to "Temp", "brightness" to "Light", "conductivity" to "Fertility", "battery" to "Battery")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlantCard(card: JSONObject, ctx: CardContext) {
    val e = ctx.states[card.text("entity")] ?: return
    val problems = e.attributes.text("problem")
    val units = e.attributes.optJSONObject("unit_of_measurement_dict")
    Column(Modifier.fillMaxWidth().clickable { ctx.openMoreInfo(e.id) }.padding(16.dp)) {
        Text(card.text("name").ifEmpty { e.name }, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            PLANT_READINGS.filter { e.attributes.has(it.first) && !e.attributes.isNull(it.first) }.forEach { (key, label) ->
                val bad = key in problems
                Column {
                    Text(label, color = TextDim, fontSize = 12.sp)
                    Text(e.attributes.optString(key) + (units?.text(key)?.let { " $it" } ?: ""), color = if (bad) Danger else Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}

// ---- humidifier ----

@Composable
fun HumidifierCard(card: JSONObject, ctx: CardContext) {
    val e = ctx.states[card.text("entity")] ?: return
    val a = e.attributes
    val target = a.optDouble("humidity").takeUnless { it.isNaN() }
    val min = a.optDouble("min_humidity", 0.0)
    val max = a.optDouble("max_humidity", 100.0)
    fun set(v: Double) = ctx.call("humidifier", "set_humidity", listOf(e.id), JSONObject().put("humidity", v.coerceIn(min, max).toInt()))
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable { ctx.openMoreInfo(e.id) }) {
            Text(card.text("name").ifEmpty { e.name }, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val current = a.optDouble("current_humidity").takeUnless { it.isNaN() }?.let { "Now ${num(it)}% · " }.orEmpty()
            Text(current + (if (e.state == "on") "Target ${target?.let { num(it) } ?: "–"}%" else "Off"), color = TextDim, fontSize = 12.sp)
        }
        if (e.state == "on" && target != null) {
            RoundButton(Icons.Rounded.Remove) { set(target - 1) }
            Spacer(Modifier.width(8.dp))
            RoundButton(Icons.Rounded.Add) { set(target + 1) }
            Spacer(Modifier.width(8.dp))
        }
        RoundButton(Icons.Rounded.Power, bg = if (e.state == "on") Amber else Chip, tint = if (e.state == "on") CardBg else Color.White) {
            ctx.call("humidifier", "toggle", listOf(e.id), null)
        }
    }
}

// ---- alarm-panel ----

// alarm_control_panel supported_features bits (homeassistant/components/alarm_control_panel/const.py)
private val ALARM_FEATURES = mapOf("arm_home" to 1L, "arm_away" to 2L, "arm_night" to 4L, "arm_custom_bypass" to 16L, "arm_vacation" to 32L)

/** The arm buttons a disarmed panel offers: the card's `states`, else home/away filtered by what the panel supports. */
fun alarmActions(card: JSONObject, e: Entity): List<String> {
    if (e.state != "disarmed") return listOf("disarm")
    card.optJSONArray("states")?.let { arr -> return (0 until arr.length()).map { arr.optString(it) } }
    val features = e.attributes.optLong("supported_features")
    return listOf("arm_home", "arm_away").filter { features and (ALARM_FEATURES[it] ?: 0L) != 0L }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmCard(card: JSONObject, ctx: CardContext) {
    val e = ctx.states[card.text("entity")] ?: return
    var code by remember(e.id) { mutableStateOf("") }
    val format = e.attributes.text("code_format")
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ctx.openMoreInfo(e.id) }) {
            Icon(Icons.Rounded.Shield, null, tint = if (e.state == "disarmed") TextDim else Danger, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("${card.text("name").ifEmpty { e.name }} · ${e.state.replace('_', ' ').replaceFirstChar { it.uppercase() }}", color = Color.White, fontSize = 16.sp)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            alarmActions(card, e).forEach { act ->
                Text(
                    act.removePrefix("arm_").replace('_', ' ').replaceFirstChar { it.uppercase() }.let { if (act == "disarm") "Disarm" else "Arm $it" },
                    color = CardBg, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (act == "disarm") Danger else Amber)
                        .clickable {
                            ctx.call("alarm_control_panel", "alarm_$act", listOf(e.id), code.takeIf { it.isNotEmpty() }?.let { JSONObject().put("code", it) })
                            code = ""
                        }.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        // Numeric codes get HA's keypad; text codes aren't typed on a wall tablet.
        if (format == "number") {
            Spacer(Modifier.height(12.dp))
            Text("•".repeat(code.length).ifEmpty { "Code" }, color = if (code.isEmpty()) TextDim else Color.White, fontSize = 20.sp)
            listOf("123", "456", "789", "C0<").forEach { keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    keys.forEach { k ->
                        Box(
                            Modifier.size(56.dp).clip(CircleShape).background(Chip).clickable {
                                code = when (k) { 'C' -> ""; '<' -> code.dropLast(1); else -> code + k }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (k) {
                                'C' -> Icon(Icons.Rounded.Close, "Clear", tint = Color.White)
                                '<' -> Icon(Icons.Rounded.Backspace, "Delete", tint = Color.White)
                                else -> Text(k.toString(), color = Color.White, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- picture, and picture-entity / picture-glance without a camera ----

/** The image a picture card shows: `state_image` for the entity's state, `image` (string or media object), else the image entity's picture. */
fun pictureUrl(card: JSONObject, states: Map<String, Entity>): String? {
    val e = states[card.text("entity")]
    card.optJSONObject("state_image")?.let { m -> e?.let { m.text(it.state) }?.takeIf { it.isNotEmpty() }?.let { return it } }
    val img = card.optJSONObject("image")?.text("media_content_id") ?: card.text("image")
    if (img.isNotEmpty()) return img
    val source = states[card.text("image_entity")] ?: e
    return source?.attributes?.text("entity_picture")?.takeIf { it.isNotEmpty() }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun PictureCard(card: JSONObject, ctx: CardContext) {
    val ha = LocalHa.current
    val raw = pictureUrl(card, ctx.states)
    // media-source:// images (picked in HA's editor) resolve to a signed URL first.
    val url = if (raw?.startsWith("media-source://") == true) produceState<String?>(null, raw) {
        value = runCatching { (ha.request("media_source/resolve_media") { put("media_content_id", raw) } as JSONObject).text("url") }.getOrNull()
    }.value else raw
    val img = rememberImage(url)
    val id = card.text("entity")
    val e = ctx.states[id]
    val tap = ctx.runner(tapAct(card, id.ifEmpty { null }), id.ifEmpty { null }) ?: e?.let { { ctx.openMoreInfo(it.id) } }
    val glance = entityRows(JSONObject().put("entities", card.optJSONArray("entities") ?: JSONArray())).mapNotNull { ctx.states[it.text("entity")] }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg)
            .combinedClickable(onClick = { tap?.invoke() }, onLongClick = card.optJSONObject("hold_action")?.let { ctx.runner(holdAct(card, id), id) }),
    ) {
        if (img != null) Image(img, null, Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height.coerceAtLeast(1)), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        val showName = card.optBoolean("show_name", true) && card.optString("type") != "picture"
        val name = card.text("name").ifEmpty { e?.name.orEmpty() }.ifEmpty { card.text("title") }
        if ((showName && name.isNotEmpty()) || glance.isNotEmpty() || (e != null && card.optBoolean("show_state", true) && card.optString("type") == "picture-entity")) {
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (showName) name else "", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.optString("type") == "picture-entity" && e != null && card.optBoolean("show_state", true)) Text(stateText(e).ifEmpty { e.state }, color = Color.White, fontSize = 15.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    glance.forEach { g ->
                        Icon(
                            entityIcon(g), g.name, tint = if (isActive(g)) entityAccent(g) else Color.White,
                            modifier = Modifier.size(24.dp).clickable { tapService(g)?.let { s -> ctx.call(g.domain, s, listOf(g.id), null) } ?: ctx.openMoreInfo(g.id) },
                        )
                    }
                }
            }
        }
    }
}

// ---- iframe: a tile that opens the page full screen (an inline WebView is what this app exists to avoid) ----

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IframeCard(card: JSONObject) {
    val base = LocalSettings.current.haUrl.trimEnd('/')
    val url = card.text("url").let { if (it.startsWith("/")) base + it else it }
    if (url.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(card.text("title").ifEmpty { url.substringAfter("://").substringBefore('/') }, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Rounded.OpenInNew, "Open", tint = TextDim, modifier = Modifier.size(20.dp))
    }
    if (open) Dialog({ open = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            AndroidView(
                factory = { WebView(it).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = WebViewClient(); loadUrl(url) } },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp).alpha(0.9f)) { RoundButton(Icons.Rounded.Close, bg = Chip) { open = false } }
        }
    }
}


// ---- registries (areas, devices, entity → area), shared by area and home-summary cards ----

data class Area(val id: String, val name: String, val picture: String, val temperature: String, val humidity: String)

/** HA's area/device/entity registries, as the frontend loads them for every user. Cached 30 minutes. */
object Registries {
    class Snapshot(val areas: Map<String, Area>, val entityArea: Map<String, String>, val hidden: Set<String>)
    private var cached: Snapshot? = null
    private var at = 0L

    suspend fun get(ha: HaClient): Snapshot? {
        cached?.takeIf { System.currentTimeMillis() - at < 30 * 60_000L }?.let { return it }
        return runCatching {
            val areas = (ha.request("config/area_registry/list") as JSONArray).objects().associate {
                it.text("area_id") to Area(it.text("area_id"), it.text("name"), it.text("picture"), it.text("temperature_entity_id"), it.text("humidity_entity_id"))
            }
            val deviceArea = (ha.request("config/device_registry/list") as JSONArray).objects()
                .filter { it.text("area_id").isNotEmpty() }.associate { it.text("id") to it.text("area_id") }
            val ents = ((ha.request("config/entity_registry/list_for_display") as JSONObject).optJSONArray("entities") ?: JSONArray()).objects()
            val entityArea = ents.mapNotNull { e -> (e.text("ai").ifEmpty { deviceArea[e.text("di")].orEmpty() }).takeIf { it.isNotEmpty() }?.let { e.text("ei") to it } }.toMap()
            Snapshot(areas, entityArea, ents.filter { it.optBoolean("hb") }.map { it.text("ei") }.toSet())
        }.getOrNull()?.also { cached = it; at = System.currentTimeMillis() } ?: cached
    }
}

@Composable
private fun rememberRegistries(): Registries.Snapshot? {
    val ha = LocalHa.current
    return produceState<Registries.Snapshot?>(null, ha) { value = Registries.get(ha) }.value
}

// ---- area ----

private val TOGGLE_DOMAINS = setOf("light", "switch", "fan")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AreaCard(card: JSONObject, ctx: CardContext) {
    val reg = rememberRegistries() ?: return
    val area = reg.areas[card.text("area")] ?: return
    val members = reg.entityArea.filterValues { it == area.id }.keys.filter { it !in reg.hidden }.mapNotNull { ctx.states[it] }
    val excluded = card.optJSONArray("exclude_entities")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    val shown = members.filter { it.id !in excluded }
    val sensorClasses = card.optJSONArray("sensor_classes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("temperature", "humidity")
    // HA: the area's chosen sensor, else the average of the area's sensors of that class (same unit).
    val sensors = sensorClasses.mapNotNull { cls ->
        val preset = when (cls) { "temperature" -> area.temperature; "humidity" -> area.humidity; else -> "" }
        ctx.states[preset]?.let { stateText(it) } ?: run {
            val of = shown.filter { it.domain == "sensor" && it.attributes.text("device_class") == cls && it.state.toDoubleOrNull() != null }
            val unit = of.firstOrNull()?.attributes?.text("unit_of_measurement") ?: return@run null
            val vals = of.filter { it.attributes.text("unit_of_measurement") == unit }.map { it.state.toDouble() }
            stateText(Entity("sensor.avg", vals.average().toString(), JSONObject().put("unit_of_measurement", unit)))
        }
    }
    val alertClasses = card.optJSONArray("alert_classes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("moisture", "motion")
    val alerts = shown.filter { it.domain == "binary_sensor" && it.state == "on" && it.attributes.text("device_class") in alertClasses }
    val nav = card.text("navigation_path")
    val img = rememberImage(area.picture.takeIf { it.isNotEmpty() })
    Column(Modifier.fillMaxWidth().clickable(enabled = nav.isNotEmpty()) { ctx.openView(nav) }) {
        img?.let { Image(it, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop) }
        Column(Modifier.padding(16.dp)) {
            Text(card.text("name").ifEmpty { area.name }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            val line = (sensors + alerts.map { "${it.name} ${stateText(it)}" }).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, color = if (alerts.isEmpty()) TextDim else Danger, fontSize = 13.sp)
            val toggles = shown.filter { it.domain in TOGGLE_DOMAINS }
            if (toggles.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                toggles.forEach { e ->
                    val on = e.state == "on"
                    Icon(
                        entityIcon(e), e.name, tint = if (on) CardBg else Color.White,
                        modifier = Modifier.clip(CircleShape).background(if (on) entityAccent(e) else Chip).clickable { ctx.call(e.domain, "toggle", listOf(e.id), null) }.padding(9.dp).size(20.dp),
                    )
                }
            }
        }
    }
}

// ---- home-summary (HA's Home dashboard tiles) ----

/** The one-line summary HA's home-summary card shows; empty when there's nothing to say. Registries are only needed for climate. */
fun homeSummary(summary: String, states: Map<String, Entity>, areaTemps: List<String> = emptyList(), hidden: Set<String> = emptySet()): String {
    val all = states.values.filter { it.id !in hidden }
    // Light groups list their members in `entity_id`; counting them too would double up.
    fun real(e: Entity) = !e.attributes.has("entity_id")
    return when (summary) {
        "light" -> all.count { it.domain == "light" && real(it) && it.state == "on" }.let { if (it == 0) "All lights off" else "$it ${if (it == 1) "light" else "lights"} on" }
        "climate" -> areaTemps.mapNotNull { states[it]?.state?.toDoubleOrNull() }.takeIf { it.isNotEmpty() }?.let {
            val lo = String.format(Locale.US, "%.1f", it.min()); val hi = String.format(Locale.US, "%.1f", it.max())
            if (lo == hi) "$lo°" else "$lo - $hi°"
        }.orEmpty()
        "security" -> {
            val locks = all.filter { it.domain == "lock" }
            val alarms = all.filter { it.domain == "alarm_control_panel" }
            val unlocked = locks.count { it.state in setOf("unlocked", "jammed", "open") }
            val disarmed = alarms.count { it.state == "disarmed" }
            when {
                locks.isEmpty() && alarms.isEmpty() -> ""
                unlocked > 0 -> "$unlocked unlocked"
                disarmed > 0 -> "$disarmed disarmed"
                else -> "All secure"
            }
        }
        "media_players" -> all.count { it.domain == "media_player" && it.state == "playing" }.let { if (it == 0) "Nothing playing" else "$it playing" }
        "persons" -> all.filter { it.domain == "person" }.let { p -> if (p.isEmpty()) "" else "${p.count { it.state == "home" }} of ${p.size} home" }
        "maintenance" -> all.count { it.domain == "sensor" && it.attributes.text("device_class") == "battery" && (it.state.toDoubleOrNull() ?: 100.0) < 20 }
            .let { if (it == 0) "" else "$it low ${if (it == 1) "battery" else "batteries"}" }
        else -> ""
    }
}

private val SUMMARY_TITLES = mapOf(
    "alerts" to "Alerts", "light" to "Lights", "climate" to "Climate", "security" to "Security",
    "media_players" to "Media", "maintenance" to "Maintenance", "energy" to "Energy", "persons" to "People",
)
private val SUMMARY_COLORS = mapOf(
    "alerts" to "red", "light" to "amber", "climate" to "deep-orange", "security" to "blue-grey",
    "media_players" to "blue", "maintenance" to "grey", "energy" to "amber", "persons" to "green",
)

@Composable
fun HomeSummaryCard(card: JSONObject, ctx: CardContext) {
    val summary = card.text("summary")
    val reg = rememberRegistries()
    val text = if (summary == "alerts") {
        val active = card.optJSONArray("alert_entities")?.objects().orEmpty().count { ctx.states[it.text("entity")]?.let(::isActive) == true }
        if (active == 0) "" else "$active active"
    } else homeSummary(summary, ctx.states, reg?.areas?.values?.map { it.temperature }?.filter { it.isNotEmpty() }.orEmpty(), reg?.hidden.orEmpty())
    val color = mushroomColor(SUMMARY_COLORS[summary]) ?: TextDim
    Row(
        Modifier.fillMaxWidth().clickable { ctx.runner(tapAct(card, null), null)?.invoke() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ChevronRight, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(SUMMARY_TITLES[summary] ?: summary, color = Color.White, fontSize = 15.sp)
            if (text.isNotEmpty()) Text(text, color = TextDim, fontSize = 12.sp)
        }
    }
}

// ---- updates / repairs / discovered devices ----

@Composable
fun UpdatesCard(card: JSONObject, ctx: CardContext, top: Boolean) {
    val pending = ctx.states.values.filter { it.domain == "update" && it.state == "on" }.sortedBy { it.name }
    if (pending.isEmpty() && card.optBoolean("hide_empty", true)) return
    Surface(top) {
        Column {
            Title("Updates")
            if (pending.isEmpty()) Text("Everything is up to date", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
            pending.forEach { e -> EntityCard(JSONObject().put("entity", e.id), ctx) }
        }
    }
}

/** Repairs and discovered devices need an admin login; for anyone else HA refuses and the card stays hidden, like HA's empty state. */
@Composable
fun AdminListCard(card: JSONObject, ctx: CardContext, top: Boolean) {
    val ha = LocalHa.current
    val repairs = card.optString("type") == "repairs"
    val items = produceState<List<String>?>(null, repairs) {
        while (true) {
            value = runCatching {
                if (repairs) ((ha.request("repairs/list_issues") as JSONObject).optJSONArray("issues") ?: JSONArray()).objects()
                    .filter { !it.optBoolean("ignored") && it.isNull("dismissed_version") }.map { it.text("translation_key").ifEmpty { it.text("issue_id") }.replace('_', ' ').replaceFirstChar { c -> c.uppercase() } + " (" + it.text("domain") + ")" }
                else (ha.request("config_entries/flow/progress") as JSONArray).objects()
                    .filter { it.optJSONObject("context")?.text("source") !in setOf("user", "reauth", "reconfigure") }.map { it.text("handler") }
            }.getOrNull()
            delay(10 * 60_000L)
        }
    }.value ?: return
    if (items.isEmpty() && card.optBoolean("hide_empty", true)) return
    Surface(top) {
        Column(Modifier.padding(bottom = 10.dp)) {
            Title(if (repairs) "Repairs" else "Discovered devices")
            if (items.isEmpty()) Text("Nothing here", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
            items.forEach { Text(it, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        }
    }
}

// ---- logbook ----

data class LogEntry(val time: Long, val name: String, val text: String, val entityId: String)

/** `logbook/get_events` rows, newest first. `message` when HA wrote one, else the new state. */
fun parseLogbook(arr: JSONArray, states: Map<String, Entity>): List<LogEntry> = arr.objects().mapNotNull { r ->
    val t = r.optDouble("when").takeUnless { it.isNaN() } ?: return@mapNotNull null
    val id = r.text("entity_id")
    val name = r.text("name").ifEmpty { states[id]?.name ?: id }
    // The state then, not now: on/off read as actions (current brightness would be wrong for an old entry).
    val st = r.text("state")
    val text = r.text("message").ifEmpty {
        when {
            !id.startsWith("binary_sensor.") && st == "on" -> "Turned on"
            !id.startsWith("binary_sensor.") && st == "off" -> "Turned off"
            else -> states[id]?.let { stateText(Entity(id, st, it.attributes)) } ?: st
        }
    }
    LogEntry((t * 1000).toLong(), name, text, id)
}.sortedByDescending { it.time }

@Composable
fun LogbookCard(card: JSONObject, ctx: CardContext) {
    val ha = LocalHa.current
    val ids = entityRows(card).map { it.text("entity") } +
        (card.optJSONObject("target")?.opt("entity_id")?.let { if (it is JSONArray) (0 until it.length()).map { i -> it.optString(i) } else listOf(it.toString()) }.orEmpty())
    val hours = card.optDouble("hours_to_show", 24.0)
    // Raw rows kept; names resolve when drawn, since the first fetch can land before entity states do.
    val raw = produceState<JSONArray?>(null, ids, hours) {
        while (true) {
            runCatching {
                ha.request("logbook/get_events") {
                    put("start_time", isoUtc(System.currentTimeMillis() - (hours * 3_600_000).toLong()))
                    if (ids.isNotEmpty()) put("entity_ids", JSONArray(ids))
                } as JSONArray
            }.getOrNull()?.let { value = it }
            delay(60_000L)
        }
    }.value
    val entries = raw?.let { parseLogbook(it, ctx.states).take(10) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Title(card.text("title").ifEmpty { "Logbook" })
        if (entries?.isEmpty() == true) Text("No activity", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        entries?.forEach { l ->
            Row(Modifier.fillMaxWidth().clickable(enabled = l.entityId.isNotEmpty()) { ctx.openMoreInfo(l.entityId) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(l.text, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(timeText(isoUtc(l.time)), color = TextDim, fontSize = 12.sp)
            }
        }
    }
}

// ---- picture-elements ----

/** CSS "45%" → 0.45. Pixel or missing positions → null (the element isn't placed). */
fun cssPercent(v: String?): Float? = v?.trim()?.takeIf { it.endsWith("%") }?.removeSuffix("%")?.trim()?.toFloatOrNull()?.div(100f)

@Composable
fun PictureElementsCard(card: JSONObject, ctx: CardContext) {
    val camera = ctx.states[card.text("camera_image")]?.attributes?.text("entity_picture")
    val img = rememberImage(pictureUrl(card, ctx.states) ?: camera)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg)) {
        if (img != null) Image(img, null, Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height.coerceAtLeast(1)), contentScale = ContentScale.FillWidth)
        else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
            Elements(card.optJSONArray("elements")?.objects().orEmpty(), ctx, maxWidth, maxHeight)
        }
    }
}

@Composable
private fun Elements(elements: List<JSONObject>, ctx: CardContext, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    elements.forEach { el ->
        if (el.text("type") == "conditional") {
            if (conditionsMet(el.optJSONArray("conditions"), "", ctx.states)) Elements(el.optJSONArray("elements")?.objects().orEmpty(), ctx, w, h)
            return@forEach
        }
        val style = el.optJSONObject("style")
        val left = cssPercent(style?.text("left")) ?: return@forEach
        val top = cssPercent(style?.text("top")) ?: return@forEach
        // HA centres each element on its point (transform: translate(-50%, -50%)).
        Box(Modifier.offset(w * left, h * top).layout { m, c ->
            val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
            layout(p.width, p.height) { p.place(-p.width / 2, -p.height / 2) }
        }) { Element(el, ctx, w) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Element(el: JSONObject, ctx: CardContext, cardWidth: androidx.compose.ui.unit.Dp) {
    val id = el.text("entity")
    val e = ctx.states[id]
    val tap = ctx.runner(tapAct(el, id.ifEmpty { null }), id.ifEmpty { null }) ?: e?.let { { ctx.openMoreInfo(it.id) } }
    val mod = Modifier.combinedClickable(onClick = { tap?.invoke() }, onLongClick = e?.let { ctx.runner(holdAct(el, id), id) })
    when (el.text("type")) {
        "state-label" -> e?.let {
            val v = el.text("attribute").takeIf { a -> a.isNotEmpty() }?.let { a -> it.attributes.optString(a) } ?: stateText(it).ifEmpty { it.state }
            Text(el.text("prefix") + v + el.text("suffix"), color = Color.White, fontSize = 14.sp, modifier = mod.background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        "state-badge" -> e?.let { Text(stateText(it).ifEmpty { it.state }, color = Color.White, fontSize = 12.sp, modifier = mod.clip(RoundedCornerShape(50)).background(Chip).padding(horizontal = 10.dp, vertical = 6.dp)) }
        "state-icon" -> e?.let { Icon(entityIcon(it), it.name, tint = if (isActive(it)) entityAccent(it) else Color.White, modifier = mod.size(28.dp)) }
        "icon" -> Icon(Icons.Rounded.ChevronRight, el.text("title").ifEmpty { null }, tint = Color.White, modifier = mod.size(28.dp))
        "service-button", "action-button" -> Text(
            el.text("title").ifEmpty { "Run" }, color = Color.White, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Chip).clickable {
                val svc = el.text("action").ifEmpty { el.text("service") }
                if ('.' in svc) ctx.call(svc.substringBefore('.'), svc.substringAfter('.'), emptyList(), el.optJSONObject("data") ?: el.optJSONObject("service_data"))
                else tap?.invoke()
            }.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        "image" -> rememberImage(pictureUrl(el, ctx.states))?.let {
            val width = cssPercent(el.optJSONObject("style")?.text("width"))
            Image(it, null, mod.then(if (width != null) Modifier.width(cardWidth * width) else Modifier.size(48.dp)), contentScale = ContentScale.Fit)
        }
        else -> Unit // custom elements need their JavaScript
    }
}

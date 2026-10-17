package home.kiosk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

// The custom (HACS) cards on this household's dashboards that aren't a plain entity row:
// auto-entities, uptime-card, flex-table-card, upcoming-media-card, threedy-card, power-flow-card-plus.

private val Ok = Color(0xFF7AE582)

// ---- auto-entities: entities matched by include/exclude rules, handed to an inner card ----

/** auto-entities / HA style pattern: "light.*", "*kitchen*", or "/regex/". */
fun wildcard(pattern: String, value: String): Boolean {
    if (pattern.length > 1 && pattern.startsWith("/") && pattern.endsWith("/")) {
        return runCatching { Regex(pattern.drop(1).dropLast(1)).containsMatchIn(value) }.getOrDefault(false)
    }
    val rx = pattern.split("*").joinToString(".*") { Regex.escape(it) }
    return Regex("^$rx$", RegexOption.IGNORE_CASE).matches(value)
}

/** One auto-entities filter rule. Every key given must match; keys this app can't evaluate (area, device, integration) fail closed. */
fun autoRuleMatches(rule: JSONObject, e: Entity): Boolean = rule.keys().asSequence().filter { it != "options" && it != "type" }.all { k ->
    when (k) {
        "domain" -> wildcard(rule.text(k), e.domain)
        "entity_id" -> wildcard(rule.text(k), e.id)
        "name" -> wildcard(rule.text(k), e.name)
        "state" -> {
            val want = rule.opt(k)
            if (want is String && want.length > 1 && want[0] in "<>=!") stateFilterMatch(e, autoOp(want)) else wildcard(want.toString(), e.state)
        }
        "attributes" -> rule.optJSONObject(k)?.let { attrs -> attrs.keys().asSequence().all { a -> wildcard(attrs.optString(a), e.attributes.optString(a)) } } ?: true
        "not" -> rule.optJSONObject(k)?.let { !autoRuleMatches(it, e) } ?: true
        "or" -> rule.optJSONArray(k)?.objects()?.any { autoRuleMatches(it, e) } ?: true
        "and" -> rule.optJSONArray(k)?.objects()?.all { autoRuleMatches(it, e) } ?: true
        else -> false
    }
}

/** auto-entities' "> 20" / "<= 5" / "!= off" comparisons as a state_filter object. */
private fun autoOp(s: String): JSONObject {
    val op = listOf("<=", ">=", "!=", "==", "<", ">", "=").first { s.startsWith(it) }
    return JSONObject().put("operator", if (op == "=") "==" else op).put("value", s.removePrefix(op).trim())
}

fun autoEntities(card: JSONObject, states: Map<String, Entity>): List<JSONObject> {
    val filter = card.optJSONObject("filter") ?: JSONObject()
    val include = filter.optJSONArray("include")?.objects().orEmpty()
    val exclude = filter.optJSONArray("exclude")?.objects().orEmpty()
    val listed = entityRows(card)
    val found = include.flatMap { rule ->
        states.values.filter { autoRuleMatches(rule, it) }.map { e ->
            JSONObject((rule.optJSONObject("options") ?: JSONObject()).toString()).put("entity", e.id)
        }
    }
    val rows = (listed + found).distinctBy { it.text("entity") }.filter { row ->
        val e = states[row.text("entity")] ?: return@filter false
        exclude.none { autoRuleMatches(it, e) }
    }
    val sort = card.optJSONObject("sort") ?: return rows
    val by: (JSONObject) -> String = { row ->
        val e = states.getValue(row.text("entity"))
        when (sort.text("method")) {
            "name" -> e.name.lowercase()
            "state" -> e.state
            "last_changed", "last_updated" -> e.lastChanged
            else -> e.id
        }
    }
    val sorted = if (sort.text("method") == "state" && rows.all { states[it.text("entity")]?.state?.toDoubleOrNull() != null }) {
        rows.sortedBy { states.getValue(it.text("entity")).state.toDouble() }
    } else rows.sortedBy(by)
    return if (sort.optBoolean("reverse")) sorted.reversed() else sorted
}

// ---- uptime-card: a binary sensor's up/down history over a window, with the uptime share ----

/** Share of [segs] (by time) in an "up" state; unknown/unavailable time doesn't count either way. */
fun uptimeShare(segs: List<Segment>, up: (String) -> Boolean): Double? {
    val known = segs.filter { it.state != "unavailable" && it.state != "unknown" }
    val total = known.sumOf { it.end - it.start }.takeIf { it > 0 } ?: return null
    return known.filter { up(it.state) }.sumOf { it.end - it.start }.toDouble() / total
}

@Composable
fun UptimeCard(card: JSONObject, ctx: CardContext) {
    val ha = LocalHa.current
    val id = card.text("entity")
    val e = ctx.states[id] ?: return
    val d = card.optJSONObject("duration")
    val hours = (d?.optDouble("quantity", 1.0) ?: 1.0) * when (d?.text("unit")) { "minute" -> 1 / 60.0; "hour" -> 1.0; "week" -> 168.0; "month" -> 720.0; else -> 24.0 }
    // uptime-card's `ok` state defaults to "on" (binary sensors) and anything else is down.
    val okState = card.text("ok").ifEmpty { "on" }
    val rows = polled(id to hours, card.optLong("update_interval", 300).coerceAtLeast(60) * 1000) {
        (ha.request("history/history_during_period") {
            put("start_time", isoUtc(System.currentTimeMillis() - (hours * 3_600_000).toLong()))
            put("entity_ids", JSONArray(listOf(id))); put("minimal_response", true); put("no_attributes", true)
        } as JSONObject).optJSONArray(id)?.objects().orEmpty()
    }
    val now = System.currentTimeMillis()
    val start = now - (hours * 3_600_000).toLong()
    val segs = timeline(rows.orEmpty(), start, now)
    val up = e.state == okState
    val alias = card.optJSONObject("alias")
    Column(Modifier.fillMaxWidth().clickable { ctx.openMoreInfo(id) }.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.text("title_template").ifEmpty { card.text("name") }.ifEmpty { e.name }, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(alias?.text(if (up) "ok" else "ko")?.takeIf { it.isNotEmpty() } ?: if (up) "Up" else "Down", color = if (up) Ok else Danger, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(Chip)) {
            val span = (now - start).toFloat()
            segs.forEach { s ->
                val c = when (s.state) { okState -> Ok; "unavailable", "unknown" -> Color.Transparent; else -> Danger }
                drawRect(c, Offset((s.start - start) / span * size.width, 0f), Size((s.end - s.start) / span * size.width, size.height))
            }
        }
        uptimeShare(segs) { it == okState }?.let {
            Text(String.format(Locale.US, "%.2f%% up", it * 100), color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ---- flex-table-card: columns read from state, attributes, or a list inside an attribute ----

/** The table's rows: one per entity, or one per item when a column reads "Attr.field" from a list attribute. */
fun flexRows(card: JSONObject, states: Map<String, Entity>): List<List<String>> {
    val cols = card.optJSONArray("columns")?.objects().orEmpty()
    return entityRows(card).mapNotNull { states[it.text("entity")] }.flatMap { e ->
        // A dotted `data` path into a list attribute (e.g. "Leases.hostname") expands that list into rows.
        val listAttr = cols.firstNotNullOfOrNull { c -> c.text("data").substringBefore('.', "").takeIf { it.isNotEmpty() && e.attributes.optJSONArray(it) != null } }
        val items: List<JSONObject?> = listAttr?.let { e.attributes.optJSONArray(it)?.objects() } ?: listOf(null)
        items.map { item ->
            cols.map { c ->
                val data = c.text("data").ifEmpty { c.text("attr") }
                val v: Any? = when {
                    c.text("prop") == "state" -> e.state
                    c.text("prop") == "name" -> e.name
                    c.text("prop") == "entity_id" -> e.id
                    item != null && data.startsWith("$listAttr.") -> item.opt(data.removePrefix("$listAttr."))
                    data == "state" -> e.state
                    data.isNotEmpty() -> e.attributes.opt(data)
                    else -> e.state
                }
                if (v == null || v == JSONObject.NULL) "" else (v as? String)?.let { s -> if (s.length > 18 && s[4] == '-' && s[10] == 'T') timeText(s) else s } ?: v.toString()
            }
        }
    }
}

@Composable
fun FlexTableCard(card: JSONObject, ctx: CardContext) {
    val cols = card.optJSONArray("columns")?.objects().orEmpty().filter { !it.optBoolean("hidden") }
    val rows = flexRows(card, ctx.states)
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        CardTitle(card.text("title"), Modifier.padding(bottom = 8.dp))
        Column(Modifier.horizontalScroll(scroll)) {
            Row { cols.forEach { Text(it.text("name"), color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(130.dp).padding(end = 8.dp, bottom = 4.dp)) } }
            rows.forEach { r ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    r.take(cols.size).forEach { Text(it, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(130.dp).padding(end = 8.dp)) }
                }
            }
        }
    }
}

// ---- upcoming-media-card: Sonarr/Radarr/Plex sensors whose `data` attribute lists items ----

/** The card's lines for one item: `$field` placeholders from the defaults row, with $day/$time/$date from airdate. */
fun upcomingLines(defaults: JSONObject, item: JSONObject): List<String> {
    val air = parseIso(item.text("airdate"))
    // Fields first: a field's value can itself hold $day/$time (Sonarr's "release" is "$day, $time").
    fun fill(template: String): String {
        var out = Regex("""\$([a-z_]+)""").replace(template) { m -> if (m.groupValues[1] in setOf("day", "time", "date")) m.value else item.text(m.groupValues[1]) }
        if (air != null) {
            out = out.replace("\$day", SimpleDateFormat("EEE", Locale.getDefault()).format(air))
                .replace("\$time", SimpleDateFormat("h:mm a", Locale.getDefault()).format(air))
                .replace("\$date", SimpleDateFormat("MMM d", Locale.getDefault()).format(air))
        }
        return out
    }
    return listOf("title_default", "line1_default", "line2_default", "line3_default", "line4_default")
        .map { item.text(it.removeSuffix("_default")).takeIf { v -> v.isNotEmpty() && it != "title_default" && it != "line1_default" } ?: fill(defaults.text(it)) }
        .map { line -> line.trim().trim('-').trim() }
}

@Composable
fun UpcomingMediaCard(card: JSONObject, ctx: CardContext) {
    val e = ctx.states[card.text("entity")] ?: return
    val data = e.attributes.optJSONArray("data")?.objects().orEmpty()
    val defaults = data.firstOrNull()?.takeIf { it.has("title_default") } ?: JSONObject()
    val items = data.filter { !it.has("title_default") }.take(card.optInt("max", 10))
    val fanart = card.text("image_style") == "fanart"
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle(card.text("title"))
        if (items.isEmpty()) Text("Nothing upcoming", color = TextDim, fontSize = 13.sp)
        items.forEach { item ->
            val lines = upcomingLines(defaults, item)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Artwork is on the internet (TMDB); tablets without internet just show the text.
                rememberImage(item.text(if (fanart) "fanart" else "poster").takeIf { it.startsWith("http") })?.let {
                    Image(it, null, Modifier.width(if (fanart) 120.dp else 60.dp).aspectRatio(if (fanart) 16f / 9f else 2f / 3f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(lines[0], color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    lines.drop(1).filter { it.isNotEmpty() }.take(3).forEach { Text(it, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

// ---- threedy-card: a 3D printer's progress and monitored sensors ----

@Composable
fun ThreedyCard(card: JSONObject, ctx: CardContext) {
    val sensors = card.optJSONObject("sensors") ?: JSONObject()
    fun entity(key: String) = sensors.optJSONObject(key)?.text("entity")?.let { ctx.states[it] }
    val progress = entity("Progress")?.state?.toDoubleOrNull()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle(card.text("name").ifEmpty { "3D printer" }, Modifier.weight(1f))
            ctx.states[card.text("light_entity")]?.let { l ->
                RoundButton(entityIcon(l), bg = if (l.state == "on") entityAccent(l) else Chip, tint = if (l.state == "on") CardBg else Color.White) {
                    ctx.call(l.domain, "toggle", listOf(l.id), null)
                }
            }
        }
        if (progress != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Chip)) {
                    Box(Modifier.fillMaxWidth((progress / 100).toFloat().coerceIn(0f, 1f)).height(8.dp).background(Ok))
                }
                Text("  ${num(progress)}%", color = Color.White, fontSize = 13.sp)
            }
        }
        val monitored = card.optJSONArray("monitored")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: sensors.keys().asSequence().toList()
        monitored.forEach { key ->
            entity(key)?.let { e ->
                Row(Modifier.clickable { ctx.openMoreInfo(e.id) }) {
                    Text(key, color = TextDim, fontSize = 13.sp, modifier = Modifier.width(90.dp))
                    Text(stateText(e).ifEmpty { e.state }, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---- power-flow-card-plus: live power per source, as rows (the animated flow diagram is decoration) ----

/** The card's (label, entity) pairs: grid consumption/production, solar, battery, home, fossil share, individual devices. */
fun powerFlowEntities(card: JSONObject): List<Pair<String, String>> {
    val ents = card.optJSONObject("entities") ?: return emptyList()
    val out = mutableListOf<Pair<String, String>>()
    for (key in listOf("home", "grid", "solar", "battery", "fossil_fuel_percentage")) {
        val o = ents.optJSONObject(key) ?: continue
        val label = o.text("name").ifEmpty { key.replace('_', ' ').replaceFirstChar { it.uppercase() } }
        when (val en = o.opt("entity")) {
            is String -> out += label to en
            is JSONObject -> {
                en.text("consumption").takeIf { it.isNotEmpty() }?.let { out += "$label in" to it }
                en.text("production").takeIf { it.isNotEmpty() }?.let { out += "$label out" to it }
            }
        }
    }
    ents.optJSONArray("individual")?.objects()?.forEach { o -> o.text("entity").takeIf { it.isNotEmpty() }?.let { out += o.text("name").ifEmpty { it } to it } }
    return out
}

@Composable
fun PowerFlowCard(card: JSONObject, ctx: CardContext) {
    val rows = powerFlowEntities(card).filter { ctx.states.containsKey(it.second) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        CardTitle(card.text("title"), Modifier.padding(start = 16.dp, bottom = 4.dp))
        rows.forEach { (label, id) -> EntityCard(JSONObject().put("entity", id).put("name", label), ctx) }
    }
}

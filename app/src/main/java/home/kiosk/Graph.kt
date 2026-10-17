package home.kiosk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Android optString turns JSON null into "null"; see the shared text() helper in #4. */
private fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key)

private val PALETTE = listOf(Color(0xFFFFB74D), Color(0xFF4CC9F0), Color(0xFF7AE582), Color(0xFFFF6F61), Color(0xFFBA68C8))

/** mini-graph-card, or HA's sensor card with `graph:` set. A plain sensor card stays an entity row. */
fun isGraphCard(card: JSONObject) = when (card.optString("type")) {
    "custom:mini-graph-card" -> true
    "sensor" -> card.has("graph") && card.str("graph") != "none"
    else -> false
}

data class GraphLine(val id: String, val name: String?, val color: Color)

fun graphLines(card: JSONObject): List<GraphLine> {
    val arr = card.optJSONArray("entities")
    val raw = if (arr != null) (0 until arr.length()).map { arr.opt(it) } else listOf(card.optString("entity"))
    return raw.mapIndexedNotNull { i, x ->
        val o = x as? JSONObject
        val id = o?.str("entity") ?: x?.toString() ?: return@mapIndexedNotNull null
        if (id.isEmpty()) return@mapIndexedNotNull null
        val color = (o?.str("color") ?: card.str("line_color")).takeIf { it.startsWith("#") }
            ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        GraphLine(id, o?.str("name")?.takeIf { it.isNotEmpty() }, color ?: PALETTE[i % PALETTE.size])
    }
}

/** HA's minimal history rows ({s, lu}) → numeric (epoch ms, value) points; non-numeric states are skipped. */
fun historyPoints(rows: List<JSONObject>): List<Pair<Long, Double>> = rows.mapNotNull { r ->
    val v = r.str("s").toDoubleOrNull() ?: return@mapNotNull null
    val t = r.optDouble("lu").takeUnless { it.isNaN() } ?: return@mapNotNull null
    (t * 1000).toLong() to v
}

/** HA long-term statistics rows ({start: epoch ms, mean, state}) → (epoch ms, value). Mean when present. */
fun statisticsPoints(rows: List<JSONObject>, key: String? = null): List<Pair<Long, Double>> = rows.mapNotNull { r ->
    val v = (if (key != null) r.optDouble(key) else r.optDouble("mean").takeUnless { it.isNaN() } ?: r.optDouble("state")).takeUnless { it.isNaN() } ?: return@mapNotNull null
    val t = r.optDouble("start").takeUnless { it.isNaN() } ?: return@mapNotNull null
    // Older HA sent seconds; current sends milliseconds.
    (if (t < 1e11) t * 1000 else t).toLong() to v
}

/**
 * Averages points into fixed buckets between [start] and [end]. Empty buckets repeat the last value
 * (sensors only report changes), so a flat sensor draws a flat line rather than gaps.
 */
fun bucketize(points: List<Pair<Long, Double>>, start: Long, end: Long, buckets: Int): List<Double?> {
    if (buckets <= 0 || end <= start) return emptyList()
    val width = (end - start).toDouble() / buckets
    val sums = DoubleArray(buckets)
    val counts = IntArray(buckets)
    var last: Double? = points.lastOrNull { it.first < start }?.second
    for ((t, v) in points) {
        if (t < start) continue
        val i = ((t - start) / width).toInt().coerceAtMost(buckets - 1)
        sums[i] += v; counts[i]++
    }
    return (0 until buckets).map { i ->
        if (counts[i] > 0) (sums[i] / counts[i]).also { last = it } else last
    }
}

fun isoUtc(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraphCard(card: JSONObject, ctx: CardContext) {
    val ha = LocalHa.current
    val lines = graphLines(card)
    if (lines.isEmpty()) return
    val hours = card.optDouble("hours_to_show", 24.0).coerceIn(1.0, 24.0 * 7)
    // sensor card: detail 1 = hourly, 2 = every 10 min. mini-graph: points_per_hour (default 0.5).
    val perHour = when {
        card.optString("type") == "sensor" -> if (card.optInt("detail", 1) >= 2) 6.0 else 1.0
        else -> card.optDouble("points_per_hour", 0.5)
    }
    val buckets = (hours * perHour).toInt().coerceIn(12, 200)

    // Once, then every 10 minutes; the newest point comes from live state below.
    // Long-term statistics first: pre-averaged by HA, ~25x smaller than raw history (1.3 MB → 54 KB for
    // a day of energy sensors), which matters because a big reply blocks the socket while it's parsed.
    // Raw history only for sensors without statistics (no state_class).
    val period = if (perHour > 1) "5minute" else "hour"
    val history = produceState<Map<String, List<Pair<Long, Double>>>?>(null, lines.map { it.id }, hours) {
        while (true) {
            val end = System.currentTimeMillis()
            val from = isoUtc(end - (hours * 3_600_000).toLong())
            value = runCatching {
                val ids = lines.map { it.id }
                val stats = ha.request("recorder/statistics_during_period") {
                    put("start_time", from); put("statistic_ids", org.json.JSONArray(ids))
                    put("period", period); put("types", org.json.JSONArray(listOf("mean", "state")))
                } as? JSONObject ?: JSONObject()
                val got = withContext(Dispatchers.Default) {
                    ids.associateWith { statisticsPoints(stats.optJSONArray(it)?.objects().orEmpty()) }.filterValues { it.isNotEmpty() }
                }
                val missing = ids - got.keys
                val raw = if (missing.isEmpty()) emptyMap() else {
                    val r = ha.request("history/history_during_period") {
                        put("start_time", from); put("entity_ids", org.json.JSONArray(missing))
                        put("minimal_response", true); put("no_attributes", true)
                    } as JSONObject
                    withContext(Dispatchers.Default) { missing.associateWith { historyPoints(r.optJSONArray(it)?.objects().orEmpty()) } }
                }
                got + raw
            }.getOrNull() ?: value
            delay(10 * 60_000L)
        }
    }.value

    val now = System.currentTimeMillis()
    val start = now - (hours * 3_600_000).toLong()
    val series = lines.map { l ->
        val live = ctx.states[l.id]?.state?.toDoubleOrNull()?.let { listOf(now to it) }.orEmpty()
        bucketize(history?.get(l.id).orEmpty() + live, start, now, buckets)
    }
    val first = ctx.states[lines[0].id]
    val title = card.str("name").ifEmpty { lines[0].name ?: first?.name ?: lines[0].id }
    val tap = ctx.runner(tapAct(card, lines[0].id), lines[0].id) ?: { ctx.openMoreInfo(lines[0].id) }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg).clickable(onClick = tap).padding(20.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, color = TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (lines.size == 1) first?.let { Text(stateText(it), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light) }
        }
        if (lines.size > 1) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                lines.forEach { l ->
                    val e = ctx.states[l.id]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(l.color))
                        Spacer(Modifier.width(6.dp))
                        Text("${l.name ?: e?.name ?: l.id} ${e?.let(::stateText).orEmpty()}", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (history == null) Box(Modifier.fillMaxWidth().height(80.dp))
        else Sparklines(series, lines.map { it.color }, fill = lines.size == 1, modifier = Modifier.fillMaxWidth().height(80.dp))
    }
}

@Composable
fun Sparklines(series: List<List<Double?>>, colors: List<Color>, fill: Boolean, modifier: Modifier) {
    val all = series.flatten().filterNotNull()
    if (all.isEmpty()) return
    val min = all.min()
    val span = (all.max() - min).takeIf { it > 0 } ?: 1.0
    Canvas(modifier) {
        series.forEachIndexed { s, values ->
            val step = size.width / (values.size - 1).coerceAtLeast(1)
            val pts = values.mapIndexedNotNull { i, v -> v?.let { Offset(i * step, size.height - ((it - min) / span * size.height).toFloat()) } }
            if (pts.size < 2) return@forEachIndexed
            val line = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            if (fill) {
                val area = Path().apply { addPath(line); lineTo(pts.last().x, size.height); lineTo(pts[0].x, size.height); close() }
                drawPath(area, Brush.verticalGradient(listOf(colors[s].copy(alpha = 0.35f), Color.Transparent)))
            }
            drawPath(line, colors[s], style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// ---- history-graph: lines for sensors with a unit, a coloured timeline for everything else (as HA draws it) ----

data class Segment(val start: Long, val end: Long, val state: String)

/** Minimal history rows ({s, lu}) → contiguous state segments clipped to [start, end]. */
fun timeline(rows: List<JSONObject>, start: Long, end: Long): List<Segment> {
    val pts = rows.mapNotNull { r -> r.optDouble("lu").takeUnless { it.isNaN() }?.let { (it * 1000).toLong().coerceAtLeast(start) to r.str("s") } }
    return pts.mapIndexedNotNull { i, (t, st) ->
        val next = pts.getOrNull(i + 1)?.first ?: end
        if (next <= t || t >= end) null else Segment(t, next.coerceAtMost(end), st)
    }
}

private val TIMELINE_PALETTE = listOf(Color(0xFF4CC9F0), Color(0xFF7AE582), Color(0xFFBA68C8), Color(0xFFFF6F61), Color(0xFF4DB6AC), Color(0xFFF06292))

/** On-ish states amber, off-ish grey, unavailable invisible, anything else a stable colour per state text. */
fun timelineColor(state: String): Color = when (state) {
    "on", "home", "playing", "open", "unlocked", "heat", "cool", "detected" -> Color(0xFFFFB74D)
    "off", "not_home", "idle", "closed", "locked", "standby", "paused", "clear" -> Chip
    "unavailable", "unknown", "" -> Color.Transparent
    else -> TIMELINE_PALETTE[Math.floorMod(state.hashCode(), TIMELINE_PALETTE.size)]
}

@Composable
private fun <T> polled(key: Any, everyMs: Long, fetch: suspend () -> T?): T? = produceState<T?>(null, key) {
    while (true) {
        runCatching { fetch() }.getOrNull()?.let { value = it }
        delay(everyMs)
    }
}.value

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryGraphCard(card: JSONObject, ctx: CardContext) {
    val ha = LocalHa.current
    val rows = entityRows(card)
    if (rows.isEmpty()) return
    val ids = rows.map { it.str("entity") }
    val hours = card.optDouble("hours_to_show", 24.0).coerceIn(1.0, 24.0 * 7)
    val history = polled(ids to hours, 5 * 60_000L) {
        val r = ha.request("history/history_during_period") {
            put("start_time", isoUtc(System.currentTimeMillis() - (hours * 3_600_000).toLong()))
            put("entity_ids", org.json.JSONArray(ids)); put("minimal_response", true); put("no_attributes", true)
        } as JSONObject
        withContext(Dispatchers.Default) { ids.associateWith { r.optJSONArray(it)?.objects().orEmpty() } }
    }
    val now = System.currentTimeMillis()
    val start = now - (hours * 3_600_000).toLong()
    // HA's rule: a unit of measurement means a line; no unit means a timeline.
    val (lineIds, barIds) = ids.partition { ctx.states[it]?.attributes?.text("unit_of_measurement").orEmpty().isNotEmpty() }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card.str("title").takeIf { it.isNotEmpty() }?.let { Text(it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        if (lineIds.isNotEmpty()) {
            val colors = lineIds.indices.map { PALETTE[it % PALETTE.size] }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                lineIds.forEachIndexed { i, id ->
                    val e = ctx.states[id]
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ctx.openMoreInfo(id) }) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colors[i]))
                        Spacer(Modifier.width(6.dp))
                        Text("${rows[ids.indexOf(id)].str("name").ifEmpty { e?.name ?: id }} ${e?.let(::stateText).orEmpty()}", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            val series = lineIds.map { id ->
                val live = ctx.states[id]?.state?.toDoubleOrNull()?.let { listOf(now to it) }.orEmpty()
                bucketize(historyPoints(history?.get(id).orEmpty()) + live, start, now, 120)
            }
            if (history == null) Box(Modifier.fillMaxWidth().height(100.dp))
            else Sparklines(series, colors, fill = lineIds.size == 1, modifier = Modifier.fillMaxWidth().height(100.dp))
        }
        barIds.forEach { id ->
            val e = ctx.states[id]
            val segs = timeline(history?.get(id).orEmpty(), start, now)
            Column(Modifier.clickable { ctx.openMoreInfo(id) }) {
                Row {
                    Text(rows[ids.indexOf(id)].str("name").ifEmpty { e?.name ?: id }, color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    e?.let { Text(stateText(it).ifEmpty { it.state }, color = Color.White, fontSize = 12.sp, maxLines = 1) }
                }
                Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).background(CardBg)) {
                    val span = (now - start).toFloat()
                    segs.forEach { s ->
                        val x = (s.start - start) / span * size.width
                        drawRect(timelineColor(s.state), Offset(x, 0f), androidx.compose.ui.geometry.Size((s.end - s.start) / span * size.width, size.height))
                    }
                }
            }
        }
    }
}

// ---- statistics-graph: long-term statistics, as lines (mean/state) or bars (change) ----

/** Which statistic a statistics-graph draws for an entity: the first configured type the data has. */
fun pickStatType(card: JSONObject, rows: List<JSONObject>): String? {
    val wanted = when (val t = card.opt("stat_types")) {
        is String -> listOf(t)
        is org.json.JSONArray -> (0 until t.length()).map { t.optString(it) }
        else -> listOf("mean", "change", "state", "sum", "max", "min")
    }
    return wanted.firstOrNull { k -> rows.any { !it.optDouble(k).isNaN() } }
}

@Composable
fun StatisticsGraphCard(card: JSONObject, ctx: CardContext) {
    val ha = LocalHa.current
    val rows = entityRows(card)
    if (rows.isEmpty()) return
    val ids = rows.map { it.str("entity") }
    val days = card.optDouble("days_to_show", 30.0).coerceIn(1.0, 365.0)
    val period = card.str("period").takeIf { it in setOf("5minute", "hour", "day", "week", "month") } ?: if (days <= 7) "hour" else "day"
    val stats = polled(ids to days, 15 * 60_000L) {
        ha.request("recorder/statistics_during_period") {
            put("start_time", isoUtc(System.currentTimeMillis() - (days * 86_400_000).toLong())); put("statistic_ids", org.json.JSONArray(ids))
            put("period", period); put("types", org.json.JSONArray(listOf("mean", "min", "max", "state", "sum", "change")))
        } as? JSONObject
    }
    val now = System.currentTimeMillis()
    val start = now - (days * 86_400_000).toLong()
    val buckets = when (period) { "5minute" -> 200; "hour" -> (days * 24).toInt(); "day" -> days.toInt(); "week" -> (days / 7).toInt(); else -> (days / 30).toInt() }.coerceIn(2, 200)
    val colors = ids.indices.map { PALETTE[it % PALETTE.size] }
    val series = ids.map { id ->
        val r = stats?.optJSONArray(id)?.objects().orEmpty()
        val type = pickStatType(card, r)
        type to bucketize(statisticsPoints(r, type), start, now, buckets)
    }
    val bars = card.str("chart_type") == "bar" || (card.str("chart_type").isEmpty() && series.all { it.first == "change" })

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card.str("title").takeIf { it.isNotEmpty() }?.let { Text(it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ids.forEachIndexed { i, id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors[i]))
                    Spacer(Modifier.width(6.dp))
                    Text(rows[i].str("name").ifEmpty { ctx.states[id]?.name ?: id }, color = Color.White, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        if (stats == null) Box(Modifier.fillMaxWidth().height(110.dp))
        else if (bars) Bars(series.map { it.second }, colors, Modifier.fillMaxWidth().height(110.dp))
        else Sparklines(series.map { it.second }, colors, fill = ids.size == 1, modifier = Modifier.fillMaxWidth().height(110.dp))
    }
}

@Composable
private fun Bars(series: List<List<Double?>>, colors: List<Color>, modifier: Modifier) {
    val top = series.flatten().filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: return
    Canvas(modifier) {
        val n = series.firstOrNull()?.size ?: return@Canvas
        val slot = size.width / n
        val w = slot / series.size * 0.8f
        series.forEachIndexed { s, values ->
            values.forEachIndexed { i, v ->
                if (v == null || v <= 0) return@forEachIndexed
                val h = (v / top * size.height).toFloat()
                drawRect(colors[s], Offset(i * slot + s * w, size.height - h), androidx.compose.ui.geometry.Size(w, h))
            }
        }
    }
}

// ---- distribution: one stacked bar of the entities' shares ----

/** Each entity's share of the positive total, in order; non-numbers and negatives count as 0. */
fun distribution(values: List<Double?>): List<Float> {
    val clean = values.map { v -> v?.takeIf { it > 0 } ?: 0.0 }
    val total = clean.sum().takeIf { it > 0 } ?: return clean.map { 0f }
    return clean.map { (it / total).toFloat() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DistributionCard(card: JSONObject, ctx: CardContext) {
    val rows = entityRows(card)
    val es = rows.map { ctx.states[it.str("entity")] }
    val shares = distribution(es.map { it?.state?.toDoubleOrNull() })
    val colors = rows.mapIndexed { i, r -> mushroomColor(r.str("color")) ?: PALETTE[i % PALETTE.size] }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        card.str("title").takeIf { it.isNotEmpty() }?.let { Text(it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Chip)) {
            shares.forEachIndexed { i, f -> if (f > 0f) Box(Modifier.weight(f).fillMaxHeight().background(colors[i])) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            rows.forEachIndexed { i, r ->
                val e = es[i] ?: return@forEachIndexed
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ctx.openMoreInfo(e.id) }) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors[i]))
                    Spacer(Modifier.width(6.dp))
                    Text("${r.str("name").ifEmpty { e.name }} ${stateText(e)}", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

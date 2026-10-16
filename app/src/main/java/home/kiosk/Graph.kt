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
fun statisticsPoints(rows: List<JSONObject>): List<Pair<Long, Double>> = rows.mapNotNull { r ->
    val v = r.optDouble("mean").takeUnless { it.isNaN() } ?: r.optDouble("state").takeUnless { it.isNaN() } ?: return@mapNotNull null
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

private fun isoUtc(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

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
private fun Sparklines(series: List<List<Double?>>, colors: List<Color>, fill: Boolean, modifier: Modifier) {
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

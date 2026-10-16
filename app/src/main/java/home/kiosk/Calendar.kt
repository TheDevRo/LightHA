package home.kiosk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

val CALENDAR_CARDS = setOf("custom:calendar-card-pro", "calendar")

fun isCalendarCard(card: JSONObject) = card.optString("type") in CALENDAR_CARDS

data class CalEvent(val title: String, val start: Date, val end: Date?, val allDay: Boolean, val location: String, val color: Color)

private val PALETTE = listOf(Color(0xFFF0A030), Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFE57373), Color(0xFFBA68C8))

/** One calendar's events from HA's REST `/api/calendars/<entity>`. All-day dates are local midnight. */
fun parseEvents(arr: JSONArray, color: Color): List<CalEvent> = arr.objects().mapNotNull { e ->
    val s = e.optJSONObject("start") ?: return@mapNotNull null
    val allDay = s.has("date")
    val start = (if (allDay) localDate(s.getString("date")) else parseIso(s.optString("dateTime"))) ?: return@mapNotNull null
    val en = e.optJSONObject("end")
    val end = en?.let { if (it.has("date")) localDate(it.getString("date")) else parseIso(it.optString("dateTime")) }
    CalEvent(e.str("summary").trim().ifEmpty { "(no title)" }, start, end, allDay, e.str("location").trim(), color)
}

/** Android's optString turns JSON null into the text "null"; HA sends null for empty fields. */
private fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key)

private fun localDate(s: String): Date? = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s) }.getOrNull()

/** Events grouped under "Today" / "Tomorrow" / "Sat, Oct 17"; all-day first within a day. */
fun groupByDay(events: List<CalEvent>, now: Date = Date()): List<Pair<String, List<CalEvent>>> {
    val key = SimpleDateFormat("yyyyMMdd", Locale.US)
    val today = key.format(now)
    val tomorrow = key.format(Date(now.time + 86_400_000L))
    return events
        .filter { (it.end ?: it.start).after(now) || key.format(it.start) == today }
        .sortedWith(compareBy({ key.format(it.start) }, { !it.allDay }, { it.start }))
        .groupBy { key.format(it.start) }
        .map { (k, list) ->
            val label = when (k) {
                today -> "Today"
                tomorrow -> "Tomorrow"
                else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(list.first().start)
            }
            label to list
        }
}

private fun isoUtc(d: Date) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)

@Composable
fun CalendarCard(card: JSONObject, ctx: CardContext) {
    val ha = LocalHa.current
    val ids = card.optJSONArray("entities")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("entity") ?: arr.optString(i) }.filter { it.isNotEmpty() }
    } ?: listOfNotNull(card.optString("entity").takeIf { it.isNotEmpty() })
    val days = card.optInt("days_to_show", 7).coerceIn(1, 31)
    val accent = card.optString("accent_color").takeIf { it.startsWith("#") }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
    // Refetch when any calendar's state flips (an event starting/ending) and every 5 minutes.
    val stamp = ids.joinToString { ctx.states[it]?.state.orEmpty() }

    val events = produceState<List<CalEvent>?>(null, ids, stamp, days) {
        while (true) {
            val now = Date()
            val start = Calendar.getInstance().apply { time = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.time
            val end = Date(start.time + days * 86_400_000L)
            value = ids.flatMapIndexed { i, id ->
                runCatching {
                    parseEvents(ha.getJson("/api/calendars/$id?start=${isoUtc(start)}&end=${isoUtc(end)}") as JSONArray, if (i == 0 && accent != null) accent else PALETTE[i % PALETTE.size])
                }.getOrDefault(emptyList())
            }
            delay(5 * 60_000L)
        }
    }.value

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        card.optString("title").takeIf { it.isNotEmpty() }?.let { Text(it, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        when {
            events == null -> Text("Loading calendar…", color = TextDim, fontSize = 14.sp)
            events.isEmpty() -> Text("Nothing in the next $days days", color = TextDim, fontSize = 14.sp)
            else -> groupByDay(events).forEach { (day, list) ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(day.uppercase(), color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                    list.forEach { EventRow(it) }
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: CalEvent) {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault())
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(e.color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(
                    if (e.allDay) "All day" else time.format(e.start) + (e.end?.let { " – " + time.format(it) } ?: ""),
                    e.location,
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

package home.kiosk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

val CALENDAR_CARDS = setOf("custom:calendar-card-pro", "calendar", "custom:atomic-calendar-revive")

fun isCalendarCard(card: JSONObject) = card.optString("type") in CALENDAR_CARDS

/** Month grid for HA's calendar card in dayGridMonth and atomic-calendar-revive's Calendar mode; otherwise the day list. */
fun isMonthView(card: JSONObject) = when (card.optString("type")) {
    "calendar" -> card.optString("initial_view", "dayGridMonth") == "dayGridMonth"
    "custom:atomic-calendar-revive" -> card.optString("defaultMode") == "Calendar"
    else -> false
}

/** The month containing [month] as weeks of 7 days, Sunday-first unless the locale says otherwise; null pads the edges. */
fun monthCells(month: Date, firstDay: Int = Calendar.getInstance().firstDayOfWeek): List<Date?> {
    val c = Calendar.getInstance().apply { time = month; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val lead = (c.get(Calendar.DAY_OF_WEEK) - firstDay + 7) % 7
    val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cells = MutableList<Date?>(lead) { null }
    repeat(days) { cells += c.time; c.add(Calendar.DAY_OF_MONTH, 1) }
    while (cells.size % 7 != 0) cells += null
    return cells
}

/** A CSS size like "20px" as sp, or [def] (calendar-card-pro's event_font_size, time_font_size...). */
fun cssSp(v: String, def: Float) = v.trim().removeSuffix("px").toFloatOrNull()?.takeIf { it > 0 } ?: def

private val DAY_KEY get() = SimpleDateFormat("yyyyMMdd", Locale.US)

data class CalEvent(val title: String, val start: Date, val end: Date?, val allDay: Boolean, val location: String, val color: Color)

private val PALETTE = listOf(Color(0xFFF0A030), Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFE57373), Color(0xFFBA68C8))

/** One calendar's events from HA's REST `/api/calendars/<entity>`. All-day dates are local midnight. */
fun parseEvents(arr: JSONArray, color: Color): List<CalEvent> = arr.objects().mapNotNull { e ->
    val s = e.optJSONObject("start") ?: return@mapNotNull null
    val allDay = s.has("date")
    val start = (if (allDay) localDate(s.getString("date")) else parseIso(s.optString("dateTime"))) ?: return@mapNotNull null
    val en = e.optJSONObject("end")
    val end = en?.let { if (it.has("date")) localDate(it.getString("date")) else parseIso(it.optString("dateTime")) }
    CalEvent(e.text("summary").trim().ifEmpty { "(no title)" }, start, end, allDay, e.text("location").trim(), color)
}


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
    val month = isMonthView(card)
    val days = (card.optInt("days_to_show", 0).takeIf { it > 0 } ?: card.optInt("maxDaysToShow", 7)).coerceIn(1, 31)
    val eventSp = cssSp(card.optString("event_font_size"), 15f)
    val timeSp = cssSp(card.optString("time_font_size"), 13f)
    val accent = card.optString("accent_color").takeIf { it.startsWith("#") }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
    // Refetch when any calendar's state flips (an event starting/ending) and every 5 minutes.
    val stamp = ids.joinToString { ctx.states[it]?.state.orEmpty() }

    val events = polled(listOf(ids, stamp, days, month), 5 * 60_000L) {
        val now = Date()
        val cells = monthCells(now)
        val start = if (month) cells.first { it != null }!! else Calendar.getInstance().apply { time = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.time
        val end = if (month) Date(cells.last { it != null }!!.time + 86_400_000L) else Date(start.time + days * 86_400_000L)
        ids.flatMapIndexed { i, id ->
            runCatching {
                parseEvents(ha.getJson("/api/calendars/$id?start=${isoUtc(start)}&end=${isoUtc(end)}") as JSONArray, if (i == 0 && accent != null) accent else PALETTE[i % PALETTE.size])
            }.getOrDefault(emptyList())
        }
    }

    // calendar-card-pro's per-day weather (position "date", its default): icon + high/low beside each day.
    val weather = card.optJSONObject("weather")
    val weatherId = weather?.optString("entity").orEmpty()
    val showWeather = weatherId.isNotEmpty() && weather?.optString("position", "date") in setOf("date", "both")
    val showLow = weather?.optJSONObject("date")?.optBoolean("show_low_temp", false) ?: false
    val forecast = if (showWeather) rememberSubscription(weatherId, "weather/subscribe_forecast", {
        put("entity_id", weatherId); put("forecast_type", "daily")
    }) { f -> parseForecast(f).associateBy { DAY_KEY.format(it.time) } } else null

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        (card.optString("title").ifEmpty { card.optString("name") }).takeIf { it.isNotEmpty() }
            ?.let { Text(it, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        when {
            events == null -> Text("Loading calendar…", color = TextDim, fontSize = 14.sp)
            month -> MonthGrid(events, eventSp, timeSp)
            events.isEmpty() -> Text("Nothing in the next $days days", color = TextDim, fontSize = 14.sp)
            else -> groupByDay(events).forEach { (day, list) ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(day.uppercase(), color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                        forecast?.get(DAY_KEY.format(list.first().start))?.let { f ->
                            Icon(weatherIcon(f.condition), null, tint = Color(0xFFFFC14D), modifier = Modifier.size(16.dp))
                            Text(
                                listOfNotNull(f.temp, f.low?.takeIf { showLow }).joinToString(" / ") { "${it.roundToInt()}°" },
                                color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    list.forEach { EventRow(it, eventSp, timeSp) }
                }
            }
        }
    }
}

/** This month as a 7-column grid with a dot per event; tap a day to list its events under the grid. */
@Composable
private fun MonthGrid(events: List<CalEvent>, eventSp: Float, timeSp: Float) {
    val now = Date()
    val todayKey = DAY_KEY.format(now)
    val byDay = remember(events) { events.groupBy { DAY_KEY.format(it.start) } }
    var picked by remember { mutableStateOf(todayKey) }
    val cells = remember(todayKey) { monthCells(now) }
    Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Row {
        val wd = SimpleDateFormat("EEEEE", Locale.getDefault())
        cells.take(7).indices.forEach { i ->
            // Weekday initials from the first full week, so they follow the locale's first day.
            val d = cells.drop(7).getOrNull(i) ?: cells[i]
            Text(d?.let { wd.format(it) }.orEmpty(), Modifier.weight(1f), color = TextDim, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
    cells.chunked(7).forEach { week ->
        Row {
            week.forEach { d ->
                val key = d?.let { DAY_KEY.format(it) }
                val dayEvents = key?.let { byDay[it] }.orEmpty()
                Column(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (key != null && key == picked) Chip else Color.Transparent)
                        .then(if (key != null) Modifier.clickable { picked = key } else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    if (d != null) {
                        Text(
                            SimpleDateFormat("d", Locale.US).format(d), fontSize = 15.sp,
                            color = if (key == todayKey) Amber else Color.White, fontWeight = if (key == todayKey) FontWeight.Bold else FontWeight.Normal,
                        )
                        Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            dayEvents.take(3).forEach { Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(it.color)) }
                        }
                    }
                }
            }
        }
    }
    val list = byDay[picked].orEmpty().sortedWith(compareBy({ !it.allDay }, { it.start }))
    if (list.isEmpty()) Text("Nothing on this day", color = TextDim, fontSize = 14.sp)
    else list.forEach { EventRow(it, eventSp, timeSp) }
}

@Composable
private fun EventRow(e: CalEvent, eventSp: Float, timeSp: Float) {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault())
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(e.color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Color.White, fontSize = eventSp.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(
                    if (e.allDay) "All day" else time.format(e.start) + (e.end?.let { " – " + time.format(it) } ?: ""),
                    e.location,
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                color = TextDim, fontSize = timeSp.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

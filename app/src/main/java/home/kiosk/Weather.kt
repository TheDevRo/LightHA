package home.kiosk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FilterDrama
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Sun = Color(0xFFFFC14D)

val WEATHER_CARDS = setOf("custom:weather-forecast-card", "weather-forecast")

fun isWeatherCard(card: JSONObject) = card.optString("type") in WEATHER_CARDS

data class ForecastItem(val time: Date, val condition: String, val temp: Double?, val low: Double?, val rainChance: Int?)

fun parseForecast(event: JSONObject): List<ForecastItem> {
    val arr = event.optJSONArray("forecast") ?: JSONArray()
    return arr.objects().mapNotNull { f ->
        val t = parseIso(f.optString("datetime")) ?: return@mapNotNull null
        ForecastItem(
            t, f.optString("condition"),
            f.optDouble("temperature").takeUnless { it.isNaN() },
            f.optDouble("templow").takeUnless { it.isNaN() },
            f.optInt("precipitation_probability", -1).takeIf { it >= 0 },
        )
    }
}

/** "3 PM" for hourly; "Today" / "Tue" for daily. */
fun forecastLabel(time: Date, daily: Boolean, now: Date = Date()): String {
    if (!daily) return SimpleDateFormat("h a", Locale.getDefault()).format(time)
    val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    return if (day.format(time) == day.format(now)) "Today" else SimpleDateFormat("EEE", Locale.getDefault()).format(time)
}

fun weatherIcon(condition: String, night: Boolean = false): ImageVector = when (condition) {
    "sunny" -> Icons.Rounded.WbSunny
    "clear-night" -> Icons.Rounded.Bedtime
    "partlycloudy" -> if (night) Icons.Rounded.Bedtime else Icons.Rounded.FilterDrama
    "cloudy", "fog" -> Icons.Rounded.Cloud
    "rainy" -> Icons.Rounded.WaterDrop
    "pouring" -> Icons.Rounded.Umbrella
    "lightning", "lightning-rainy" -> Icons.Rounded.Thunderstorm
    "snowy", "snowy-rainy", "hail" -> Icons.Rounded.AcUnit
    "windy", "windy-variant" -> Icons.Rounded.Air
    else -> Icons.Rounded.Warning
}

@Composable
fun WeatherCard(card: JSONObject, ctx: CardContext) {
    val id = card.optString("entity")
    val e = ctx.states[id] ?: return
    val a = e.attributes
    val unit = a.optString("temperature_unit", "°")
    // Forecast type: card's default, toggled by tapping the strip ("forecast_types: both").
    var daily by remember { mutableStateOf(card.optString("default_forecast", "daily") == "daily") }
    val type = if (daily) "daily" else "hourly"
    val forecast = rememberSubscription(id to type, "weather/subscribe_forecast", {
        put("entity_id", id); put("forecast_type", type)
    }) { parseForecast(it) }
    // Today's high/low come from the daily forecast; fetch it even while showing hourly.
    val today = rememberSubscription(id to "today", "weather/subscribe_forecast", {
        put("entity_id", id); put("forecast_type", "daily")
    }) { parseForecast(it).firstOrNull() }
    val tap = ctx.runner(tapAct(card, id), id) ?: { ctx.openMoreInfo(id) }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg).padding(20.dp)) {
        if (card.optBoolean("show_current", true)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = tap), verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(e.state), null, tint = Sun, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        a.optDouble("temperature").takeUnless { it.isNaN() }?.let { "${it.roundToInt()}$unit" } ?: "–",
                        color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light,
                    )
                    Text(weatherText(e.state), color = TextDim, fontSize = 15.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    today?.let { t ->
                        Text(listOfNotNull(t.temp?.let { "↑${it.roundToInt()}°" }, t.low?.let { "↓${it.roundToInt()}°" }).joinToString("  "), color = Color.White, fontSize = 15.sp)
                    }
                    a.optInt("humidity", -1).takeIf { it >= 0 }?.let { Text("$it% humidity", color = TextDim, fontSize = 13.sp) }
                    a.optDouble("wind_speed").takeUnless { it.isNaN() }?.let { Text("${it.roundToInt()} ${a.optString("wind_speed_unit")} wind", color = TextDim, fontSize = 13.sp) }
                }
            }
        }
        if (card.optBoolean("show_forecast", true) && !forecast.isNullOrEmpty()) {
            Spacer(Modifier.size(16.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .clickable(enabled = card.optString("forecast_types") == "both") { daily = !daily },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                forecast.take(if (daily) 7 else 12).forEach { f ->
                    val hour = Calendar.getInstance().apply { time = f.time }.get(Calendar.HOUR_OF_DAY)
                    Column(Modifier.widthIn(min = 52.dp).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(forecastLabel(f.time, daily), color = TextDim, fontSize = 12.sp)
                        Icon(weatherIcon(f.condition, night = !daily && (hour < 6 || hour >= 19)), null, tint = Sun, modifier = Modifier.padding(vertical = 6.dp).size(24.dp))
                        Text(f.temp?.let { "${it.roundToInt()}°" } ?: "", color = Color.White, fontSize = 14.sp)
                        if (daily) f.low?.let { Text("${it.roundToInt()}°", color = TextDim, fontSize = 12.sp) }
                        f.rainChance?.takeIf { it >= 20 }?.let { Text("$it%", color = Color(0xFF64B5F6), fontSize = 11.sp) }
                    }
                }
            }
            if (card.optString("forecast_types") == "both") {
                Text(if (daily) "Daily · tap for hourly" else "Hourly · tap for daily", color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

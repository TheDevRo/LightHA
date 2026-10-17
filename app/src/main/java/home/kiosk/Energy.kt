package home.kiosk

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
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

// HA's energy cards, drawn for today from the Energy dashboard's own settings (energy/get_prefs).
// HA lets you pick other days with energy-date-selection; a wall tablet shows today.

private val GridColor = Color(0xFF4CC9F0)
private val SolarColor = Color(0xFFFFB74D)
private val BatteryColor = Color(0xFF7AE582)
private val ExportColor = Color(0xFFBA68C8)
private val GasColor = Color(0xFFFF8A65)
private val WaterColor = Color(0xFF64B5F6)

/** The statistics the Energy dashboard is built from. Reads the current grid format and the older flow_from/flow_to one. */
data class EnergySetup(
    val gridIn: List<String> = emptyList(), val gridOut: List<String> = emptyList(), val solar: List<String> = emptyList(),
    val batteryOut: List<String> = emptyList(), val batteryIn: List<String> = emptyList(),
    val gas: List<String> = emptyList(), val water: List<String> = emptyList(),
    val devices: List<Pair<String, String>> = emptyList(), val waterDevices: List<Pair<String, String>> = emptyList(),
    /** Grid import price per kWh, when set as a fixed number. */
    val gridPrice: Double? = null,
    /** Live power sensors (stat_rate) configured on sources. */
    val power: List<String> = emptyList(),
) {
    val energyIds get() = (gridIn + gridOut + solar + batteryOut + batteryIn + gas + devices.map { it.first }).distinct()
    val waterIds get() = (water + waterDevices.map { it.first }).distinct()
}

fun energySetup(prefs: JSONObject): EnergySetup {
    var s = EnergySetup()
    fun JSONObject.stat(k: String) = text(k).takeIf { it.isNotEmpty() }
    prefs.optJSONArray("energy_sources")?.objects().orEmpty().forEach { src ->
        val rate = listOfNotNull(src.stat("stat_rate"), src.optJSONObject("power_config")?.stat("stat_rate"))
        s = when (src.text("type")) {
            "grid" -> {
                val from = listOfNotNull(src.stat("stat_energy_from")) + src.optJSONArray("flow_from")?.objects().orEmpty().mapNotNull { it.stat("stat_energy_from") }
                val to = listOfNotNull(src.stat("stat_energy_to")) + src.optJSONArray("flow_to")?.objects().orEmpty().mapNotNull { it.stat("stat_energy_to") }
                val price = src.optDouble("number_energy_price").takeUnless { it.isNaN() }
                    ?: src.optJSONArray("flow_from")?.objects()?.firstNotNullOfOrNull { f -> f.optDouble("number_energy_price").takeUnless { it.isNaN() } }
                s.copy(gridIn = s.gridIn + from, gridOut = s.gridOut + to, gridPrice = s.gridPrice ?: price, power = s.power + rate)
            }
            "solar" -> s.copy(solar = s.solar + listOfNotNull(src.stat("stat_energy_from")), power = s.power + rate)
            "battery" -> s.copy(batteryOut = s.batteryOut + listOfNotNull(src.stat("stat_energy_from")), batteryIn = s.batteryIn + listOfNotNull(src.stat("stat_energy_to")), power = s.power + rate)
            "gas" -> s.copy(gas = s.gas + listOfNotNull(src.stat("stat_energy_from")))
            "water" -> s.copy(water = s.water + listOfNotNull(src.stat("stat_energy_from")))
            else -> s
        }
    }
    fun devices(k: String) = prefs.optJSONArray(k)?.objects().orEmpty().mapNotNull { d -> d.stat("stat_consumption")?.let { it to d.text("name") } }
    return s.copy(devices = devices("device_consumption"), waterDevices = devices("device_consumption_water"))
}

/** Today's energy totals (kWh) and what they imply, the way HA's distribution card and gauges compute them. */
data class Flows(val gridIn: Double, val gridOut: Double, val solar: Double, val batteryIn: Double, val batteryOut: Double) {
    val home get() = (gridIn + solar + batteryOut - gridOut - batteryIn).coerceAtLeast(0.0)
    /** Share of home use not bought from the grid. */
    val selfSufficiency get() = if (home > 0) (1 - gridIn / home).coerceIn(0.0, 1.0) else null
    /** Share of solar used here rather than exported. */
    val solarConsumed get() = if (solar > 0) ((solar - gridOut) / solar).coerceIn(0.0, 1.0) else null
    /** Exported share of all grid traffic: 0.5 = net zero, above = net exporter. */
    val gridNeutrality get() = if (gridIn + gridOut > 0) gridOut / (gridIn + gridOut) else null
}

fun flowsOf(setup: EnergySetup, totals: Map<String, Double>): Flows {
    fun sum(ids: List<String>) = ids.sumOf { totals[it] ?: 0.0 }
    return Flows(sum(setup.gridIn), sum(setup.gridOut), sum(setup.solar), sum(setup.batteryIn), sum(setup.batteryOut))
}

/** Hourly `change` rows → per-statistic totals and per-hour values (24 slots from local midnight). */
fun energyTotals(stats: JSONObject): Map<String, Double> =
    stats.keys().asSequence().associateWith { id -> stats.optJSONArray(id)?.objects().orEmpty().sumOf { r -> r.optDouble("change").takeUnless { it.isNaN() } ?: 0.0 } }

fun energyHours(stats: JSONObject, ids: List<String>, midnight: Long): List<Double?> {
    val slots = DoubleArray(24)
    val seen = BooleanArray(24)
    ids.forEach { id ->
        stats.optJSONArray(id)?.objects().orEmpty().forEach { r ->
            val t = r.optDouble("start").let { if (it < 1e11) it * 1000 else it }
            val h = ((t - midnight) / 3_600_000).toInt()
            val v = r.optDouble("change")
            if (h in 0..23 && !v.isNaN()) { slots[h] += v; seen[h] = true }
        }
    }
    return (0 until 24).map { if (seen[it]) slots[it] else null }
}

private fun midnight(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Prefs plus today's hourly stats, fetched once per 5 minutes for all energy cards on screen. */
object EnergyData {
    class Today(val setup: EnergySetup, val stats: JSONObject, val midnight: Long) {
        val totals by lazy { energyTotals(stats) }
        val flows by lazy { flowsOf(setup, totals) }
    }
    private var cached: Today? = null
    private var at = 0L

    suspend fun get(ha: HaClient): Today? {
        cached?.takeIf { System.currentTimeMillis() - at < 5 * 60_000L && it.midnight == midnight() }?.let { return it }
        return runCatching {
            val setup = energySetup(ha.request("energy/get_prefs") as JSONObject)
            val start = midnight()
            val stats = JSONObject()
            suspend fun fetch(ids: List<String>, units: JSONObject?) {
                if (ids.isEmpty()) return
                val r = ha.request("recorder/statistics_during_period") {
                    put("start_time", isoUtc(start)); put("statistic_ids", JSONArray(ids)); put("period", "hour")
                    put("types", JSONArray(listOf("change"))); units?.let { put("units", it) }
                } as JSONObject
                r.keys().forEach { stats.put(it, r.get(it)) }
            }
            fetch(setup.energyIds, JSONObject().put("energy", "kWh"))
            fetch(setup.waterIds, null)
            Today(setup, stats, start)
        }.getOrNull()?.also { cached = it; at = System.currentTimeMillis() } ?: cached
    }
}

@Composable
private fun rememberEnergy(): EnergyData.Today? {
    val ha = LocalHa.current
    return polled(ha, 5 * 60_000L) { EnergyData.get(ha) }
}

private fun kwh(v: Double) = String.format(Locale.US, if (v >= 10) "%.0f kWh" else "%.1f kWh", v)

@Composable
private fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

/** The energy card types and which view draws them. */
enum class EnergyView { DATE, USAGE, SOURCE_GRAPH, DEVICES, FLOWS, TABLE, GAUGE, POWER, NONE }

fun energyView(type: String): EnergyView? = when (type) {
    "energy-date-selection", "energy-compare" -> EnergyView.DATE
    "energy-usage-graph", "energy-grid-balance" -> EnergyView.USAGE
    "energy-solar-graph", "energy-gas-graph", "energy-water-graph" -> EnergyView.SOURCE_GRAPH
    "energy-devices-graph", "energy-devices-detail-graph" -> EnergyView.DEVICES
    "energy-distribution", "energy-sankey", "water-sankey", "water-flow-sankey" -> EnergyView.FLOWS
    "energy-sources-table" -> EnergyView.TABLE
    "energy-solar-consumed-gauge", "energy-self-sufficiency-gauge", "energy-grid-neutrality-gauge" -> EnergyView.GAUGE
    "power-sources-graph", "power-sankey" -> EnergyView.POWER
    // Needs the CO2 Signal integration's fossil-fuel share, which this HA doesn't expose to a card; drawn as nothing.
    "energy-carbon-consumed-gauge" -> EnergyView.NONE
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnergyCard(card: JSONObject, ctx: CardContext, top: Boolean) {
    val type = card.optString("type")
    val view = energyView(type) ?: return
    if (view == EnergyView.NONE) return
    if (view == EnergyView.DATE) {
        Text("Today · " + SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()), color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        return
    }
    val data = rememberEnergy() ?: return
    val s = data.setup
    val f = data.flows
    val title = card.text("title")
    if (view == EnergyView.POWER) {
        if (s.power.isEmpty()) return
        Surface(top) { Column { CardTitle(title, Modifier.padding(start = 16.dp, top = 8.dp)); s.power.forEach { EntityCard(JSONObject().put("entity", it), ctx) } } }
        return
    }
    Surface(top) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (view) {
                EnergyView.USAGE -> {
                    CardTitle(title.ifEmpty { "Energy usage" })
                    val series = listOf(s.gridIn to GridColor, s.solar to SolarColor, s.batteryOut to BatteryColor, s.gridOut to ExportColor).filter { it.first.isNotEmpty() }
                    Legend(series.map { (ids, c) -> c to (when (c) { GridColor -> "Grid"; SolarColor -> "Solar"; BatteryColor -> "Battery"; else -> "Returned" } + " " + kwh(ids.sumOf { data.totals[it] ?: 0.0 })) })
                    HourBars(series.map { energyHours(data.stats, it.first, data.midnight) }, series.map { it.second })
                }
                EnergyView.SOURCE_GRAPH -> {
                    val (ids, color, name) = when (type) {
                        "energy-solar-graph" -> Triple(s.solar, SolarColor, "Solar production")
                        "energy-gas-graph" -> Triple(s.gas, GasColor, "Gas consumption")
                        else -> Triple(s.water, WaterColor, "Water consumption")
                    }
                    CardTitle(title.ifEmpty { name })
                    if (ids.isEmpty()) Text("Not set up in the Energy dashboard", color = TextDim, fontSize = 13.sp)
                    else HourBars(listOf(energyHours(data.stats, ids, data.midnight)), listOf(color))
                }
                EnergyView.DEVICES -> {
                    CardTitle(title.ifEmpty { "Individual devices" })
                    val rows = s.devices.map { (id, name) -> (name.ifEmpty { ctx.states[id]?.name ?: id }) to (data.totals[id] ?: 0.0) }.sortedByDescending { it.second }
                    val top1 = rows.firstOrNull()?.second?.takeIf { it > 0 } ?: 1.0
                    if (rows.isEmpty()) Text("No devices in the Energy dashboard", color = TextDim, fontSize = 13.sp)
                    rows.forEach { (name, v) ->
                        Column {
                            Row { Text(name, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(kwh(v), color = TextDim, fontSize = 13.sp) }
                            Box(Modifier.fillMaxWidth((v / top1).toFloat().coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(GridColor))
                        }
                    }
                }
                EnergyView.FLOWS -> {
                    val water = type.startsWith("water")
                    CardTitle(title.ifEmpty { if (water) "Water" else "Energy distribution" })
                    val parts = if (water) {
                        s.water.map { (ctx.states[it]?.name ?: "Water") to (data.totals[it] ?: 0.0) } + s.waterDevices.map { (id, n) -> n.ifEmpty { ctx.states[id]?.name ?: id } to (data.totals[id] ?: 0.0) }
                    } else listOf("Grid" to f.gridIn, "Solar" to f.solar, "Battery" to f.batteryOut, "Returned" to f.gridOut, "Gas" to s.gas.sumOf { data.totals[it] ?: 0.0 })
                    val shown = parts.filter { it.second > 0 }
                    val colors = listOf(GridColor, SolarColor, BatteryColor, ExportColor, GasColor, WaterColor)
                    if (!water) Text("Home " + kwh(f.home), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
                    val shares = distribution(shown.map { it.second })
                    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Chip)) {
                        shares.forEachIndexed { i, sh -> if (sh > 0f) Box(Modifier.weight(sh).fillMaxHeight().background(colors[i % colors.size])) }
                    }
                    Legend(shown.mapIndexed { i, (n, v) -> colors[i % colors.size] to "$n ${if (water) num(v) else kwh(v)}" })
                }
                EnergyView.TABLE -> {
                    CardTitle(title.ifEmpty { "Sources" })
                    val rows = listOf("Grid" to f.gridIn, "Solar" to f.solar, "Battery out" to f.batteryOut, "Battery in" to f.batteryIn, "Returned to grid" to f.gridOut).filter { it.second > 0 || it.first == "Grid" }
                    rows.forEach { (n, v) ->
                        Row {
                            Text(n, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(kwh(v), color = Color.White, fontSize = 14.sp)
                            if (n == "Grid" && s.gridPrice != null) Text("  $" + String.format(Locale.US, "%.2f", v * s.gridPrice), color = TextDim, fontSize = 14.sp)
                        }
                    }
                }
                EnergyView.GAUGE -> {
                    val (value, name) = when (type) {
                        "energy-solar-consumed-gauge" -> f.solarConsumed to "Self-consumed solar"
                        "energy-self-sufficiency-gauge" -> f.selfSufficiency to "Self-sufficiency"
                        else -> f.gridNeutrality to "Grid neutrality"
                    }
                    GaugeDial(value?.toFloat(), if (type == "energy-grid-neutrality-gauge") ExportColor else BatteryColor, value?.let { "${(it * 100).toInt()}%" } ?: "–", name)
                }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (c, t) -> Row(verticalAlignment = Alignment.CenterVertically) { Dot(c); Spacer(Modifier.width(6.dp)); Text(t, color = Color.White, fontSize = 13.sp) } }
    }
}

@Composable
private fun HourBars(series: List<List<Double?>>, colors: List<Color>) {
    if (series.all { s -> s.all { it == null } }) Text("No data yet today", color = TextDim, fontSize = 13.sp)
    else BarChart(series, colors, Modifier.fillMaxWidth().height(110.dp))
}

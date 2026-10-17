package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsTest {
    private fun j(s: String) = JSONObject(s)
    private fun e(id: String, state: String, attrs: String = "{}") = Entity(id, state, JSONObject(attrs))

    /** Every card type HA's frontend creates (create-card-element.ts, HA 2026.10). */
    private val nativeTypes = listOf(
        "entity", "entities", "button", "entity-button", "glance", "grid", "section", "light", "sensor", "thermostat",
        "weather-forecast", "tile", "heading", "alarm-panel", "area", "calendar", "conditional", "empty-state",
        "energy-compare", "energy-carbon-consumed-gauge", "energy-date-selection", "energy-devices-graph",
        "energy-devices-detail-graph", "energy-distribution", "energy-gas-graph", "energy-water-graph",
        "energy-grid-neutrality-gauge", "energy-grid-balance", "energy-solar-consumed-gauge", "energy-self-sufficiency-gauge",
        "energy-solar-graph", "energy-sources-table", "energy-usage-graph", "energy-sankey", "water-sankey",
        "water-flow-sankey", "power-sources-graph", "power-sankey", "entity-filter", "error", "home-summary", "shortcut",
        "discovered-devices", "repairs", "alert", "updates", "gauge", "history-graph", "horizontal-stack", "distribution",
        "humidifier", "iframe", "logbook", "map", "markdown", "clock", "media-control", "picture-elements", "picture-entity",
        "picture-glance", "picture", "plant-status", "recovery-mode", "toggle-group", "todo-list", "shopping-list", "starting",
        "statistics-graph", "statistic", "vertical-stack",
    )

    /** Not drawn natively yet; each later batch removes its types from here. */
    private val pending = nativeTypes.filter { it.startsWith("energy-") || it.startsWith("water-") || it.startsWith("power-") }

    @Test fun everyNativeCardHasARenderer() {
        for (t in nativeTypes) {
            val r = rendererOf(j("""{"type":"$t","entity":"sensor.x"}"""))
            if (t in pending) assertEquals("$t is drawn now; drop it from pending", Renderer.FALLBACK, r)
            else assertTrue("$t falls back", r != Renderer.FALLBACK)
        }
    }

    @Test fun rendererPicksTheSpecificCardFirst() {
        assertEquals(Renderer.CAMERA, rendererOf(j("""{"type":"picture-entity","entity":"camera.door"}""")))
        assertEquals(Renderer.PICTURE, rendererOf(j("""{"type":"picture-entity","entity":"image.map"}""")))
        assertEquals(Renderer.MEDIA, rendererOf(j("""{"type":"tile","entity":"media_player.sonos"}""")))
        assertEquals(Renderer.BUTTON, rendererOf(j("""{"type":"button","tap_action":{"action":"toggle"}}""")))
        assertEquals(Renderer.FALLBACK, rendererOf(j("""{"type":"custom:some-card","entity":"light.a"}""")))
    }

    @Test fun entityRowsReadsEntityAndEntities() {
        val rows = entityRows(j("""{"entity":"light.a","entities":["light.b",{"entity":"light.c","name":"C"},{"type":"divider"},null]}"""))
        assertEquals(listOf("light.a", "light.b", "light.c"), rows.map { it.getString("entity") })
        assertEquals("C", rows[2].getString("name"))
    }

    @Test fun legacyConditionalCardConditions() {
        val states = mapOf("sun.sun" to e("sun.sun", "below_horizon"))
        assertTrue(conditionsMet(j("""{"c":[{"entity":"sun.sun","state":"below_horizon"}]}""").getJSONArray("c"), "", states))
        assertFalse(conditionsMet(j("""{"c":[{"entity":"sun.sun","state_not":"below_horizon"}]}""").getJSONArray("c"), "", states))
        assertTrue(conditionsMet(null, "", states))
    }

    @Test fun stateFilterOperators() {
        val t = e("sensor.t", "21.5", """{"battery":15}""")
        assertTrue(stateFilterMatch(t, "21.5"))
        assertTrue(stateFilterMatch(t, j("""{"operator":"==","value":21.50}""")))
        assertTrue(stateFilterMatch(t, j("""{"operator":">","value":20}""")))
        assertFalse(stateFilterMatch(t, j("""{"operator":"<=","value":"20"}""")))
        assertTrue(stateFilterMatch(t, j("""{"operator":"<","value":20,"attribute":"battery"}""")))
        assertTrue(stateFilterMatch(e("light.a", "on"), j("""{"operator":"in","value":["on","off"]}""")))
        assertTrue(stateFilterMatch(e("light.a", "on"), j("""{"operator":"not in","value":["off"]}""")))
        assertTrue(stateFilterMatch(e("light.a", "unavailable"), j("""{"operator":"regex","value":"^unav"}""")))
        assertFalse(stateFilterMatch(e("light.a", "on"), j("""{"operator":"bogus","value":"on"}""")))
    }

    @Test fun entityFilterKeepsMatchingRows() {
        val states = mapOf("light.a" to e("light.a", "on"), "light.b" to e("light.b", "off"), "light.c" to e("light.c", "on"))
        val legacy = j("""{"entities":["light.a","light.b","light.c","light.gone"],"state_filter":["on"]}""")
        assertEquals(listOf("light.a", "light.c"), entityFilter(legacy, states).map { it.getString("entity") })
        // A row's own filter wins over the card's; conditions apply to each row's entity.
        val mixed = j("""{"entities":[{"entity":"light.b","state_filter":["off"]},"light.c"],"conditions":[{"condition":"state","state":"on"}]}""")
        assertEquals(listOf("light.b", "light.c"), entityFilter(mixed, states).map { it.getString("entity") })
    }

    @Test fun gaugeColours() {
        val sev = j("""{"severity":{"green":0,"yellow":45,"red":85}}""")
        assertEquals(mushroomColor("green"), gaugeColor(sev, 10.0))
        assertEquals(mushroomColor("yellow"), gaugeColor(sev, 45.0))
        assertEquals(mushroomColor("red"), gaugeColor(sev, 99.0))
        val segs = j("""{"segments":[{"from":0,"color":"#ff0000"},{"from":50,"color":"blue"}]}""")
        assertEquals(mushroomColor("blue"), gaugeColor(segs, 60.0))
        assertNull(gaugeColor(j("{}"), 5.0))
    }

    @Test fun statisticRequestDefaultsToToday() {
        val req = statisticRequest(j("""{"entity":"sensor.energy"}"""))
        assertEquals("sensor.energy", req.getString("statistic_id"))
        assertEquals("change", req.getJSONArray("types").getString(0))
        assertEquals("day", req.getJSONObject("calendar").getString("period"))
        val week = statisticRequest(j("""{"entity":"sensor.e","stat_type":"max","period":{"rolling_window":{"duration":{"days":7}}}}"""))
        assertEquals(7, week.getJSONObject("rolling_window").getJSONObject("duration").getInt("days"))
        assertFalse(week.has("calendar"))
    }

    @Test fun alarmActionsFollowStateAndFeatures() {
        assertEquals(listOf("arm_home", "arm_away"), alarmActions(j("{}"), e("alarm_control_panel.h", "disarmed", """{"supported_features":3}""")))
        assertEquals(listOf("arm_away"), alarmActions(j("{}"), e("alarm_control_panel.h", "disarmed", """{"supported_features":2}""")))
        assertEquals(listOf("arm_night"), alarmActions(j("""{"states":["arm_night"]}"""), e("alarm_control_panel.h", "disarmed")))
        assertEquals(listOf("disarm"), alarmActions(j("{}"), e("alarm_control_panel.h", "armed_away")))
    }

    @Test fun pictureUrlSources() {
        val states = mapOf("image.cam" to e("image.cam", "x", """{"entity_picture":"/api/image_proxy/image.cam?token=1"}"""), "light.a" to e("light.a", "on"))
        assertEquals("/local/a.png", pictureUrl(j("""{"image":"/local/a.png"}"""), states))
        assertEquals("media-source://x", pictureUrl(j("""{"image":{"media_content_id":"media-source://x"}}"""), states))
        assertEquals("/api/image_proxy/image.cam?token=1", pictureUrl(j("""{"image_entity":"image.cam"}"""), states))
        assertEquals("/local/on.png", pictureUrl(j("""{"entity":"light.a","image":"/local/x.png","state_image":{"on":"/local/on.png"}}"""), states))
    }

    @Test fun timelineSegments() {
        val rows = listOf(j("""{"s":"off","lu":100}"""), j("""{"s":"on","lu":150}"""), j("""{"s":"off","lu":180}"""))
        val segs = timeline(rows, 120_000, 200_000)
        assertEquals(listOf(Segment(120_000, 150_000, "off"), Segment(150_000, 180_000, "on"), Segment(180_000, 200_000, "off")), segs)
        assertEquals(Chip, timelineColor("off"))
        assertEquals(timelineColor("Watching"), timelineColor("Watching")) // stable per state text
    }

    @Test fun statisticsGraphPicksAvailableType() {
        val rows = listOf(j("""{"start":1,"change":2.5,"sum":10}"""))
        assertEquals("change", pickStatType(j("{}"), rows))
        assertEquals("sum", pickStatType(j("""{"stat_types":["sum"]}"""), rows))
        assertNull(pickStatType(j("""{"stat_types":"mean"}"""), rows))
    }

    @Test fun distributionShares() {
        assertEquals(listOf(0.25f, 0.75f, 0f), distribution(listOf(1.0, 3.0, -2.0)))
        assertEquals(listOf(0f, 0f), distribution(listOf(null, 0.0)))
    }

    @Test fun homeSummaries() {
        val states = mapOf(
            "light.a" to e("light.a", "on"), "light.b" to e("light.b", "off"),
            "light.group" to e("light.group", "on", """{"entity_id":["light.a","light.b"]}"""),
            "media_player.tv" to e("media_player.tv", "playing"), "person.a" to e("person.a", "home"), "person.b" to e("person.b", "not_home"),
            "sensor.t1" to e("sensor.t1", "20.5"), "sensor.t2" to e("sensor.t2", "23"),
            "sensor.bat" to e("sensor.bat", "11", """{"device_class":"battery"}"""),
        )
        assertEquals("1 light on", homeSummary("light", states))
        assertEquals("1 playing", homeSummary("media_players", states))
        assertEquals("1 of 2 home", homeSummary("persons", states))
        assertEquals("20.5 - 23.0°", homeSummary("climate", states, listOf("sensor.t1", "sensor.t2")))
        assertEquals("1 low battery", homeSummary("maintenance", states))
        assertEquals("", homeSummary("security", states))
        assertEquals("All lights off", homeSummary("light", states, hidden = setOf("light.a")))
    }

    @Test fun logbookNewestFirst() {
        val states = mapOf("light.a" to e("light.a", "off"))
        val log = parseLogbook(org.json.JSONArray("""[{"when":100,"entity_id":"light.a","state":"on"},{"when":200,"name":"HA","message":"started"}]"""), states)
        assertEquals(listOf("started", "Turned on"), log.map { it.text })
        assertEquals(200_000L, log[0].time)
    }

    @Test fun cssPercentPositions() {
        assertEquals(0.45f, cssPercent("45%")!!, 0.0001f)
        assertNull(cssPercent("30px"))
        assertNull(cssPercent(null))
    }
}

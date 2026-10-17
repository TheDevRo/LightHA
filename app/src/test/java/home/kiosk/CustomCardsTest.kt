package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCardsTest {
    private fun j(s: String) = JSONObject(s)
    private fun e(id: String, state: String, attrs: String = "{}", changed: String = "") = Entity(id, state, JSONObject(attrs), changed)

    /** Custom cards on this household's dashboards; each must draw something specific (not the generic fallback). */
    @Test fun usedCustomCardsHaveRenderers() {
        listOf(
            "custom:mushroom-light-card", "custom:mushroom-number-card", "custom:mushroom-template-card", "custom:mushroom-climate-card",
            "custom:mushroom-vacuum-card", "custom:mushroom-media-player-card", "custom:mushroom-entity-card", "custom:mini-graph-card",
            "custom:calendar-card-pro", "custom:atomic-calendar-revive", "custom:universal-remote-card", "custom:advanced-camera-card",
            "custom:yet-another-media-player", "custom:weather-forecast-card", "custom:uptime-card", "custom:flex-table-card",
            "custom:upcoming-media-card", "custom:threedy-card", "custom:auto-entities", "custom:power-flow-card-plus",
        ).forEach { t -> assertTrue("$t falls back", rendererOf(j("""{"type":"$t","entity":"sensor.x"}""")) != Renderer.FALLBACK) }
    }

    @Test fun wildcards() {
        assertTrue(wildcard("media_player.plex*", "media_player.plex_ipad"))
        assertFalse(wildcard("media_player.plex*", "media_player.sonos"))
        assertTrue(wildcard("*kitchen*", "light.Kitchen_main"))
        assertTrue(wildcard("/^sensor\\.t\\d$/", "sensor.t1"))
        assertFalse(wildcard("light.a", "light.ab"))
    }

    @Test fun autoEntitiesIncludeExcludeSort() {
        val states = mapOf(
            "media_player.plex_a" to e("media_player.plex_a", "playing", changed = "2026-10-16T10:00:00+00:00"),
            "media_player.plex_b" to e("media_player.plex_b", "playing", changed = "2026-10-16T12:00:00+00:00"),
            "media_player.plex_c" to e("media_player.plex_c", "idle"),
            "media_player.sonos" to e("media_player.sonos", "playing"),
        )
        val card = j("""{"filter":{"include":[{"domain":"media_player","entity_id":"media_player.plex*","state":"playing","options":{"secondary_info":"last-changed"}}],"exclude":[]},
            "sort":{"method":"last_changed","reverse":true}}""")
        val rows = autoEntities(card, states)
        assertEquals(listOf("media_player.plex_b", "media_player.plex_a"), rows.map { it.getString("entity") })
        assertEquals("last-changed", rows[0].getString("secondary_info"))
        val excl = j("""{"filter":{"include":[{"domain":"media_player"}],"exclude":[{"state":"idle"},{"entity_id":"*sonos"}]},"sort":{"method":"name"}}""")
        assertEquals(listOf("media_player.plex_a", "media_player.plex_b"), autoEntities(excl, states).map { it.getString("entity") })
        // Rules this app can't evaluate fail closed rather than matching everything.
        assertFalse(autoRuleMatches(j("""{"area":"office"}"""), states.getValue("media_player.sonos")))
        assertTrue(autoRuleMatches(j("""{"state":"> 5"}"""), e("sensor.t", "7")))
    }

    @Test fun uptimeShareIgnoresUnknownTime() {
        val segs = listOf(Segment(0, 90, "on"), Segment(90, 100, "off"), Segment(100, 200, "unavailable"))
        assertEquals(0.9, uptimeShare(segs) { it == "on" }!!, 1e-9)
        assertNull(uptimeShare(emptyList()) { true })
    }

    @Test fun flexTableExpandsListAttributes() {
        val states = mapOf("sensor.leases" to e("sensor.leases", "2", """{"Leases":[{"hostname":"a","address":"192.168.1.7"},{"hostname":"b","address":"192.168.1.60"}]}"""))
        val card = j("""{"entities":[{"entity":"sensor.leases"}],"columns":[{"name":"Host","data":"Leases.hostname"},{"name":"IP","data":"Leases.address"},{"name":"Count","prop":"state"}]}""")
        assertEquals(listOf(listOf("a", "192.168.1.7", "2"), listOf("b", "192.168.1.60", "2")), flexRows(card, states))
    }

    @Test fun upcomingMediaLines() {
        val defaults = j("""{"title_default":"${'$'}title","line1_default":"${'$'}episode","line2_default":"${'$'}release","line3_default":"${'$'}number - ${'$'}rating"}""")
        val item = j("""{"title":"MobLand","episode":"Birdland","number":"S02E05","rating":"","release":"${'$'}day, ${'$'}time","airdate":"2026-10-16T07:00:00Z"}""")
        val lines = upcomingLines(defaults, item)
        assertEquals("MobLand", lines[0])
        assertEquals("Birdland", lines[1])
        assertTrue(lines[2], lines[2].contains(",") && '$' !in lines[2])
        assertEquals("S02E05", lines[3])
    }

    @Test fun powerFlowEntities() {
        val card = j("""{"entities":{"grid":{"name":"Grid","entity":{"consumption":"sensor.in","production":"sensor.out"}},"home":{"entity":"sensor.home"},"individual":[{"entity":"sensor.ev","name":"EV"}]}}""")
        assertEquals(listOf("Home" to "sensor.home", "Grid in" to "sensor.in", "Grid out" to "sensor.out", "EV" to "sensor.ev"), powerFlowEntities(card))
    }
}

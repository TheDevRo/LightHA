package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LovelaceTest {
    private fun j(s: String) = JSONObject(s)
    private fun e(id: String, state: String) = Entity(id, state, JSONObject())

    @Test fun visibilityConditions() {
        val states = mapOf("climate.ac" to e("climate.ac", "unavailable"), "sun.sun" to e("sun.sun", "below_horizon"), "sensor.t" to e("sensor.t", "71"))
        // state_not with no entity falls back to the card's own entity
        assertFalse(visible(j("""{"entity":"climate.ac","visibility":[{"condition":"state","state_not":"unavailable"}]}"""), states))
        assertTrue(visible(j("""{"visibility":[{"condition":"or","conditions":[{"condition":"state","entity":"sun.sun","state":["above_horizon","below_horizon"]}]}]}"""), states))
        assertTrue(visible(j("""{"visibility":[{"condition":"numeric_state","entity":"sensor.t","above":70,"below":75}]}"""), states))
        assertFalse(visible(j("""{"visibility":[{"condition":"numeric_state","entity":"sensor.t","below":70}]}"""), states))
        assertTrue(visible(j("""{"visibility":[{"condition":"screen","media_query":"(min-width: 1024px)"}]}"""), states))
    }

    @Test fun tapActions() {
        assertEquals(Act.Default, tapAct(j("""{"entity":"light.a"}"""), "light.a"))
        assertEquals(Act.Popup("#pop-up-cameras"), tapAct(j("""{"tap_action":{"action":"navigate","navigation_path":"#pop-up-cameras"}}"""), null))
        val svc = tapAct(j("""{"tap_action":{"action":"perform-action","perform_action":"input_boolean.turn_off","target":{"entity_id":["input_boolean.x"]}}}"""), null) as Act.Service
        assertEquals("input_boolean" to "turn_off", svc.domain to svc.service)
        assertEquals("input_boolean.x", svc.data!!.getJSONArray("entity_id").getString(0))
    }

    @Test fun holdActions() {
        assertEquals(Act.MoreInfo, holdAct(j("""{"entity":"light.a"}"""), "light.a"))
        assertEquals(Act.MoreInfo, tapAct(j("""{"tap_action":{"action":"more-info"}}"""), "light.a"))
        val custom = holdAct(j("""{"hold_action":{"action":"perform-action","perform_action":"automation.trigger"}}"""), "light.a")
        assertEquals("automation" to "trigger", (custom as Act.Service).let { it.domain to it.service })
        assertEquals(Act.MoreInfo, doubleTapAct(j("""{"double_tap_action":{"action":"more-info"}}"""), "light.a"))
        assertEquals(Act.None, doubleTapAct(j("""{"entity":"light.a"}"""), "light.a"))
    }

    @Test fun confirmationWrapsServiceCalls() {
        val c = tapAct(j("""{"tap_action":{"action":"perform-action","perform_action":"script.bedtime","confirmation":{"text":"Start bedtime?"}}}"""), null)
        assertEquals("Start bedtime?", (c as Act.Confirm).text)
        assertTrue(c.then is Act.Service)
        assertTrue(tapAct(j("""{"tap_action":{"action":"toggle","confirmation":true}}"""), "lock.door") is Act.Confirm)
        assertEquals(Act.MoreInfo, tapAct(j("""{"tap_action":{"action":"more-info","confirmation":true}}"""), "x.y")) // only for things that do something
    }

    @Test fun agoText() {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).parse("2026-10-15T22:30:00+0000")!!
        assertEquals("5 min ago", ago("2026-10-15T22:25:00.123+00:00", now))
        assertEquals("3 h ago", ago("2026-10-15T19:30:00+00:00", now))
        assertEquals("", ago("", now))
    }

    @Test fun rowPacking() {
        val half = j("""{"type":"x","grid_options":{"columns":6}}""")
        val full = j("""{"type":"x"}""")
        val tile = j("""{"type":"tile"}""")
        assertEquals(listOf(2, 1, 1), packRows(listOf(half, tile, full, half)).map { it.size })
    }

    private val cfg = j("""{"views":[{"sections":[{"type":"grid","cards":[{"type":"a"},{"type":"b"},{"type":"c","custom_option":{"keep":true}}]}]}]}""")
    private fun types(c: JSONObject) = sectionsOf(c)[0].map { it.getString("type") }

    @Test fun editsReturnCopiesAndKeepUnknownFields() {
        assertEquals(listOf("b", "a", "c"), types(Edits.move(cfg, 0, 0, 0, 1)))
        assertEquals(listOf("a", "c"), types(Edits.remove(cfg, 0, 0, 1)))
        assertEquals(listOf("a", "b", "c", "tile"), types(Edits.add(cfg, 0, 0, Edits.cardFor("light.x"))))
        val renamed = Edits.rename(cfg, 0, 0, 2, "Hall")
        assertEquals("Hall", sectionsOf(renamed)[0][2].getString("name"))
        assertTrue(sectionsOf(renamed)[0][2].getJSONObject("custom_option").getBoolean("keep"))
        assertEquals(listOf("a", "b", "c"), types(cfg)) // original untouched
    }

    @Test fun viewsAndNavigation() {
        val c = j("""{"views":[{"title":"Home","sections":[{"cards":[{"type":"a"}]}]},{"title":"Living Room","path":"living-room","cards":[{"type":"b"}]}]}""")
        assertEquals(listOf("0", "living-room"), viewsOf(c).mapIndexed { i, v -> viewKey(v, i) })
        assertEquals(1, viewIndexFor(c, "/wall-home/living-room"))
        assertEquals(0, viewIndexFor(c, "0"))
        assertEquals(null, viewIndexFor(c, "/elsewhere/nope"))
        assertEquals("b", sectionsOf(c, 1)[0][0].getString("type")) // masonry view = one section
        assertEquals(Act.View("/wall-home/living-room"), tapAct(j("""{"tap_action":{"action":"navigate","navigation_path":"/wall-home/living-room"}}"""), null))
        assertEquals(listOf("b", "x"), sectionsOf(Edits.add(c, 1, 0, j("""{"type":"x"}""")), 1)[0].map { it.getString("type") })
    }

    @Test fun visibilityAcceptsEntityId() {
        val states = mapOf("climate.ac" to e("climate.ac", "off"))
        assertFalse(visible(j("""{"visibility":[{"condition":"not","conditions":[{"condition":"state","entity_id":"climate.ac","state":["off"]}]}]}"""), states))
    }

    @Test fun popupsByHash() {
        val c = j("""{"views":[{"sections":[{"cards":[{"type":"custom:bubble-card","card_type":"pop-up","hash":"#pop-up-calendar","cards":[{"type":"heading"}]}]}]}]}""")
        assertEquals(1, popupsOf(c)["#pop-up-calendar"]!!.size)
    }
}

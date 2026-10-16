package home.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class KioskTest {
    @Test fun saverStartsOnlyWhenIdleLongEnoughAndNotBusy() {
        assertTrue(shouldStartSaver(idleMs = 5 * 60_000L, minutes = 5, busy = false))
        assertFalse(shouldStartSaver(idleMs = 4 * 60_000L, minutes = 5, busy = false))
        assertFalse(shouldStartSaver(idleMs = 60 * 60_000L, minutes = 5, busy = true)) // editing / sheet open
        assertFalse(shouldStartSaver(idleMs = 60 * 60_000L, minutes = 0, busy = false)) // "Never"
    }

    @Test fun clockMovesEveryMinuteAndStaysOnScreen() {
        val spots = (0L until 6L).map { saverOffset(it, 1000f, 1000f) }
        assertEquals(6, spots.toSet().size)
        assertNotEquals(saverOffset(0, 1000f, 1000f), saverOffset(1, 1000f, 1000f))
        spots.forEach { (x, y) -> assertTrue(x in 0f..1000f && y in 0f..1000f) }
    }

    @Test fun screenFollowsAnyEntity() {
        fun e(id: String, state: String) = Entity(id, state, JSONObject())
        val idle = 5 * 60_000L
        assertEquals(true, followAction(e("light.office_lights", "on"), idle, busy = false))
        assertEquals(false, followAction(e("light.office_lights", "off"), idle, busy = false))
        assertEquals(true, followAction(e("binary_sensor.kitchen_motion", "on"), 0, busy = true)) // waking is always fine
        assertEquals(true, followAction(e("person.deven", "home"), idle, busy = false))
        assertNull(followAction(e("light.office_lights", "off"), 30_000L, busy = false)) // touched recently
        assertNull(followAction(e("light.office_lights", "off"), idle, busy = true)) // editing / sheet open
        assertNull(followAction(e("light.office_lights", "unavailable"), idle, busy = false))
        assertNull(followAction(null, idle, busy = false)) // nothing chosen
    }
}

package home.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

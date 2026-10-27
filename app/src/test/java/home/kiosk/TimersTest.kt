package home.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class TimersTest {
    // Shape from HA 2026.10's HassTimerStatus via /api/intent/handle.
    private val response = """{"response_type":"action_done","speech_slots":{"timers":[
        {"id":"a","name":"pasta","device_id":"me","is_active":true,"total_seconds_left":117},
        {"id":"b","name":"","device_id":"me","is_active":false,"total_seconds_left":30},
        {"id":"c","name":"eggs","device_id":"other","is_active":true,"total_seconds_left":10}]}}"""

    @Test fun keepsThisTabletsTimersSoonestFirst() {
        val t = parseTimers(response, "me", 1_000)
        assertEquals(listOf("b", "a"), t.map { it.id })
        assertEquals(107, t[1].secondsLeft(11_000))
        assertEquals(30, t[0].secondsLeft(99_000)) // paused
        assertEquals(0, t[1].secondsLeft(999_000))
        assertEquals(emptyList<AssistTimer>(), parseTimers("""{"speech":{}}""", "me", 0))
    }

    @Test fun clockFormats() {
        assertEquals("1:15", clock(75))
        assertEquals("0:05", clock(5))
        assertEquals("1:02:05", clock(3725))
    }
}

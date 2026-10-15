package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KindsTest {
    private fun e(id: String, state: String, attrs: String = "{}") = Entity(id, state, JSONObject(attrs))

    @Test fun tapActions() {
        assertEquals("toggle", tapService(e("light.a", "off")))
        assertEquals("unlock", tapService(e("lock.front", "locked")))
        assertEquals("lock", tapService(e("lock.front", "unlocked")))
        assertEquals("press", tapService(e("button.restart", "2026-10-15T10:00:00+00:00")))
        assertEquals("turn_on", tapService(e("scene.movie", "scening")))
        assertNull(tapService(e("sensor.temp", "21")))
        assertNull(tapService(e("light.a", "unavailable")))
    }

    @Test fun stateTexts() {
        assertEquals("50%", stateText(e("light.a", "on", """{"brightness":128}""")))
        assertEquals("20.5° → 22°", stateText(e("climate.lr", "heat", """{"current_temperature":20.5,"temperature":22}""")))
        assertEquals("Off · 19°", stateText(e("climate.lr", "off", """{"current_temperature":19,"temperature":null}""")))
        assertEquals("Open", stateText(e("binary_sensor.door", "on", """{"device_class":"door"}""")))
        assertEquals("21.4°C", stateText(e("sensor.t", "21.44", """{"unit_of_measurement":"°C"}""")))
        assertEquals("230 W", stateText(e("sensor.p", "230", """{"unit_of_measurement":"W"}""")))
        assertEquals("Song — Band", stateText(e("media_player.x", "playing", """{"media_title":"Song","media_artist":"Band"}""")))
        assertEquals("40% open", stateText(e("cover.blind", "open", """{"current_position":40}""")))
    }

    @Test fun timestampsAndWeather() {
        val tz = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse("2026-10-16 08:00")!!
            assertEquals("1:06 PM", timeText("2026-10-16T13:06:27.123+00:00", now))
            assertEquals("Thu 12:25 AM", timeText("2026-10-15T00:25:02+00:00", now))
            assertEquals("garbage", timeText("garbage", now))
        } finally {
            java.util.TimeZone.setDefault(tz)
        }
        assertEquals("Partly cloudy", stateText(e("weather.home", "partlycloudy")))
        assertEquals("", stateText(e("input_button.cam", "unknown"))) // never pressed is fine
    }

    @Test fun numberStepper() {
        val n = e("input_number.low", "66.0", """{"step":1,"min":60,"max":66,"unit_of_measurement":"°F"}""")
        assertEquals("66°F", stateText(n))
        assertEquals(65.0, nudgedValue(n, up = false)!!, 0.0)
        assertEquals(66.0, nudgedValue(n, up = true)!!, 0.0) // clamped at max
        assertNull(tapService(n))
    }

    @Test fun thermostatNudge() {
        assertEquals(22.5, nudgedTarget(e("climate.lr", "heat", """{"temperature":22}"""), up = true)!!, 0.0)
        assertEquals(69.0, nudgedTarget(e("climate.lr", "heat", """{"temperature":70}"""), up = false)!!, 0.0)
        assertNull(nudgedTarget(e("climate.lr", "heat_cool", """{"temperature":null}"""), up = true))
    }
}

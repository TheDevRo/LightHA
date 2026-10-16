package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class WeatherTest {
    @Test fun parsesForecastAndLabels() {
        val items = parseForecast(JSONObject("""{"type":"daily","forecast":[
            {"datetime":"2026-10-16T06:00:00+00:00","condition":"sunny","temperature":71,"templow":45,"precipitation_probability":10},
            {"datetime":"bad","condition":"rainy"},
            {"datetime":"2026-10-17T06:00:00+00:00","condition":"rainy","temperature":60}]}"""))
        assertEquals(2, items.size)
        assertEquals(45.0, items[0].low!!, 0.0)
        assertNull(items[1].rainChance)

        val tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val now = parseIso("2026-10-16T12:00:00+00:00")!!
            assertEquals("Today", forecastLabel(items[0].time, daily = true, now = now))
            assertEquals("Sat", forecastLabel(items[1].time, daily = true, now = now))
            assertEquals("3 PM", forecastLabel(parseIso("2026-10-16T15:00:00+00:00")!!, daily = false, now = now))
        } finally {
            TimeZone.setDefault(tz)
        }
    }
}

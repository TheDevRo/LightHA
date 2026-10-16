package home.kiosk

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CalendarTest {
    @Test fun parsesAndGroupsEvents() {
        val tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
        try {
            val events = parseEvents(JSONArray("""[
                {"start":{"dateTime":"2026-10-15T18:00:00-06:00"},"end":{"dateTime":"2026-10-15T19:00:00-06:00"},"summary":"Therapy "},
                {"start":{"date":"2026-10-16"},"end":{"date":"2026-10-17"},"summary":"Vacuum stairs"},
                {"start":{"dateTime":"2026-10-16T09:00:00-06:00"},"summary":"Dentist","location":"Main St"},
                {"start":{"date":"2026-10-17"},"summary":"Null location","location":null},
                {"start":{"dateTime":"2026-10-15T08:00:00-06:00"},"end":{"dateTime":"2026-10-15T09:00:00-06:00"},"summary":"Earlier today"},
                {"summary":"no start"}]"""), Color.Red)
            assertEquals(5, events.size)
            assertEquals("", events[4].location)
            assertTrue(events[1].allDay)
            assertEquals("Therapy", events[0].title)

            val now = parseIso("2026-10-15T17:30:00-06:00")!!
            val groups = groupByDay(events, now)
            assertEquals(listOf("Today", "Tomorrow", "Sat, Oct 17"), groups.map { it.first })
            assertEquals(listOf("Earlier today", "Therapy"), groups[0].second.map { it.title }) // today's past events stay listed
            assertEquals(listOf("Vacuum stairs", "Dentist"), groups[1].second.map { it.title }) // all-day first
        } finally {
            TimeZone.setDefault(tz)
        }
    }
}

package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphTest {
    @Test fun whichCardsAreGraphs() {
        assertTrue(isGraphCard(JSONObject("""{"type":"custom:mini-graph-card"}""")))
        assertTrue(isGraphCard(JSONObject("""{"type":"sensor","graph":"line"}""")))
        assertFalse(isGraphCard(JSONObject("""{"type":"sensor","entity":"sensor.x"}"""))) // plain sensor stays a row
    }

    @Test fun linesFromConfig() {
        val lines = graphLines(JSONObject("""{"entities":[{"entity":"sensor.a","name":"Living","color":"#FF6F61"},"sensor.b"]}"""))
        assertEquals(listOf("sensor.a", "sensor.b"), lines.map { it.id })
        assertEquals("Living", lines[0].name)
        assertEquals(null, lines[1].name)
    }

    @Test fun statisticsRows() {
        val rows = listOf(
            JSONObject("""{"start":1792000000000,"end":1792000300000,"mean":12.5,"state":13}"""),
            JSONObject("""{"start":1792000300000,"state":14}"""),   // total_increasing sensors: state only
            JSONObject("""{"start":1792000600,"mean":15}"""),       // seconds (older HA)
            JSONObject("""{"start":1792000900000}"""),
        )
        assertEquals(listOf(1792000000000L to 12.5, 1792000300000L to 14.0, 1792000600000L to 15.0), statisticsPoints(rows))
    }

    @Test fun historyAndBuckets() {
        val pts = historyPoints(listOf(JSONObject("""{"s":"68.5","lu":1000.0}"""), JSONObject("""{"s":"unavailable","lu":1500.0}"""), JSONObject("""{"s":"70","lu":2000.5}""")))
        assertEquals(listOf(1_000_000L to 68.5, 2_000_500L to 70.0), pts)

        // 4 buckets over 0..400: values in buckets 0 and 2; bucket 1 and 3 repeat the last value.
        val b = bucketize(listOf(10L to 1.0, 20L to 3.0, 250L to 5.0), start = 0, end = 400, buckets = 4)
        assertEquals(listOf(2.0, 2.0, 5.0, 5.0), b)
        // A value from before the window seeds the start instead of leaving it empty.
        assertEquals(listOf(9.0, 9.0), bucketize(listOf(-5L to 9.0), 0, 100, 2))
        assertEquals(listOf<Double?>(null, null), bucketize(emptyList(), 0, 100, 2))
    }
}

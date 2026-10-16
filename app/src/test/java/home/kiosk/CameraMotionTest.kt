package home.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMotionTest {
    private val w = 176
    private val h = 144
    private fun frame(fill: (x: Int, y: Int) -> Int) = ByteArray(w * h * 3 / 2).also { f ->
        for (y in 0 until h) for (x in 0 until w) f[y * w + x] = fill(x, y).toByte()
    }

    @Test fun stillSceneWithNoiseIsNotMotion() {
        val a = lumaGrid(frame { x, y -> 100 + (x + y) % 5 }, w, h)
        val b = lumaGrid(frame { x, y -> 100 + (x * y) % 5 }, w, h)
        assertEquals(0f, motionScore(a, b))
    }

    @Test fun personSizedChangeCrossesMediumThreshold() {
        val empty = lumaGrid(frame { _, _ -> 100 }, w, h)
        // A dark shape covering about a sixth of the picture.
        val person = lumaGrid(frame { x, y -> if (x in 70..110 && y in 30..140) 30 else 100 }, w, h)
        val score = motionScore(empty, person)
        assertTrue("score $score", score >= 0.06f && score < 0.5f)
    }

    @Test fun gridCoversWholeFrameWithBrightValues() {
        val g = lumaGrid(frame { _, _ -> 250 }, w, h) // bytes above 127 must not go negative
        assertTrue(g.all { it == 250 })
    }
}

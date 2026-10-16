package home.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WakeWordTest {
    @Test fun bundledManifestsParse() {
        WAKE_WORDS.forEach { (id, _) ->
            val m = WakeModel.parse(id, File("src/main/assets/wakeword/$id.json").readText())
            assert(m.cutoff in 0.5f..0.99f && m.stepMs > 0 && m.window > 0) { m }
            assert(File("src/main/assets/wakeword/$id.tflite").length() > 10_000) { id }
        }
    }

    @Test fun sensitivityNudgesCutoffWithinBounds() {
        assertEquals(0.85f, wakeCutoff(0.85f, 0), 1e-6f)
        assertEquals(0.88f, wakeCutoff(0.85f, 1), 1e-6f)
        assertEquals(0.75f, wakeCutoff(0.85f, -1), 1e-6f)
        assertEquals(0.99f, wakeCutoff(0.97f, 1), 1e-6f)
    }
}

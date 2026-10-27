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

    @Test fun bundledModelsLookLikeTflite() {
        WAKE_WORDS.forEach { (id, _) -> assert(WakeCatalog.isTflite(File("src/main/assets/wakeword/$id.tflite").readBytes())) { id } }
        assert(!WakeCatalog.isTflite("<html>404</html>".padEnd(20_000).toByteArray()))
    }

    @Test fun catalogKeepsNewestVersionOfEachWord() {
        fun e(v: Int, slug: String, label: String, esphome: Boolean = false) = """{"source": "microWakeWordsV$v", "slug": "$slug", "label": "$label",
            "url": "https://x/V$v/$slug${if (esphome) ".wake-bundle" else ""}.json", "model_url": "https://x/V$v/$slug.tflite"
            ${if (esphome) ", \"esphome_url\": \"https://x/V$v/$slug.esphome.json\"" else ""}}"""
        val words = WakeCatalog.parse("""{"entries": [${listOf(
            e(1, "hey_alfred", "Hey Alfred"), e(8, "hey_alfred", "Hey Alfred", esphome = true), e(3, "hey_alfred", "Hey Alfred"),
            e(2, "achim", "Achim"), e(2, "../evil", "Evil"),
        ).joinToString()}]}""")
        assertEquals(listOf("achim", "hey_alfred"), words.map { it.slug })
        assertEquals("https://x/V8/hey_alfred.esphome.json", words[1].jsonUrl)
        assertEquals("https://x/V8/hey_alfred.tflite", words[1].modelUrl)
    }

    @Test fun sensitivityNudgesCutoffWithinBounds() {
        assertEquals(0.85f, wakeCutoff(0.85f, 0), 1e-6f)
        assertEquals(0.88f, wakeCutoff(0.85f, 1), 1e-6f)
        assertEquals(0.75f, wakeCutoff(0.85f, -1), 1e-6f)
        assertEquals(0.99f, wakeCutoff(0.97f, 1), 1e-6f)
    }
}

class LoudnessTest {
    @org.junit.Test fun silenceAndFullScale() {
        org.junit.Assert.assertEquals(0f, loudness(ByteArray(640), 640), 0f)
        // 0x4000 = half scale: RMS 0.5, times 8, capped at 1.
        val loud = ByteArray(640) { if (it % 2 == 0) 0x00 else 0x40 }
        org.junit.Assert.assertEquals(1f, loudness(loud, 640), 0f)
        // -64 (0xFFC0) is quiet: RMS ~0.002, times 8.
        val quiet = ByteArray(640) { if (it % 2 == 0) 0xC0.toByte() else 0xFF.toByte() }
        org.junit.Assert.assertEquals(64 / 32768f * 8, loudness(quiet, 640), 1e-4f)
    }
}

package home.kiosk

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Every card on your own dashboards, run through the dispatcher. Only runs when `dashboards.local.json`
 * (a map of dashboard → Lovelace config, gitignored) sits in the repo root; prints what falls back.
 */
class RealDashboardsTest {
    @Test fun noRealCardFallsBack() {
        val file = File("../dashboards.local.json")
        assumeTrue("no dashboards.local.json", file.exists())
        val all = JSONObject(file.readText())
        val fallbacks = mutableMapOf<String, Int>()
        var cards = 0
        fun walk(card: JSONObject, where: String) {
            cards++
            if (rendererOf(card) == Renderer.FALLBACK) fallbacks.merge("${card.optString("type")} ($where)", 1, Int::plus)
            listOf("cards", "elements").forEach { k -> card.optJSONArray(k)?.objects()?.forEach { walk(it, where) } }
            card.optJSONObject("card")?.let { walk(it, where) }
        }
        all.keys().forEach { name ->
            viewsOf(all.getJSONObject(name)).forEachIndexed { i, _ -> sectionsOf(all.getJSONObject(name), i).flatten().forEach { walk(it, name) } }
        }
        println("RealDashboards: $cards cards checked")
        fallbacks.forEach { (k, n) -> println("RealDashboards FALLBACK $n× $k") }
        assert(fallbacks.isEmpty()) { "cards falling back: $fallbacks" }
    }

}

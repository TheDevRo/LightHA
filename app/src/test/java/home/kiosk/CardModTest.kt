package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardModTest {
    private fun card(style: Any) = JSONObject().put("type", "markdown").put("card_mod", JSONObject().put("style", style))

    @Test fun ipadClockStyle() { // the real mobile-dashboard clock
        val m = cardMod(card("ha-card {\n  font-size: 6em;\n  font-weight: bold;\n  text-align: center;\n}\n"))
        assertEquals(6f, m.fontScale)
        assertEquals(true, m.bold)
        assertEquals("center", m.align)
    }

    @Test fun colorsAndBackground() {
        assertEquals(0xFF00FFFF, cardMod(card("ha-card {\n  color: cyan;\n}\n")).color) // the network dashboard
        val m = cardMod(card("ha-card { background: #1e1e1e !important; color: rgba(255, 0, 0, 0.5); border-radius: 8px }"))
        assertEquals(0xFF1E1E1E, m.background)
        assertEquals(0x7FFF0000L, m.color)
        assertEquals(8f, m.radiusPx)
        assertEquals(0xFFAABBCC, cssColor("#abc"))
        assertEquals(0x80112233L, cssColor("#11223380"))
    }

    @Test fun mapStyleBareDeclarationsAndIgnoredValues() {
        assertEquals(1.5f, cardMod(card(JSONObject().put(".", "ha-card { font-size: 24px }"))).fontScale)
        assertEquals("right", cardMod(card("text-align: end")).align)
        assertNull(cardMod(card("ha-card { color: var(--primary-color) }")).color)
        assertNull(cardMod(card("ha-card { color: {{ 'red' if is_state('sun.sun','above_horizon') else 'blue' }} }")).color)
        assertEquals(CardMod(), cardMod(JSONObject().put("type", "markdown")))
    }
}

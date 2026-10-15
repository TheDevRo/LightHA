package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {
    private fun e(id: String, state: String, attrs: String = "{}") = Entity(id, state, JSONObject(attrs))

    @Test fun positionAdvancesOnlyWhilePlaying() {
        val now = parseIso("2026-10-15T22:00:30+00:00")!!
        val attrs = """{"media_position":100,"media_duration":120,"media_position_updated_at":"2026-10-15T22:00:00.5+00:00"}"""
        assertEquals(130.0, mediaPosition(e("media_player.k", "playing", attrs.replace("120", "300")), now)!!, 0.01)
        assertEquals(120.0, mediaPosition(e("media_player.k", "playing", attrs), now)!!, 0.01) // clamped to duration
        assertEquals(100.0, mediaPosition(e("media_player.k", "paused", attrs), now)!!, 0.01)
        assertEquals("2:10", clockText(130.0))
    }

    @Test fun activePlayerAndIds() {
        val states = mapOf("media_player.a" to e("media_player.a", "idle"), "media_player.b" to e("media_player.b", "playing"))
        assertEquals("media_player.b", activePlayer(listOf("media_player.a", "media_player.b"), states))
        assertEquals("media_player.a", activePlayer(listOf("media_player.a"), states))
        assertEquals(listOf("media_player.x", "media_player.y"),
            mediaIds(JSONObject("""{"type":"custom:yet-another-media-player","entities":["media_player.x",{"entity_id":"media_player.y"}]}""")))
        assertTrue(isMediaCard(JSONObject("""{"type":"tile","entity":"media_player.x"}""")))
        assertFalse(isMediaCard(JSONObject("""{"type":"tile","entity":"light.x"}""")))
    }

    @Test fun featureFlags() {
        val sonos = e("media_player.s", "idle", """{"supported_features":8320575}""")
        assertTrue(supports(sonos, MP_NEXT) && supports(sonos, MP_VOLUME_SET) && supports(sonos, MP_PLAY))
        assertFalse(supports(e("media_player.p", "idle", """{"supported_features":131584}"""), MP_NEXT))
    }
}

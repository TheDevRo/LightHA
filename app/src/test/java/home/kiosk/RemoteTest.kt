package home.kiosk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTest {
    private val fire = JSONObject("""{"type":"custom:universal-remote-card","platform":"Fire TV","remote_id":"remote.pi","media_player_id":"media_player.pi",
        "custom_actions":[{"type":"button","name":"playstop","tap_action":{"action":"key","key":"MEDIA_PLAY_PAUSE"}},
                          {"type":"button","name":"Plex","tap_action":{"action":"perform-action","perform_action":"script.pi_plex","target":{}}}]}""")
    private val apple = JSONObject("""{"type":"custom:universal-remote-card","platform":"Apple TV","remote_id":"remote.bedroom","config_entry_id":"abc"}""")

    private fun cmd(a: Act?) = (a as Act.Service).data!!.getString("command")

    @Test fun fireTvUsesAdbShortcutsOrKeyevents() {
        val back = defaultKeyService(fire, "back")!!
        assertEquals("androidtv" to "adb_command", back.domain to back.service)
        assertEquals(listOf("media_player.pi"), back.ids)
        assertEquals("BACK", cmd(back))
        assertEquals("input keyevent KEYCODE_MEDIA_REWIND", cmd(defaultKeyService(fire, "rewind")))
        assertEquals("input keyevent KEYCODE_MEDIA_PLAY_PAUSE", cmd(remoteAct(fire, customAction(fire, "playstop")!!.getJSONObject("tap_action"))))
        assertEquals("CENTER", cmd(keyService(fire, "CENTER")))
    }

    @Test fun appleTvUsesRemoteSendCommand() {
        val menu = defaultKeyService(apple, "menu")!!
        assertEquals("remote" to "send_command", menu.domain to menu.service)
        assertEquals("menu", cmd(menu))
        assertEquals("skip_backward", cmd(defaultKeyService(apple, "skip_backward")))
        assertEquals("select", cmd(defaultKeyService(apple, "center")))
        assertNull(defaultKeyService(apple, "power")) // no such key; the card uses a custom ApplePower button
    }

    @Test fun onlyNamesTheHaCardDrawsForThatPlatform() {
        // universal-remote-card skips these, so a stray "rewind" row on an Apple TV remote shows nothing in HA.
        listOf("rewind", "fast_forward", "back").forEach { assertNull(it, defaultKeyService(apple, it)) }
        listOf("previous", "next").forEach { assertNull(it, defaultKeyService(fire, it)) }
    }

    @Test fun swipeNeedsAStepAndPicksTheBiggerAxis() {
        assertNull(swipeDirection(20f, -10f, 28f))
        assertEquals("right", swipeDirection(30f, 10f, 28f))
        assertEquals("up", swipeDirection(5f, -29f, 28f))
        assertEquals("left", swipeDirection(-40f, 39f, 28f))
    }

    @Test fun customActionsAndText() {
        val plex = remoteAct(fire, customAction(fire, "Plex")!!.getJSONObject("tap_action")) as Act.Service
        assertEquals("script" to "pi_plex", plex.domain to plex.service)
        assertEquals("input text 'hello%sworld'", cmd(textService(fire, "hello world")))
        val appleText = textService(apple, "hi")!!
        assertEquals("apple_tv" to "set_keyboard_text", appleText.domain to appleText.service)
        assertTrue(appleText.data!!.getString("config_entry_id") == "abc")
    }

    @Test fun nullAndBlankNamesAreGaps() {
        val row = JSONArray("""[null, "previous", "", "  ", "play_pause", ["volume_up", null]]""")
        assertEquals(listOf(true, false, true, true, false, false), (0 until row.length()).map { isGap(row, it) })
        assertTrue(isGap(row.getJSONArray(5), 1))
    }
}

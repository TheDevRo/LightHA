package home.kiosk

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
        val back = defaultKeyService(apple, "back")!!
        assertEquals("remote" to "send_command", back.domain to back.service)
        assertEquals("menu", cmd(back))
        assertEquals("select", cmd(defaultKeyService(apple, "center")))
        assertNull(defaultKeyService(apple, "power")) // no such key; the card uses a custom ApplePower button
    }

    @Test fun customActionsAndText() {
        val plex = remoteAct(fire, customAction(fire, "Plex")!!.getJSONObject("tap_action")) as Act.Service
        assertEquals("script" to "pi_plex", plex.domain to plex.service)
        assertEquals("input text 'hello%sworld'", cmd(textService(fire, "hello world")))
        val appleText = textService(apple, "hi")!!
        assertEquals("apple_tv" to "set_keyboard_text", appleText.domain to appleText.service)
        assertTrue(appleText.data!!.getString("config_entry_id") == "abc")
    }
}

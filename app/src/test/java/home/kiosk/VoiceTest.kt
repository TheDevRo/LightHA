package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTest {
    private fun ev(json: String) = JSONObject(json)

    @Test fun fullTurn() {
        var s = VoiceState()
        s = voiceStep(s, ev("""{"type":"run-start","data":{"pipeline":"x","runner_data":{"stt_binary_handler_id":1,"timeout":300}}}"""))
        assertEquals(1, s.handler)
        s = voiceStep(s, ev("""{"type":"stt-vad-end","data":{}}"""))
        assertEquals(VoicePhase.LISTENING, s.phase)
        s = voiceStep(s, ev("""{"type":"stt-end","data":{"stt_output":{"text":" Turn on the office lights"}}}"""))
        assertEquals(VoicePhase.THINKING, s.phase)
        assertEquals("Turn on the office lights", s.heard)
        s = voiceStep(s, ev("""{"type":"intent-end","data":{"intent_output":{"response":{"speech":{"plain":{"speech":"Turned on the lights"}}}}}}"""))
        assertEquals("Turned on the lights", s.reply)
        s = voiceStep(s, ev("""{"type":"tts-end","data":{"tts_output":{"url":"/api/tts_proxy/abc.mp3","mime_type":"audio/mpeg"}}}"""))
        s = voiceStep(s, ev("""{"type":"run-end","data":null}"""))
        assertEquals(VoicePhase.SPEAKING, s.phase) // playback still to come
        assertEquals("/api/tts_proxy/abc.mp3", s.ttsUrl)
    }

    @Test fun nothingHeard() {
        val s = voiceStep(VoiceState(handler = 1), ev("""{"type":"error","data":{"code":"stt-no-text-recognized","message":"No text recognized"}}"""))
        assertEquals(VoicePhase.ERROR, s.phase)
        assertEquals("Didn't catch that.", s.reply)
        assertEquals(VoicePhase.ERROR, voiceStep(s, ev("""{"type":"run-end"}""")).phase)
    }
}

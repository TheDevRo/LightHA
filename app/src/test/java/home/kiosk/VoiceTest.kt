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

    @Test fun mp3FrameHeaders() {
        assertEquals(Mp3Frame(192, 24000, 1, 576), mp3Frame(0xFF, 0xF3, 0x84, 0xC4)) // MPEG-2, 64 kbps, mono
        assertEquals(Mp3Frame(384, 24000, 1, 576), mp3Frame(0xFF, 0xF3, 0xC4, 0xC4)) // what Pocket TTS via HA sends
        assertEquals(Mp3Frame(417, 44100, 2, 1152), mp3Frame(0xFF, 0xFB, 0x90, 0x64)) // MPEG-1, 128 kbps, joint stereo
        assertEquals(418, mp3Frame(0xFF, 0xFB, 0x92, 0x64)?.length) // padding bit
        assertEquals(null, mp3Frame(0x49, 0x44, 0x33, 0x04)) // "ID3", not a frame
        assertEquals(null, mp3Frame(0xFF, 0xEB, 0x90, 0x64)) // reserved version
        assertEquals(null, mp3Frame(0xFF, 0xFD, 0x90, 0x64)) // Layer II
        assertEquals(null, mp3Frame(0xFF, 0xFF, 0x90, 0x64)) // Layer I
        assertEquals(null, mp3Frame(0xFF, 0xFB, 0x00, 0x64)) // free bitrate
        assertEquals(null, mp3Frame(0xFF, 0xFB, 0xF0, 0x64)) // bad bitrate
        assertEquals(null, mp3Frame(0xFF, 0xFB, 0x9C, 0x64)) // reserved sample rate
    }

    @Test fun id3TagSize() {
        fun head(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertEquals(10 + 35, id3Size(head(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 0x23)))
        assertEquals(10 + 0x81, id3Size(head(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 1, 1))) // 7 bits per byte
        assertEquals(10 + 1 + 10, id3Size(head(0x49, 0x44, 0x33, 4, 0, 0x10, 0, 0, 0, 1))) // footer
        assertEquals(0, id3Size(head(0xFF, 0xF3, 0xC4, 0xC4, 0, 0, 0, 0, 0, 0)))
        assertEquals(0, id3Size(head(0x49, 0x44, 0x33)))
    }
}

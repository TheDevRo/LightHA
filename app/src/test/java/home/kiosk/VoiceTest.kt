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

    @Test fun finishedSpeakingMatchesHa() {
        assertEquals(0.7f, finishedSpeakingSeconds(0))
        assertEquals(1.25f, finishedSpeakingSeconds(1))
        assertEquals(0.25f, finishedSpeakingSeconds(-1))
    }

    /** Chunks of 32 ms until [process] says the command is over; null if it never does within [max]. */
    private fun chunksUntilDone(end: EndOfSpeech, probs: Sequence<Float>, max: Int = 1000): Int? {
        probs.take(max).forEachIndexed { i, p -> if (!end.process(0.032f, p)) return i + 1 }
        return null
    }

    private fun speechThenSilence(speechChunks: Int) = generateSequence(0) { it + 1 }.map { if (it < speechChunks) 0.9f else 0f }

    @Test fun silenceAloneNeverEnds() {
        // HA's segmenter times out after 15 s here; Voice's own 15 s mic limit covers that.
        assertEquals(null, chunksUntilDone(EndOfSpeech(0.7f), generateSequence { 0f }))
    }

    @Test fun silenceLengthSetsTheCutOff() {
        // 1.5 s of speech, then silence: aggressive ends after ~0.25 s of it, default ~0.7 s, relaxed ~1.25 s.
        val speech = 47
        assertEquals(speech + 8, chunksUntilDone(EndOfSpeech(0.25f), speechThenSilence(speech)))
        assertEquals(speech + 22, chunksUntilDone(EndOfSpeech(0.7f), speechThenSilence(speech)))
        assertEquals(speech + 40, chunksUntilDone(EndOfSpeech(1.25f), speechThenSilence(speech)))
    }

    @Test fun shortCommandsLastAtLeastASecond() {
        // 0.4 s of speech: the command still runs a second before silence can end it.
        assertEquals(32, chunksUntilDone(EndOfSpeech(0.25f), speechThenSilence(13)))
    }

    @Test fun pauseShorterThanTheSilenceKeepsListening() {
        // Speech, a 0.5 s pause, then more speech: relaxed keeps listening, aggressive stops in the pause.
        val probs = (List(40) { 0.9f } + List(16) { 0f } + List(40) { 0.9f } + List(200) { 0f }).asSequence()
        assertEquals(40 + 8, chunksUntilDone(EndOfSpeech(0.25f), probs))
        assertEquals(40 + 16 + 40 + 40, chunksUntilDone(EndOfSpeech(1.25f), probs))
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

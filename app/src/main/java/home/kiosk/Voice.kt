package home.kiosk

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

enum class VoicePhase { LISTENING, THINKING, SPEAKING, DONE, ERROR }

/** One push-to-talk turn. [handler] is the byte HA wants in front of each audio frame (-1 until run-start). */
data class VoiceState(
    val phase: VoicePhase = VoicePhase.LISTENING, val heard: String = "", val reply: String = "",
    val handler: Int = -1, val ttsUrl: String? = null,
)

/** Folds one assist_pipeline/run event into the state. */
fun voiceStep(s: VoiceState, event: JSONObject): VoiceState {
    val d = event.optJSONObject("data") ?: JSONObject()
    return when (event.optString("type")) {
        "run-start" -> s.copy(handler = d.optJSONObject("runner_data")?.optInt("stt_binary_handler_id", -1) ?: -1)
        "stt-end" -> s.copy(phase = VoicePhase.THINKING, heard = d.optJSONObject("stt_output")?.optString("text").orEmpty().trim())
        "intent-end" -> s.copy(
            reply = d.optJSONObject("intent_output")?.optJSONObject("response")?.optJSONObject("speech")
                ?.optJSONObject("plain")?.optString("speech").orEmpty(),
        )
        "tts-end" -> s.copy(phase = VoicePhase.SPEAKING, ttsUrl = d.optJSONObject("tts_output")?.optString("url")?.takeIf { it.isNotEmpty() })
        // HA ends the run as soon as the reply audio is ready; playback (SPEAKING) finishes on our side.
        "run-end" -> if (s.phase == VoicePhase.LISTENING || s.phase == VoicePhase.THINKING) s.copy(phase = VoicePhase.DONE) else s
        "error" -> s.copy(
            phase = VoicePhase.ERROR,
            reply = if (d.optString("code") == "stt-no-text-recognized") "Didn't catch that." else d.optString("message", "Something went wrong."),
        )
        else -> s
    }
}

/**
 * Push-to-talk Assist: streams the mic to an HA pipeline over the existing WebSocket, shows what was heard and the
 * answer, and plays the spoken reply. HA's own voice detection decides when you've stopped talking.
 */
class Voice(private val ha: HaClient, private val baseUrl: String, private val scope: CoroutineScope, private val settings: Settings) {
    private val _state = MutableStateFlow<VoiceState?>(null)
    /** null = nothing on screen. */
    val state: StateFlow<VoiceState?> = _state
    private var job: Job? = null

    fun stop() {
        job?.cancel()
        _state.value = null
    }

    @SuppressLint("MissingPermission") // Settings only shows the mic button once RECORD_AUDIO is granted
    fun start(pipeline: String) {
        job?.cancel()
        _state.value = VoiceState()
        job = scope.launch {
            // Whatever goes wrong inside (a hung reply, a mic that won't start), the turn ends and the overlay goes
            // away, so the wake word gets the mic back.
            val ok = runCatching { withTimeoutOrNull(TURN_MS) { turn(pipeline) } }
            if (ok.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch // stop() or a newer turn
            if (ok.isFailure || ok.getOrNull() == null) {
                Log.w(TAG, "turn ended badly: ${ok.exceptionOrNull() ?: "timed out"}")
                _state.update { it?.copy(phase = VoicePhase.ERROR, reply = "Something went wrong. Try again.") }
                delay(3_000)
            }
            _state.value = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun turn(pipeline: String) {
        run {
            if (settings.micBroken) { fail(MIC_BROKEN); return }
            val sub = ha.subscribe("assist_pipeline/run", {
                put("start_stage", "stt")
                put("end_stage", "tts")
                put("input", JSONObject().put("sample_rate", RATE))
                if (pipeline.isNotEmpty()) put("pipeline", pipeline)
            }, once = true) { e -> _state.update { it?.let { s -> voiceStep(s, e) } } }
            var mic: AudioRecord? = null
            try {
                val handler = withTimeoutOrNull(5_000) { _state.first { it == null || it.handler >= 0 || it.phase == VoicePhase.ERROR } }?.handler
                if (handler == null || handler < 0) { fail("Home Assistant didn't start listening."); return }

                val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 3200)
                mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                if (mic.state != AudioRecord.STATE_INITIALIZED) { fail("Microphone unavailable."); return }
                withContext(Dispatchers.IO) {
                    mic.startRecording()
                    val buf = ByteArray(1024) // 32 ms of 16 kHz 16-bit mono
                    val until = SystemClock.elapsedRealtime() + 15_000
                    while (_state.value?.phase == VoicePhase.LISTENING && SystemClock.elapsedRealtime() < until) {
                        val n = mic.read(buf, 0, buf.size)
                        if (n < 0) { settings.micBroken = true; break } // audio service died; see Settings.micBroken
                        if (n > 0) ha.sendBinary(byteArrayOf(handler.toByte()) + buf.copyOf(n))
                    }
                    mic.stop()
                    ha.sendBinary(byteArrayOf(handler.toByte())) // handler byte alone = end of audio
                }
                mic.release(); mic = null

                val done = withTimeoutOrNull(30_000) { _state.first { it == null || it.phase >= VoicePhase.SPEAKING } }
                if (settings.micBroken) { fail(MIC_BROKEN); return }
                if (done == null) { fail("Home Assistant didn't answer."); return }
                done.ttsUrl?.let { url ->
                    if (withTimeoutOrNull(PLAY_MS) { play(if (url.startsWith("http")) url else baseUrl.trimEnd('/') + url) } == null) Log.w(TAG, "tts playback timed out")
                }
                _state.update { it?.takeIf { s -> s.phase == VoicePhase.ERROR } ?: it?.copy(phase = VoicePhase.DONE) }
                delay(4_000)
            } finally {
                mic?.release()
                sub.cancel()
            }
        }
    }

    private suspend fun fail(message: String) {
        _state.update { it?.copy(phase = VoicePhase.ERROR, reply = message) }
        delay(4_000)
    }

    /** Plays the reply and returns when it's done (or failed). */
    private suspend fun play(url: String) = suspendCancellableCoroutine { cont ->
        val p = MediaPlayer()
        cont.invokeOnCancellation { p.release() }
        val finish = { if (cont.isActive) { p.release(); cont.resume(Unit) } }
        runCatching {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(url)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { finish() }
            p.setOnErrorListener { _, what, extra -> Log.w(TAG, "tts playback $what/$extra"); finish(); true }
            p.prepareAsync()
        }.onFailure { Log.w(TAG, "tts playback: $it"); finish() }
    }

    companion object {
        private const val TAG = "Voice"
        private const val RATE = 16_000
        /** Whole turn, listening through reply: 15 s mic + 30 s answer + reply + 4 s on screen fits. */
        private const val TURN_MS = 90_000L
        private const val PLAY_MS = 30_000L
        const val MIC_BROKEN = "This tablet's microphone crashed Android's audio service, so it's switched off. Choose a wake word or the Assist button again in Settings to retry."
    }
}

private val Amber = Color(0xFFFFB74D)

/** Bottom card for a voice turn; tapping anywhere cancels it. */
@Composable
fun VoiceOverlay(s: VoiceState, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Rounded.Mic, null, tint = if (s.phase == VoicePhase.LISTENING) Amber else TextDim, modifier = Modifier.size(36.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    s.phase == VoicePhase.LISTENING -> Text("Listening…", color = Color.White, fontSize = 18.sp)
                    s.heard.isNotEmpty() -> Text("“${s.heard}”", color = TextDim, fontSize = 15.sp)
                }
                when {
                    s.phase == VoicePhase.ERROR -> Text(s.reply, color = Color(0xFFE57373), fontSize = 17.sp)
                    s.reply.isNotEmpty() -> Text(s.reply, color = Color.White, fontSize = 17.sp)
                    s.phase == VoicePhase.THINKING -> Text("…", color = Color.White, fontSize = 17.sp)
                }
            }
        }
    }
}

package home.kiosk

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.math.sin
import kotlin.math.sqrt

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
class Voice(private val ha: HaClient, private val baseUrl: String, private val scope: CoroutineScope, private val settings: Settings, private val chime: () -> Unit) {
    private val _state = MutableStateFlow<VoiceState?>(null)
    /** null = nothing on screen. */
    val state: StateFlow<VoiceState?> = _state
    private val _level = MutableStateFlow(0f)
    /** Loudness of the mic while listening, 0..1, for the overlay's bar. */
    val level: StateFlow<Float> = _level
    private var job: Job? = null

    fun stop() {
        job?.cancel()
        _state.value = null
    }

    @SuppressLint("MissingPermission") // Settings only shows the mic button once RECORD_AUDIO is granted
    fun start(pipeline: String) {
        job?.cancel()
        _state.value = VoiceState()
        chime()
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
                // Lets HA's timers ("set a 10 minute timer") ring on this tablet; see MobileApp.deviceIdFor.
                settings.mobileDeviceId.takeIf { it.isNotEmpty() }?.let { put("device_id", it) }
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
                        if (n > 0) {
                            ha.sendBinary(byteArrayOf(handler.toByte()) + buf.copyOf(n))
                            _level.value = loudness(buf, n)
                        }
                    }
                    _level.value = 0f
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

    /**
     * Says [text] in the pipeline's voice and shows it in the bubble: HA's `TTS` notify message, as the Companion app
     * takes it. Skipped while a turn is running; a turn started meanwhile cuts it off.
     */
    fun say(text: String, pipeline: String) {
        if (_state.value != null || text.isBlank()) return
        val mine = VoiceState(phase = VoicePhase.SPEAKING, reply = text)
        _state.value = mine
        job = scope.launch {
            val url = CompletableDeferred<String?>()
            val sub = ha.subscribe("assist_pipeline/run", {
                put("start_stage", "tts")
                put("end_stage", "tts")
                put("input", JSONObject().put("text", text))
                if (pipeline.isNotEmpty()) put("pipeline", pipeline)
            }, once = true) { e ->
                when (e.optString("type")) {
                    "tts-end" -> url.complete(e.optJSONObject("data")?.optJSONObject("tts_output")?.optString("url"))
                    "error", "run-end" -> url.complete(null)
                }
            }
            try {
                withTimeoutOrNull(15_000) { url.await() }?.takeIf { it.isNotEmpty() }?.let {
                    withTimeoutOrNull(PLAY_MS) { play(if (it.startsWith("http")) it else baseUrl.trimEnd('/') + it) }
                }
                delay(2_000)
            } finally {
                sub.cancel()
                _state.compareAndSet(mine, null) // a turn that cut in keeps its bubble
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
            val usage = if (Build.VERSION.SDK_INT >= 26) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_MEDIA
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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


/** RMS of little-endian PCM16, scaled so normal speech fills most of 0..1. */
fun loudness(pcm: ByteArray, n: Int): Float {
    var sum = 0.0
    var i = 0
    while (i + 1 < n) {
        val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
        sum += v * v
        i += 2
    }
    val rms = sqrt(sum / maxOf(1, n / 2)) / 32768.0
    return (rms * 8).toFloat().coerceIn(0f, 1f)
}

private val BarColors = listOf(Amber, Color(0xFFFF7A45), Color(0xFFE85D9E), Color(0xFF4FC3F7), Amber)

/**
 * Docked voice bubble: sits at the bottom over the dashboard (which stays usable) with a glowing gradient bar
 * along its foot. The bar slides slowly while listening, fast while thinking and steadily while speaking, and
 * swells with your voice. Tapping the bubble cancels the turn.
 */
@Composable
fun VoiceOverlay(s: VoiceState, level: Float, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth().shadow(24.dp, RoundedCornerShape(28.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                .background(CardBg).clickable(onClick = onClose).padding(start = 24.dp, end = 24.dp, top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                s.phase == VoicePhase.LISTENING -> Text("Listening…", color = Color.White, fontSize = 18.sp)
                s.heard.isNotEmpty() -> Text("“${s.heard}”", color = TextDim, fontSize = 15.sp)
            }
            when {
                s.phase == VoicePhase.ERROR -> Text(s.reply, color = Color(0xFFE57373), fontSize = 17.sp)
                s.reply.isNotEmpty() -> Text(s.reply, color = Color.White, fontSize = 17.sp)
                s.phase == VoicePhase.THINKING -> Text("…", color = Color.White, fontSize = 17.sp)
            }
            VoiceBar(s.phase, level, Modifier.fillMaxWidth().height(30.dp))
        }
    }
}

@Composable
private fun VoiceBar(phase: VoicePhase, level: Float, modifier: Modifier) {
    val period = when (phase) { VoicePhase.THINKING -> 0.5f; VoicePhase.SPEAKING -> 2f; else -> 3f }
    var shift by remember { mutableFloatStateOf(0f) }
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(period) {
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) shift = (shift + (now - last) / 1e9f / period) % 1f
            last = now
            clock = now / 1e9f
        }
    }
    val heard by animateFloatAsState(level, tween(90), label = "level")
    Canvas(modifier) {
        // The reply's audio isn't measured, so speaking gets a gentle pulse instead.
        val l = when (phase) {
            VoicePhase.LISTENING -> heard
            VoicePhase.SPEAKING -> 0.3f + 0.2f * sin(clock * 7f)
            VoicePhase.THINKING -> 0.15f
            else -> 0f
        }
        val h = 6.dp.toPx() * (1 + 2 * l)
        val bottom = size.height - 8.dp.toPx()
        val x = -size.width * shift
        val brush = if (phase == VoicePhase.ERROR) SolidColor(Color(0xFFE57373))
        else Brush.linearGradient(BarColors, Offset(x, 0f), Offset(x + size.width, 0f), TileMode.Repeated)
        // Glow: a few wider, fainter copies under the bar, brighter as the voice gets louder.
        for (i in 3 downTo 1) {
            val g = i * (2.dp.toPx() + 3.dp.toPx() * l)
            drawRoundRect(brush, Offset(-g, bottom - h - g), Size(size.width + 2 * g, h + 2 * g), CornerRadius(h / 2 + g), alpha = (0.1f + 0.25f * l) / i)
        }
        drawRoundRect(brush, Offset(0f, bottom - h), Size(size.width, h), CornerRadius(h / 2))
    }
}

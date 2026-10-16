package home.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.Closeable
import java.nio.ByteBuffer

/** Wake words bundled in assets/wakeword (id to spoken name). */
val WAKE_WORDS = listOf("okay_nabu" to "Okay Nabu", "hey_jarvis" to "Hey Jarvis", "hey_mycroft" to "Hey Mycroft")

/** True while the tablet's mic is open for the wake word. */
val wakeListening = MutableStateFlow(false)
/** Why the wake word stopped on its own (null = fine). Shown in Settings. */
val wakeError = MutableStateFlow<String?>(null)

/** A model's manifest (the ESPHome micro-wake-word-models JSON next to the .tflite). */
data class WakeModel(val id: String, val cutoff: Float, val stepMs: Int, val window: Int) {
    companion object {
        fun parse(id: String, json: String) = JSONObject(json).getJSONObject("micro").let {
            WakeModel(id, it.getDouble("probability_cutoff").toFloat(), it.getInt("feature_step_size"), it.getInt("sliding_window_size"))
        }
    }
}

/**
 * The model's own cutoff, nudged by the sensitivity setting: 1 low (fewer false wakes, e.g. near a TV),
 * 0 as shipped, -1 high (hears you from further away).
 */
fun wakeCutoff(base: Float, sensitivity: Int) = (base + when (sensitivity) { 1 -> 0.03f; -1 -> -0.1f; else -> 0f }).coerceIn(0.5f, 0.99f)

/** JNI wrapper over the native microWakeWord engine in src/main/cpp. Not thread-safe: one per listening thread. */
class MicroWakeWord(model: ByteBuffer, stepMs: Int, cutoff: Float, window: Int) : Closeable {
    private var handle = nativeCreate(model, 16_000, stepMs, cutoff, window)

    init { check(handle != 0L) { "wake word engine failed to load the model" } }

    /** 16 kHz mono PCM; true when the wake word was just heard. */
    fun process(samples: ShortArray) = handle != 0L && nativeProcessAudio(handle, samples)

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    companion object {
        init { System.loadLibrary("microwakeword") }

        @JvmStatic external fun nativeCreate(model: ByteBuffer, sampleRate: Int, stepMs: Int, cutoff: Float, window: Int): Long
        @JvmStatic external fun nativeProcessAudio(handle: Long, samples: ShortArray): Boolean
        @JvmStatic external fun nativeReset(handle: Long)
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}

/**
 * Listens on its own thread until the wake word is heard, then lets go of the mic (so Assist can have it) and
 * calls [onWake] on the main thread. Start it again for the next one.
 */
class WakeWordListener(private val context: Context, private val onMicBroken: () -> Unit, private val onWake: () -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    private var config: Pair<String, Int>? = null

    /** No-op while already listening with the same settings, so it's safe to call often (see [MainActivity]). */
    fun start(id: String, sensitivity: Int) {
        if (thread?.isAlive == true && config == (id to sensitivity)) return
        stop()
        config = id to sensitivity
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (id.isEmpty() || !granted) return
        running = true
        wakeError.value = null
        thread = Thread({
            // A busy or flaky mic (still held for a moment after an Assist turn) is retried, not given up on.
            while (running && listen(id, sensitivity) == Outcome.RETRY) runCatching { Thread.sleep(2_000) }
        }, "wake-word").apply { start() }
    }

    /** Returns once the mic is released. */
    fun stop() {
        running = false
        config = null
        thread?.join(1_000)
        thread = null
    }

    private enum class Outcome { DONE, RETRY }

    @SuppressLint("MissingPermission") // checked in start()
    private fun listen(id: String, sensitivity: Int): Outcome {
        var mic: AudioRecord? = null
        try {
            val model = WakeModel.parse(id, context.assets.open("wakeword/$id.json").bufferedReader().use { it.readText() })
            val bytes = context.assets.open("wakeword/$id.tflite").use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
            MicroWakeWord(buffer, model.stepMs, wakeCutoff(model.cutoff, sensitivity), model.window).use { engine ->
                val size = maxOf(AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 3200)
                mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                val m = mic!!
                if (m.state != AudioRecord.STATE_INITIALIZED) { wakeError.value = "Microphone unavailable, retrying."; return Outcome.RETRY }
                m.startRecording()
                if (m.recordingState != AudioRecord.RECORDSTATE_RECORDING) { wakeError.value = "Microphone busy, retrying."; return Outcome.RETRY }
                wakeError.value = null
                wakeListening.value = true
                val chunk = ShortArray(160) // 10 ms
                while (running) {
                    val n = m.read(chunk, 0, chunk.size)
                    // Some ROMs (LineageOS 14.1 on the Fire HD6) crash Android's audio service on any capture. Don't retry:
                    // every new AudioRecord crashes it again.
                    if (n < 0) { Log.w(TAG, "mic read error $n"); Handler(Looper.getMainLooper()).post(onMicBroken); return Outcome.DONE }
                    if (n > 0 && engine.process(if (n == chunk.size) chunk else chunk.copyOf(n))) {
                        Log.i(TAG, "heard $id")
                        running = false
                        m.release(); mic = null // free the mic before Assist opens it
                        Handler(Looper.getMainLooper()).post(onWake)
                    }
                }
            }
            return Outcome.DONE
        } catch (e: UnsatisfiedLinkError) { // a broken native lib must not take the app down, and won't fix itself
            Log.w(TAG, "wake word stopped: $e")
            wakeError.value = "Wake word engine failed to load."
            return Outcome.DONE
        } catch (e: Exception) {
            Log.w(TAG, "wake word error, retrying: $e")
            wakeError.value = "Wake word error, retrying: ${e.message}"
            return Outcome.RETRY
        } finally {
            mic?.release()
            wakeListening.value = false
        }
    }

    companion object { private const val TAG = "WakeWord" }
}

package home.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Another tablet taking calls, as heard from its beacon. */
data class Peer(val id: String, val name: String, val host: String, val port: Int, val seen: Long)

enum class CallPhase { CALLING, RINGING, LIVE, ENDED }

/** What the call screen shows. [canTalk] is false when this tablet's mic can't be used: the call is listen-only. */
data class CallState(val phase: CallPhase, val peer: String, val note: String = "", val talking: Boolean = false, val canTalk: Boolean = true)

/** The beacon a tablet taking calls sends every few seconds; null for anything else on the port. */
fun parseBeacon(text: String, host: String, now: Long): Peer? = runCatching {
    val o = JSONObject(text)
    if (o.optString("lightha") != "intercom") return null
    Peer(o.getString("id"), o.getString("name"), host, o.getInt("port"), now)
}.getOrNull()

/**
 * Tablet-to-tablet intercom on the LAN. Making and taking calls are separate switches: a tablet that only calls
 * never opens a port or announces itself, so nobody can reach it.
 *
 * Wire: the callee sends a UDP beacon to [BEACON_PORT]. A call is one TCP connection to the callee's [PORT]:
 * the caller sends a JSON line with its name, the callee answers `ringing` or `busy`, then `answer` or `decline`
 * when someone taps. After `answer` both sides send raw 16 kHz mono PCM16 while their talk button is held.
 * Closing the socket ends the call. Push to talk only, so it never needs echo cancellation.
 */
class Intercom(private val context: Context, scope: CoroutineScope, private val settings: Settings, private val busy: () -> Boolean, private val onRing: () -> Unit) {
    // A port already taken or a dropped network must never take the app down.
    private val io = scope + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "stopped: $e") }
    private val _state = MutableStateFlow<CallState?>(null)
    /** null = no call on screen. */
    val state: StateFlow<CallState?> = _state
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private val id = UUID.randomUUID().toString()
    private var receiving: Job? = null
    private var browsing: Job? = null
    private var socket: Socket? = null
    private var answer: (() -> Unit)? = null
    @Volatile private var talking = false

    /** Starts or stops each half to match the settings. Safe to call often. */
    fun apply(call: Boolean, receive: Boolean) {
        if (call && browsing == null) browsing = io.launch { browse() }
        if (!call) { browsing?.cancel(); browsing = null; _peers.value = emptyList() }
        if (receive && receiving == null) receiving = io.launch { launch { beacon() }; serve() }
        if (!receive) { receiving?.cancel(); receiving = null }
    }

    fun call(peer: Peer) {
        if (_state.value != null) return
        _state.value = CallState(CallPhase.CALLING, peer.name, "Connecting…")
        io.launch {
            val s = Socket()
            socket = s
            val outcome = runCatching {
                s.connect(InetSocketAddress(peer.host, peer.port), 3_000)
                s.soTimeout = RING_MS
                s.getOutputStream().write((JSONObject().put("name", settings.intercomName).toString() + "\n").toByteArray())
                val input = s.getInputStream()
                when (readLine(input)) {
                    "ringing" -> {}
                    "busy" -> return@runCatching "${peer.name} is busy."
                    else -> return@runCatching "${peer.name} isn't taking calls."
                }
                _state.update { it?.copy(note = "Ringing…") }
                when (readLine(input)) {
                    "answer" -> { s.soTimeout = 0; live(s, peer.name); "Call ended." }
                    "decline" -> "${peer.name} declined."
                    else -> "No answer."
                }
            }
            end(outcome.getOrElse { if (socket == null) null else "Couldn't reach ${peer.name}." })
        }
    }

    fun answer() = answer?.invoke()

    /** Ends, cancels or declines whatever call is up. */
    fun hangup() {
        val s = socket
        val ringing = _state.value?.phase == CallPhase.RINGING
        socket = null
        _state.value = null
        io.launch {
            if (ringing) runCatching { s?.getOutputStream()?.write("decline\n".toByteArray()) }
            runCatching { s?.close() }
        }
    }

    fun talk(on: Boolean) {
        talking = on
        _state.update { it?.copy(talking = on) }
    }

    private suspend fun end(note: String?) {
        socket?.let { runCatching { it.close() } }
        socket = null
        talking = false
        answer = null
        if (note == null || _state.value == null) { _state.value = null; return }
        _state.value = _state.value?.copy(phase = CallPhase.ENDED, note = note, talking = false)
        delay(3_000)
        _state.update { if (it?.phase == CallPhase.ENDED) null else it }
    }

    private suspend fun browse() = DatagramSocket(null).use { udp ->
        udp.reuseAddress = true
        udp.bind(InetSocketAddress(BEACON_PORT))
        udp.soTimeout = 2_000
        val buf = ByteArray(512)
        while (coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            val p = DatagramPacket(buf, buf.size)
            val heard = runCatching { udp.receive(p); parseBeacon(String(p.data, 0, p.length), p.address.hostAddress.orEmpty(), now) }.getOrNull()
            _peers.update { list ->
                (list.filter { it.id != heard?.id && now - it.seen < PEER_TTL_MS } + listOfNotNull(heard?.takeIf { it.id != id })).sortedBy { it.name }
            }
        }
    }

    private suspend fun beacon() = DatagramSocket().use { udp ->
        udp.broadcast = true
        val to = InetAddress.getByName("255.255.255.255")
        while (coroutineContext.isActive) {
            val b = JSONObject().put("lightha", "intercom").put("id", id).put("name", settings.intercomName).put("port", PORT).toString().toByteArray()
            runCatching { udp.send(DatagramPacket(b, b.size, to, BEACON_PORT)) }.onFailure { Log.w(TAG, "beacon: $it") }
            delay(3_000)
        }
    }

    private suspend fun serve() = ServerSocket().use { server ->
        server.reuseAddress = true
        server.bind(InetSocketAddress(PORT))
        server.soTimeout = 2_000
        while (coroutineContext.isActive) {
            val s = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            io.launch { incoming(s) }
        }
    }

    private suspend fun incoming(s: Socket) {
        val name = runCatching {
            s.soTimeout = 5_000
            JSONObject(readLine(s.getInputStream()) ?: "").getString("name").take(40)
        }.getOrNull()
        if (name == null || _state.value != null || busy()) {
            runCatching { s.getOutputStream().write("busy\n".toByteArray()); s.close() }
            return
        }
        socket = s
        _state.value = CallState(CallPhase.RINGING, name, "Calling…")
        val answered = MutableStateFlow(false)
        answer = { answered.value = true }
        s.getOutputStream().write("ringing\n".toByteArray())
        withContext(Dispatchers.Main) { onRing() }
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        val outcome = runCatching {
            // Ring until answered, declined (hangup() closes the socket) or the caller gives up. The caller sends
            // nothing until answered, so a short read only ever times out or sees the caller hang up.
            val input = s.getInputStream()
            s.soTimeout = 200
            val start = System.currentTimeMillis()
            var rung = 0L
            while (!answered.value) {
                val now = System.currentTimeMillis()
                if (now - start > RING_MS) return@runCatching "Missed call from $name."
                if (now - rung > 2_000) { tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1_000); rung = now }
                val r = try { input.read() } catch (_: SocketTimeoutException) { 0 }
                if (r < 0) return@runCatching "Missed call from $name."
            }
            tone?.stopTone()
            s.getOutputStream().write("answer\n".toByteArray())
            s.soTimeout = 0
            live(s, name)
            "Call ended."
        }
        tone?.release()
        if (socket === s || socket == null) end(outcome.getOrElse { if (socket == null) null else "Call dropped." })
    }

    /** Plays what comes in and sends the mic while the talk button is held, until either side hangs up. */
    @SuppressLint("MissingPermission") // checked in micAllowed
    private suspend fun live(s: Socket, peer: String) = withContext(Dispatchers.IO) {
        val canTalk = micAllowed()
        _state.value = CallState(CallPhase.LIVE, peer, canTalk = canTalk)
        val out = s.getOutputStream()
        val track = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 2),
            AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        val sender = if (!canTalk) null else launch {
            val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 3200)
            val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            try {
                if (mic.state != AudioRecord.STATE_INITIALIZED) { _state.update { it?.copy(canTalk = false) }; return@launch }
                mic.startRecording()
                val buf = ByteArray(640) // 20 ms
                while (isActive) {
                    val n = mic.read(buf, 0, buf.size)
                    if (n < 0) { settings.micBroken = true; _state.update { it?.copy(canTalk = false) }; break } // see Settings.micBroken
                    if (n > 0 && talking) out.write(buf, 0, n)
                }
            } catch (e: Exception) {
                Log.w(TAG, "mic: $e")
            } finally {
                mic.release()
            }
        }
        try {
            track.play()
            val input = DataInputStream(s.getInputStream())
            val buf = ByteArray(640) // whole frames: TCP can split a sample across reads
            while (true) {
                input.readFully(buf)
                track.write(buf, 0, buf.size)
            }
        } catch (_: EOFException) {
        } catch (e: Exception) {
            if (socket != null) Log.w(TAG, "call: $e")
        } finally {
            sender?.cancel()
            sender?.join()
            track.release()
        }
    }

    private fun micAllowed() = !settings.micBroken &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** One `\n`-ended line, read a byte at a time so nothing after it is swallowed. null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (b.size() < 1_000) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return b.toString("UTF-8")
            b.write(c)
        }
        return null
    }

    companion object {
        private const val TAG = "Intercom"
        const val PORT = 47_811
        const val BEACON_PORT = 47_812
        private const val RATE = 16_000
        private const val RING_MS = 30_000
        private const val PEER_TTL_MS = 10_000L
    }
}

/** Full-screen call card, drawn over the dashboard and the screensaver. */
@Composable
fun IntercomOverlay(s: CallState, onAnswer: () -> Unit, onHangup: () -> Unit, onTalk: (Boolean) -> Unit) {
    val red = Color(0xFFE57373)
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures {} }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(CardBg).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Rounded.Call, null, tint = Amber, modifier = Modifier.size(40.dp))
            Text(s.peer, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            val note = when {
                s.phase != CallPhase.LIVE -> s.note
                !s.canTalk -> "Listen only: this tablet's mic is off."
                s.talking -> "Talking…"
                else -> "Hold the button to talk."
            }
            Text(note, color = TextDim, fontSize = 16.sp)
            when (s.phase) {
                CallPhase.RINGING -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onHangup, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("DECLINE") }
                    Button(onAnswer, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C784))) { Text("ANSWER") }
                }
                CallPhase.LIVE -> {
                    if (s.canTalk) Box(
                        Modifier.size(140.dp).clip(CircleShape).background(if (s.talking) Amber else Chip)
                            .pointerInput(Unit) { detectTapGestures(onPress = { onTalk(true); tryAwaitRelease(); onTalk(false) }) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Mic, "Hold to talk", tint = if (s.talking) CardBg else Color.White, modifier = Modifier.size(56.dp)) }
                    Button(onHangup, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("END") }
                }
                CallPhase.CALLING -> Button(onHangup, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("CANCEL") }
                CallPhase.ENDED -> TextButton(onHangup) { Text("CLOSE") }
            }
        }
    }
}

/** The header's intercom button opens this: every tablet currently taking calls. */
@Composable
fun IntercomPicker(peers: List<Peer>, onPick: (Peer) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = CardBg,
        title = { Text("Call a tablet", color = Color.White) },
        text = {
            Column {
                if (peers.isEmpty()) Text("Looking for tablets… Turn on Receive calls on another tablet to see it here.", color = TextDim, fontSize = 15.sp)
                peers.forEach { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(p) }.padding(vertical = 14.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Call, null, tint = Amber)
                        Text("   " + p.name, color = Color.White, fontSize = 18.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("CLOSE") } },
    )
}

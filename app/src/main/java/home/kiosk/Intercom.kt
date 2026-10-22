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
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.DoNotDisturbOn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Another tablet taking calls, as heard from its beacon. */
data class Peer(val id: String, val name: String, val host: String, val port: Int, val seen: Long, val dnd: Boolean = false)

enum class CallPhase { CALLING, RINGING, LIVE, ENDED }

/**
 * What the call screen shows. [canTalk] is false when this tablet's mic can't be used (listen only) and on the
 * tablets hearing an announcement. [announce] is set on both the announcer and its listeners; [outgoing] tells
 * them apart. [replyTo] is who "Call back" rings.
 */
data class CallState(
    val phase: CallPhase, val peer: String, val note: String = "", val talking: Boolean = false, val canTalk: Boolean = true,
    val announce: Boolean = false, val outgoing: Boolean = false, val missed: Boolean = false, val replyTo: Peer? = null,
)

/** The beacon a tablet taking calls sends every few seconds; null for anything else on the port. */
fun parseBeacon(text: String, host: String, now: Long): Peer? = runCatching {
    val o = JSONObject(text)
    if (o.optString("lightha") != "intercom") return null
    Peer(o.getString("id"), o.getString("name"), host, o.getInt("port"), now, o.optBoolean("dnd"))
}.getOrNull()

/** Do not disturb [mode]: 0 off, 1 on, 2 from [from] o'clock until [to] o'clock, across midnight when from > to. */
fun dndAt(mode: Int, from: Int, to: Int, hour: Int) = when (mode) {
    1 -> true
    2 -> if (from <= to) hour in from until to else hour >= from || hour < to
    else -> false
}

/** The Intercom sensor's state in HA. */
fun haStatus(s: CallState?) = when {
    s == null -> "idle"
    s.phase == CallPhase.CALLING -> "calling"
    s.phase == CallPhase.RINGING -> "ringing"
    s.phase == CallPhase.ENDED -> if (s.missed) "missed" else "idle"
    s.announce -> if (s.outgoing) "announcing" else "listening"
    else -> "in_call"
}

/**
 * Tablet-to-tablet intercom on the LAN. Making and taking calls are separate switches: a tablet that only calls
 * never opens a port or announces itself, so nobody can reach it.
 *
 * Wire: a tablet taking calls sends a UDP beacon to [BEACON_PORT]. A call is one TCP connection to its [PORT]: the
 * caller sends a JSON line `{name, kind: call|announce}`, the callee answers `ringing`, `listening` (announcement),
 * `busy`, `dnd` or `off`, then `answer` or `decline` for a call. After that both sides send raw 16 kHz mono PCM16
 * while their talk button is held. Closing the socket ends it. Push to talk only, so it never needs echo cancellation.
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
    @Volatile private var sockets: List<Socket> = emptyList()
    @Volatile private var answer: (() -> Unit)? = null
    @Volatile private var talking = false
    /** Bumped by every new call and every hang-up, so a finished call's cleanup never touches the next one. */
    private val gen = AtomicInteger()

    /** Starts or stops each half to match the settings. Safe to call often. */
    fun apply(call: Boolean, receive: Boolean) {
        if (call && browsing == null) browsing = io.launch { browse() }
        if (!call) { browsing?.cancel(); browsing = null; _peers.value = emptyList() }
        if (receive && receiving == null) receiving = io.launch { launch { beacon() }; serve() }
        if (!receive) { receiving?.cancel(); receiving = null }
    }

    fun dnd() = dndAt(settings.intercomDnd, settings.intercomDndFrom, settings.intercomDndTo, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))

    fun canTalk() = !settings.micBroken &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun idle() = _state.value.let { it == null || it.phase == CallPhase.ENDED }

    /** For Home Assistant: rings the tablet with this name. False when there's none or a call is already up. */
    fun callByName(name: String): Boolean {
        val p = _peers.value.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: return false
        if (!idle()) return false
        call(p)
        return true
    }

    fun call(peer: Peer) {
        if (!idle()) return
        val my = gen.incrementAndGet()
        _state.value = CallState(CallPhase.CALLING, peer.name, "Connecting…", outgoing = true, replyTo = peer)
        io.launch {
            val outcome = runCatching {
                val s = open(peer, "call")
                if (gen.get() != my) { s.close(); return@runCatching null } // cancelled while connecting
                sockets = listOf(s)
                val input = s.getInputStream()
                when (readLine(input)) {
                    "ringing" -> {}
                    "busy" -> return@runCatching "${peer.name} is busy."
                    "dnd" -> return@runCatching "${peer.name} is on Do not disturb."
                    else -> return@runCatching "${peer.name} isn't taking calls."
                }
                _state.update { it?.copy(note = "Ringing…") }
                when (readLine(input)) {
                    "answer" -> {
                        s.soTimeout = 0
                        _state.update { it?.copy(phase = CallPhase.LIVE, canTalk = canTalk()) }
                        live(listOf(s), play = true)
                        "Call ended."
                    }
                    "decline" -> "${peer.name} declined."
                    else -> "No answer."
                }
            }
            end(my, outcome.getOrElse { "Couldn't reach ${peer.name}." })
        }
    }

    /** One way to every tablet taking calls. Tablets on Do not disturb, in a call or with announcements off skip it. */
    fun announce() {
        if (!idle()) return
        val my = gen.incrementAndGet()
        _state.value = CallState(CallPhase.CALLING, "Everyone", "Connecting…", announce = true, outgoing = true)
        io.launch {
            val reached = _peers.value.filter { !it.dnd }.map { p ->
                async { runCatching { open(p, "announce").let { s -> if (readLine(s.getInputStream()) == "listening") p to s else { s.close(); null } } }.getOrNull() }
            }.awaitAll().filterNotNull()
            if (gen.get() != my) { reached.forEach { runCatching { it.second.close() } }; return@launch }
            if (reached.isEmpty()) { end(my, "No tablet is taking announcements right now."); return@launch }
            sockets = reached.map { it.second }
            reached.forEach { it.second.soTimeout = 0 }
            _state.update { it?.copy(phase = CallPhase.LIVE, canTalk = canTalk(), note = "To " + reached.joinToString { p -> p.first.name }) }
            runCatching { live(sockets, play = false) }
            end(my, "Announcement over.")
        }
    }

    fun answer() = answer?.invoke()

    /** Ends, cancels, declines or dismisses whatever is up. */
    fun hangup() {
        val socks = sockets
        val ringing = _state.value?.phase == CallPhase.RINGING
        gen.incrementAndGet()
        sockets = emptyList()
        answer = null
        talking = false
        _state.value = null
        io.launch {
            if (ringing) runCatching { socks.firstOrNull()?.getOutputStream()?.write("decline\n".toByteArray()) }
            socks.forEach { runCatching { it.close() } }
        }
    }

    /** Rings whoever the screen offers to call back. */
    fun callBack() {
        val p = _state.value?.replyTo ?: return
        hangup()
        call(p)
    }

    fun talk(on: Boolean) {
        talking = on
        _state.update { it?.copy(talking = on) }
    }

    private fun open(peer: Peer, kind: String) = Socket().apply {
        connect(InetSocketAddress(peer.host, peer.port), 3_000)
        soTimeout = RING_MS
        getOutputStream().write((JSONObject().put("name", settings.intercomName).put("kind", kind).toString() + "\n").toByteArray())
    }

    /** Shows [note] for a few seconds (a missed call longer), then clears the screen. null clears it now. */
    private suspend fun end(my: Int, note: String?, missed: Boolean = false) {
        if (gen.get() != my) return // hung up here, or a newer call owns the screen
        sockets.forEach { runCatching { it.close() } }
        sockets = emptyList()
        answer = null
        talking = false
        if (note == null || _state.value == null) { _state.value = null; return }
        _state.update { it?.copy(phase = CallPhase.ENDED, note = note, talking = false, missed = missed) }
        delay(if (missed) 30_000 else 4_000)
        if (gen.get() == my) _state.value = null
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
            val b = JSONObject().put("lightha", "intercom").put("id", id).put("name", settings.intercomName).put("port", PORT).put("dnd", dnd())
                .toString().toByteArray()
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
        val hello = runCatching { s.soTimeout = 5_000; JSONObject(readLine(s.getInputStream()) ?: "") }.getOrNull()
        val name = hello?.optString("name")?.take(40).orEmpty()
        val announcing = hello?.optString("kind") == "announce"
        val refuse = when {
            name.isEmpty() -> "busy"
            !idle() || busy() -> "busy"
            dnd() -> "dnd"
            announcing && !settings.intercomAnnouncements -> "off"
            else -> null
        }
        if (refuse != null) {
            runCatching { s.getOutputStream().write("$refuse\n".toByteArray()); s.close() }
            return
        }
        val my = gen.incrementAndGet()
        sockets = listOf(s)
        val from = Peer("", name, s.inetAddress.hostAddress.orEmpty(), PORT, 0)
        if (announcing) {
            _state.value = CallState(CallPhase.LIVE, name, "Announcement", canTalk = false, announce = true, replyTo = from)
            val ok = runCatching {
                s.getOutputStream().write("listening\n".toByteArray())
                s.soTimeout = 0
                withContext(Dispatchers.Main) { onRing() }
                chime()
                live(listOf(s), play = true)
            }.isSuccess
            end(my, if (ok) "Announcement from $name is over." else null)
            return
        }

        _state.value = CallState(CallPhase.RINGING, name, "Calling…", replyTo = from)
        val answered = MutableStateFlow(false)
        answer = { answered.value = true }
        val auto = settings.intercomAutoAnswer
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        val outcome = runCatching {
            s.getOutputStream().write("ringing\n".toByteArray())
            withContext(Dispatchers.Main) { onRing() }
            // Ring until answered, declined (hangup() closes the socket) or the caller gives up. The caller sends
            // nothing until answered, so a short read only ever times out or sees the caller hang up.
            val input = s.getInputStream()
            s.soTimeout = 200
            val start = System.currentTimeMillis()
            var rung = 0L
            if (auto) tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            while (!answered.value) {
                val now = System.currentTimeMillis()
                if (auto) {
                    val left = ((AUTO_ANSWER_MS - (now - start) + 999) / 1_000).toInt()
                    if (left <= 0) break
                    _state.update { it?.copy(note = "Answering in $left…") }
                } else if (now - rung > 2_000) { tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1_000); rung = now }
                if (now - start > RING_MS) return@runCatching "missed"
                val r = try { input.read() } catch (_: SocketTimeoutException) { 0 }
                if (r < 0) return@runCatching "missed"
            }
            tone?.stopTone()
            s.getOutputStream().write("answer\n".toByteArray())
            s.soTimeout = 0
            _state.update { it?.copy(phase = CallPhase.LIVE, canTalk = canTalk()) }
            live(listOf(s), play = true)
            "Call ended."
        }
        tone?.release()
        when (val r = outcome.getOrElse { "Call dropped." }) {
            "missed" -> end(my, "Missed call from $name.", missed = true)
            else -> end(my, r)
        }
    }

    private fun chime() = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80).apply { startTone(ToneGenerator.TONE_PROP_BEEP2, 400); Thread.sleep(450); release() }
    }

    /**
     * Plays what comes in ([play]) and sends the mic to every socket while the talk button is held, until each socket
     * closes or hangup() closes them.
     */
    @SuppressLint("MissingPermission") // canTalk() checks it
    private suspend fun live(socks: List<Socket>, play: Boolean) = withContext(Dispatchers.IO) {
        val outs = socks.map { it.getOutputStream() }.toMutableList()
        val track = if (!play) null else AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 2),
            AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        val sender = if (_state.value?.canTalk != true) null else launch {
            val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 3200)
            val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            try {
                if (mic.state != AudioRecord.STATE_INITIALIZED) { _state.update { it?.copy(canTalk = false) }; return@launch }
                mic.startRecording()
                val buf = ByteArray(640) // 20 ms
                while (isActive) {
                    val n = mic.read(buf, 0, buf.size)
                    if (n < 0) { settings.micBroken = true; _state.update { it?.copy(canTalk = false) }; break } // see Settings.micBroken
                    if (n > 0 && talking) outs.removeAll { o -> runCatching { o.write(buf, 0, n) }.isFailure }
                }
            } catch (e: Exception) {
                Log.w(TAG, "mic: $e")
            } finally {
                mic.release()
            }
        }
        try {
            track?.play()
            socks.map { s ->
                launch {
                    runCatching {
                        val input = DataInputStream(s.getInputStream())
                        val buf = ByteArray(640) // whole frames: TCP can split a sample across reads
                        while (true) {
                            input.readFully(buf)
                            track?.write(buf, 0, buf.size)
                        }
                    }
                }
            }.joinAll()
        } finally {
            sender?.cancelAndJoin()
            track?.release()
        }
    }

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
        private const val AUTO_ANSWER_MS = 3_000L
        private const val PEER_TTL_MS = 10_000L
    }
}

/** Full-screen call card, drawn over the dashboard, the screensaver and Settings. */
@Composable
fun IntercomOverlay(s: CallState, intercom: Intercom) {
    val red = Color(0xFFE57373)
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures {} }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(CardBg).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(if (s.announce) Icons.Rounded.Campaign else Icons.Rounded.Call, null, tint = Amber, modifier = Modifier.size(40.dp))
            Text(s.peer, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            val note = when {
                s.phase != CallPhase.LIVE -> s.note
                s.announce && !s.outgoing -> "Announcement"
                !s.canTalk -> "Listen only: this tablet's mic is off."
                s.talking -> if (s.announce) s.note else "Talking…"
                else -> if (s.announce) s.note + ". Hold the button to talk." else "Hold the button to talk."
            }
            Text(note, color = TextDim, fontSize = 16.sp)
            when (s.phase) {
                CallPhase.RINGING -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(intercom::hangup, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("DECLINE") }
                    Button(intercom::answer, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C784))) { Text("ANSWER") }
                }
                CallPhase.LIVE -> {
                    if (s.canTalk) Box(
                        Modifier.size(140.dp).clip(CircleShape).background(if (s.talking) Amber else Chip)
                            .pointerInput(Unit) { detectTapGestures(onPress = { intercom.talk(true); tryAwaitRelease(); intercom.talk(false) }) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Mic, "Hold to talk", tint = if (s.talking) CardBg else Color.White, modifier = Modifier.size(56.dp)) }
                    if (s.announce && !s.outgoing) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(intercom::hangup) { Text("DISMISS") }
                        Button(intercom::callBack) { Text("REPLY") }
                    } else Button(intercom::hangup, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text(if (s.announce) "DONE" else "END") }
                }
                CallPhase.CALLING -> Button(intercom::hangup, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("CANCEL") }
                CallPhase.ENDED -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(intercom::hangup) { Text("CLOSE") }
                    if (s.replyTo != null) Button(intercom::callBack) { Text(if (s.outgoing) "CALL AGAIN" else "CALL BACK") }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(icon: ImageVector, label: String, sub: String?, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 14.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = if (onClick != null) Amber else TextDim)
        Column {
            Text(label, color = if (onClick != null) Color.White else TextDim, fontSize = 18.sp)
            sub?.let { Text(it, color = TextDim, fontSize = 13.sp) }
        }
    }
}

/** The header's intercom button opens this: Announce to all, then every tablet currently taking calls. */
@Composable
fun IntercomPicker(peers: List<Peer>, canAnnounce: Boolean, onPick: (Peer) -> Unit, onAnnounce: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = CardBg,
        title = { Text("Intercom", color = Color.White) },
        text = {
            Column {
                if (peers.isEmpty()) Text("Looking for tablets… Turn on Receive calls on another tablet to see it here.", color = TextDim, fontSize = 15.sp)
                else if (canAnnounce) PickerRow(Icons.Rounded.Campaign, "Announce to all", "One way, to every tablet taking calls", onAnnounce)
                peers.forEach { p ->
                    if (p.dnd) PickerRow(Icons.Rounded.DoNotDisturbOn, p.name, "Do not disturb", null)
                    else PickerRow(Icons.Rounded.Call, p.name, null) { onPick(p) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("CLOSE") } },
    )
}

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Link
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
import androidx.compose.ui.text.style.TextAlign
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

enum class CallPhase { PAIRING, CALLING, RINGING, LIVE, ENDED }

/**
 * What the call screen shows. [canTalk] is false when this tablet's mic can't be used (listen only) and on the
 * tablets hearing an announcement. [announce] is set on both the announcer and its listeners; [outgoing] tells
 * them apart. [replyTo] is who "Call back" rings. [code] is the pairing code both screens show.
 */
data class CallState(
    val phase: CallPhase, val peer: String, val note: String = "", val talking: Boolean = false, val canTalk: Boolean = true,
    val announce: Boolean = false, val outgoing: Boolean = false, val missed: Boolean = false, val replyTo: Peer? = null,
    val code: String = "",
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
    s.phase == CallPhase.PAIRING -> "pairing"
    s.phase == CallPhase.CALLING -> "calling"
    s.phase == CallPhase.RINGING -> "ringing"
    s.phase == CallPhase.ENDED -> if (s.missed) "missed" else "idle"
    s.announce -> if (s.outgoing) "announcing" else "listening"
    else -> "in_call"
}

/** A tablet this one has paired with. */
data class Paired(val id: String, val name: String, val key: ByteArray)

fun parsePairs(json: String): Map<String, Paired> = runCatching {
    val o = JSONObject(json)
    o.keys().asSequence().associateWith { id -> o.getJSONObject(id).let { Paired(id, it.getString("name"), it.getString("key").unhex()) } }
}.getOrElse { emptyMap() }

/**
 * Tablet-to-tablet intercom on the LAN. Making and taking calls are separate switches: a tablet that only calls
 * never opens a port or announces itself, so nobody can reach it. Tablets see each other but only talk once paired:
 * one asks, the other accepts, and both screens show the same code.
 *
 * Wire: a tablet taking calls sends a UDP beacon to [BEACON_PORT]. Everything else is one TCP connection to its
 * [PORT], starting with a JSON line from the caller:
 *  - `{kind: pair, id, name, pub}`: the callee answers `{pub}`, both derive the secret and code (see
 *    [IntercomCrypto]), then the callee sends `accept` or `decline` once someone taps.
 *  - `{kind: call|announce, id, name, nonce}`: the callee answers `{nonce, proof}` (or `{status: unpaired}`), the
 *    caller answers `{proof}`. Then the callee says `ringing`, `listening` (announcement), `busy`, `dnd` or `off`,
 *    and `answer` or `decline` for a call. After that both sides send AES-GCM sealed 20 ms frames of 16 kHz mono
 *    PCM16 while their talk button is held. Closing the socket ends it. Push to talk only, so it never needs echo
 *    cancellation.
 */
class Intercom(private val context: Context, scope: CoroutineScope, private val settings: Settings, private val busy: () -> Boolean, private val onRing: () -> Unit) {
    // A port already taken or a dropped network must never take the app down.
    private val io = scope + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "stopped: $e") }
    private val _state = MutableStateFlow<CallState?>(null)
    /** null = no call on screen. */
    val state: StateFlow<CallState?> = _state
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    /** Every tablet taking calls on the network, paired or not. */
    val peers: StateFlow<List<Peer>> = _peers

    private val id = settings.intercomId.ifEmpty { UUID.randomUUID().toString().also { settings.intercomId = it } }
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

    fun pairs() = parsePairs(settings.intercomPairs)

    private fun savePair(peerId: String, name: String, key: ByteArray) {
        val o = runCatching { JSONObject(settings.intercomPairs) }.getOrElse { JSONObject() }
        settings.intercomPairs = o.put(peerId, JSONObject().put("name", name).put("key", key.hex())).toString()
    }

    /** When each tablet last asked to pair, so a declined one can't keep popping up the request. */
    private val pairAsked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun dnd() = dndAt(settings.intercomDnd, settings.intercomDndFrom, settings.intercomDndTo, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))

    fun canTalk() = !settings.micBroken &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun idle() = _state.value.let { it == null || it.phase == CallPhase.ENDED }

    /** For Home Assistant: rings the paired tablet with this name. False when there's none or a call is already up. */
    fun callByName(name: String): Boolean {
        val paired = pairs()
        val p = _peers.value.firstOrNull { it.id in paired && it.name.equals(name.trim(), ignoreCase = true) } ?: return false
        if (!idle()) return false
        call(p)
        return true
    }

    /** Asks [peer] to pair. Both screens show the same code; it's done once they tap Accept. */
    fun pair(peer: Peer) {
        if (!idle()) return
        val my = gen.incrementAndGet()
        _state.value = CallState(CallPhase.PAIRING, peer.name, "Connecting…", outgoing = true)
        io.launch {
            val outcome = runCatching {
                val s = Socket().apply { connect(InetSocketAddress(peer.host, peer.port), 3_000); soTimeout = PAIR_MS }
                if (gen.get() != my) { s.close(); return@runCatching null }
                sockets = listOf(s)
                val keys = IntercomCrypto.newKeyPair()
                send(s, JSONObject().put("kind", "pair").put("id", id).put("name", settings.intercomName).put("pub", keys.public.encoded.hex()))
                val reply = JSONObject(readLine(s.getInputStream()) ?: return@runCatching "Couldn't reach ${peer.name}.")
                if (!reply.has("pub")) return@runCatching "${peer.name} is busy."
                val secret = IntercomCrypto.agree(keys, reply.getString("pub").unhex())
                _state.update { it?.copy(note = "Waiting for ${peer.name} to accept. Check both screens show this code.", code = IntercomCrypto.pairCode(secret)) }
                when (readLine(s.getInputStream())) {
                    "accept" -> { savePair(peer.id, peer.name, IntercomCrypto.pairKey(secret)); "Paired with ${peer.name}." }
                    else -> "${peer.name} didn't accept."
                }
            }
            end(my, outcome.getOrElse { "Couldn't reach ${peer.name}." })
        }
    }

    fun call(peer: Peer) {
        if (!idle()) return
        val my = gen.incrementAndGet()
        _state.value = CallState(CallPhase.CALLING, peer.name, "Connecting…", outgoing = true, replyTo = peer)
        io.launch {
            val outcome = runCatching {
                val (s, session) = open(peer, "call")
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
                        live(listOf(s to session), play = true)
                        "Call ended."
                    }
                    "decline" -> "${peer.name} declined."
                    else -> "No answer."
                }
            }
            end(my, outcome.getOrElse { (it as? Refused)?.message ?: "Couldn't reach ${peer.name}." })
        }
    }

    /** One way to every paired tablet. Tablets on Do not disturb, in a call or with announcements off skip it. */
    fun announce() {
        if (!idle()) return
        val my = gen.incrementAndGet()
        _state.value = CallState(CallPhase.CALLING, "Everyone", "Connecting…", announce = true, outgoing = true)
        val paired = pairs()
        io.launch {
            val reached = _peers.value.filter { !it.dnd && it.id in paired }.map { p ->
                async {
                    runCatching {
                        val (s, session) = open(p, "announce")
                        if (readLine(s.getInputStream()) == "listening") Triple(p, s, session) else { s.close(); null }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
            if (gen.get() != my) { reached.forEach { runCatching { it.second.close() } }; return@launch }
            if (reached.isEmpty()) { end(my, "No paired tablet is taking announcements right now."); return@launch }
            sockets = reached.map { it.second }
            reached.forEach { it.second.soTimeout = 0 }
            _state.update { it?.copy(phase = CallPhase.LIVE, canTalk = canTalk(), note = "To " + reached.joinToString { p -> p.first.name }) }
            runCatching { live(reached.map { it.second to it.third }, play = false) }
            end(my, "Announcement over.")
        }
    }

    /** Answers a call or accepts a pairing request. */
    fun answer() = answer?.invoke()

    /** Ends, cancels, declines or dismisses whatever is up. */
    fun hangup() {
        val socks = sockets
        val asked = _state.value.let { it?.phase == CallPhase.RINGING || (it?.phase == CallPhase.PAIRING && !it.outgoing) }
        gen.incrementAndGet()
        sockets = emptyList()
        answer = null
        talking = false
        _state.value = null
        io.launch {
            if (asked) runCatching { socks.firstOrNull()?.getOutputStream()?.write("decline\n".toByteArray()) }
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

    private class Refused(message: String) : Exception(message)

    /** Connects and runs the handshake: both sides prove they hold the pair key. */
    private fun open(peer: Peer, kind: String): Pair<Socket, AudioSession> {
        val key = pairs()[peer.id]?.key ?: throw Refused("${peer.name} isn't paired with this tablet.")
        val s = Socket()
        try {
            s.connect(InetSocketAddress(peer.host, peer.port), 3_000)
            s.soTimeout = RING_MS
            val mine = IntercomCrypto.nonce()
            send(s, JSONObject().put("kind", kind).put("id", id).put("name", settings.intercomName).put("nonce", mine.hex()))
            val r = JSONObject(readLine(s.getInputStream()) ?: throw Refused("${peer.name} hung up."))
            if (r.optString("status") == "unpaired") throw Refused("${peer.name} removed this tablet. Pair again.")
            val theirs = r.getString("nonce").unhex()
            if (!IntercomCrypto.same(r.getString("proof").unhex(), IntercomCrypto.proof(key, "callee", mine, theirs))) {
                throw Refused("${peer.name} couldn't prove it's the tablet you paired with.")
            }
            send(s, JSONObject().put("proof", IntercomCrypto.proof(key, "caller", mine, theirs).hex()))
            return s to AudioSession(IntercomCrypto.sessionKey(key, mine, theirs), caller = true)
        } catch (e: Exception) {
            s.close()
            throw e
        }
    }

    private fun send(s: Socket, o: JSONObject) = s.getOutputStream().write((o.toString() + "\n").toByteArray())

    /** Shows [note] for a few seconds (a missed call longer), then clears the screen. null clears it now. */
    private suspend fun end(my: Int, note: String?, missed: Boolean = false) {
        if (gen.get() != my) return // hung up here, or a newer call owns the screen
        sockets.forEach { runCatching { it.close() } }
        sockets = emptyList()
        answer = null
        talking = false
        if (note == null || _state.value == null) { _state.value = null; return }
        _state.update { it?.copy(phase = CallPhase.ENDED, note = note, talking = false, missed = missed, code = "") }
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
            io.launch { runCatching { incoming(s) }.onFailure { runCatching { s.close() } } }
        }
    }

    private suspend fun incoming(s: Socket) {
        s.soTimeout = 5_000
        val hello = JSONObject(readLine(s.getInputStream()) ?: "")
        val from = hello.getString("id")
        val name = hello.optString("name").take(40).ifEmpty { "A tablet" }
        if (hello.optString("kind") == "pair") return pairRequest(s, from, name, hello.getString("pub").unhex())

        // Unpaired tablets are told so and nothing else: no ring, no Do not disturb, no state.
        val key = pairs()[from]?.key
        if (key == null) { send(s, JSONObject().put("status", "unpaired")); s.close(); return }
        val theirs = hello.getString("nonce").unhex()
        val mine = IntercomCrypto.nonce()
        send(s, JSONObject().put("nonce", mine.hex()).put("proof", IntercomCrypto.proof(key, "callee", theirs, mine).hex()))
        val proof = JSONObject(readLine(s.getInputStream()) ?: "").getString("proof").unhex()
        if (!IntercomCrypto.same(proof, IntercomCrypto.proof(key, "caller", theirs, mine))) { Log.w(TAG, "bad proof from $name"); s.close(); return }
        val session = AudioSession(IntercomCrypto.sessionKey(key, theirs, mine), caller = false)

        val announcing = hello.optString("kind") == "announce"
        val refuse = when {
            !idle() || busy() -> "busy"
            dnd() -> "dnd"
            announcing && !settings.intercomAnnouncements -> "off"
            else -> null
        }
        if (refuse != null) { runCatching { s.getOutputStream().write("$refuse\n".toByteArray()); s.close() }; return }
        val my = gen.incrementAndGet()
        sockets = listOf(s)
        val back = _peers.value.firstOrNull { it.id == from } ?: Peer(from, name, s.inetAddress.hostAddress.orEmpty(), PORT, 0)
        if (announcing) {
            _state.value = CallState(CallPhase.LIVE, name, "Announcement", canTalk = false, announce = true, replyTo = back)
            val ok = runCatching {
                s.getOutputStream().write("listening\n".toByteArray())
                s.soTimeout = 0
                withContext(Dispatchers.Main) { onRing() }
                chime()
                live(listOf(s to session), play = true)
            }.isSuccess
            end(my, if (ok) "Announcement from $name is over." else null)
            return
        }

        _state.value = CallState(CallPhase.RINGING, name, "Calling…", replyTo = back)
        val auto = settings.intercomAutoAnswer
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        val outcome = runCatching {
            s.getOutputStream().write("ringing\n".toByteArray())
            withContext(Dispatchers.Main) { onRing() }
            if (auto) tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            val answered = waitForTap(s, RING_MS) { elapsed, rung ->
                if (auto) {
                    val left = ((AUTO_ANSWER_MS - elapsed + 999) / 1_000).toInt()
                    _state.update { it?.copy(note = "Answering in $left…") }
                    left <= 0
                } else {
                    if (rung) tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1_000)
                    false
                }
            }
            if (!answered) return@runCatching "missed"
            tone?.stopTone()
            s.getOutputStream().write("answer\n".toByteArray())
            s.soTimeout = 0
            _state.update { it?.copy(phase = CallPhase.LIVE, canTalk = canTalk()) }
            live(listOf(s to session), play = true)
            "Call ended."
        }
        tone?.release()
        when (val r = outcome.getOrElse { "Call dropped." }) {
            "missed" -> end(my, "Missed call from $name.", missed = true)
            else -> end(my, r)
        }
    }

    /** Someone asked to pair. Shows the code and waits for Accept; nothing is saved unless it's tapped. */
    private suspend fun pairRequest(s: Socket, from: String, name: String, theirPub: ByteArray) {
        val now = System.currentTimeMillis()
        val last = pairAsked.put(from, now)
        if (!idle() || busy() || (last != null && now - last < 30_000)) { send(s, JSONObject().put("status", "busy")); s.close(); return }
        val keys = IntercomCrypto.newKeyPair()
        val secret = IntercomCrypto.agree(keys, theirPub)
        send(s, JSONObject().put("pub", keys.public.encoded.hex()))
        val my = gen.incrementAndGet()
        sockets = listOf(s)
        _state.value = CallState(CallPhase.PAIRING, name, "wants to pair with this tablet. Accept only if both screens show this code.", code = IntercomCrypto.pairCode(secret))
        withContext(Dispatchers.Main) { onRing() }
        val outcome = runCatching {
            if (!waitForTap(s, PAIR_MS) { _, _ -> false }) return@runCatching "Pairing with $name timed out."
            savePair(from, name, IntercomCrypto.pairKey(secret))
            s.getOutputStream().write("accept\n".toByteArray())
            "Paired with $name."
        }
        end(my, outcome.getOrElse { "Pairing with $name was cancelled." })
    }

    /**
     * Waits for answer() up to [limit] ms. The other side sends nothing meanwhile, so a short read only ever times
     * out or sees it hang up (false). [tick] runs every 200 ms with the elapsed time and true every 2 s; returning
     * true answers.
     */
    private fun waitForTap(s: Socket, limit: Int, tick: (Long, Boolean) -> Boolean): Boolean {
        val tapped = java.util.concurrent.atomic.AtomicBoolean(false)
        answer = { tapped.set(true) }
        val input = s.getInputStream()
        s.soTimeout = 200
        val start = System.currentTimeMillis()
        var rung = 0L
        while (!tapped.get()) {
            val now = System.currentTimeMillis()
            if (now - start > limit) return false
            val ring = now - rung > 2_000
            if (ring) rung = now
            if (tick(now - start, ring)) return true
            val r = try { input.read() } catch (_: SocketTimeoutException) { 0 }
            if (r < 0) return false
        }
        return true
    }

    private fun chime() = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80).apply { startTone(ToneGenerator.TONE_PROP_BEEP2, 400); Thread.sleep(450); release() }
    }

    /**
     * Plays what comes in ([play]) and sends the mic, sealed per call, to every socket while the talk button is held,
     * until each socket closes or hangup() closes them.
     */
    @SuppressLint("MissingPermission") // canTalk() checks it
    private suspend fun live(calls: List<Pair<Socket, AudioSession>>, play: Boolean) = withContext(Dispatchers.IO) {
        val outs = calls.map { (s, session) -> s.getOutputStream() to session }.toMutableList()
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
                val frame = ByteArray(FRAME) // 20 ms, always whole: the sealed size on the wire is fixed
                while (isActive) {
                    var got = 0
                    while (got < FRAME) {
                        val n = mic.read(frame, got, FRAME - got)
                        if (n < 0) { settings.micBroken = true; _state.update { it?.copy(canTalk = false) }; return@launch } // see Settings.micBroken
                        got += n
                    }
                    if (talking) outs.removeAll { (o, session) -> runCatching { o.write(session.seal(frame)) }.isFailure }
                }
            } catch (e: Exception) {
                Log.w(TAG, "mic: $e")
            } finally {
                mic.release()
            }
        }
        try {
            track?.play()
            calls.map { (s, session) ->
                launch {
                    runCatching {
                        val input = DataInputStream(s.getInputStream())
                        val sealed = ByteArray(AudioSession.SEALED)
                        while (true) {
                            input.readFully(sealed)
                            val pcm = session.open(sealed) // throws on tampering, which ends the call
                            track?.write(pcm, 0, pcm.size)
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
        private const val FRAME = 640
        private const val RING_MS = 30_000
        private const val PAIR_MS = 60_000
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
            Icon(
                when { s.phase == CallPhase.PAIRING -> Icons.Rounded.Link; s.announce -> Icons.Rounded.Campaign; else -> Icons.Rounded.Call },
                null, tint = Amber, modifier = Modifier.size(40.dp),
            )
            Text(s.peer, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            val note = when {
                s.phase != CallPhase.LIVE -> s.note
                s.announce && !s.outgoing -> "Announcement"
                !s.canTalk -> "Listen only: this tablet's mic is off."
                s.talking -> if (s.announce) s.note else "Talking…"
                else -> if (s.announce) s.note + ". Hold the button to talk." else "Hold the button to talk."
            }
            Text(note, color = TextDim, fontSize = 16.sp, textAlign = TextAlign.Center)
            if (s.code.isNotEmpty()) Text(s.code, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            when (s.phase) {
                CallPhase.PAIRING -> if (s.outgoing) Button(intercom::hangup, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("CANCEL") }
                else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(intercom::hangup, colors = ButtonDefaults.buttonColors(containerColor = red)) { Text("DECLINE") }
                    Button(intercom::answer, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C784))) { Text("ACCEPT") }
                }
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

/**
 * The header's intercom button opens this: Announce to all, every paired tablet that's on, then tablets nearby that
 * aren't paired yet with a Pair button.
 */
@Composable
fun IntercomPicker(
    peers: List<Peer>, paired: Set<String>, canAnnounce: Boolean,
    onCall: (Peer) -> Unit, onPair: (Peer) -> Unit, onAnnounce: () -> Unit, onDismiss: () -> Unit,
) {
    val (mine, nearby) = peers.partition { it.id in paired }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = CardBg,
        title = { Text("Intercom", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (peers.isEmpty()) Text("Looking for tablets… Turn on Receive calls on another tablet to see it here.", color = TextDim, fontSize = 15.sp)
                if (mine.isNotEmpty() && canAnnounce) PickerRow(Icons.Rounded.Campaign, "Announce to all", "One way, to every paired tablet", onAnnounce)
                mine.forEach { p ->
                    if (p.dnd) PickerRow(Icons.Rounded.DoNotDisturbOn, p.name, "Do not disturb", null)
                    else PickerRow(Icons.Rounded.Call, p.name, null) { onCall(p) }
                }
                if (nearby.isNotEmpty()) {
                    Text("Nearby, not paired", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, start = 8.dp))
                    nearby.forEach { p -> PickerRow(Icons.Rounded.Link, p.name, "Tap to pair. The other tablet has to accept.") { onPair(p) } }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("CLOSE") } },
    )
}

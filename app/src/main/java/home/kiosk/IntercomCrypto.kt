package home.kiosk

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Intercom security, all from the platform (API 21+, no libraries):
 *  - Pairing: an ECDH (P-256) exchange gives both tablets the same secret without sending it. Both screens show a
 *    6-digit code from it; matching codes mean nobody sat in the middle. The pair key comes from the same secret.
 *  - Every call: each side proves it holds the pair key with an HMAC over fresh nonces from both sides, so an
 *    unpaired or spoofed tablet is refused and an old recording of a handshake can't be replayed.
 *  - Audio: AES-GCM per 20 ms frame with a per-call key and a counter nonce, so it can't be heard, altered or
 *    replayed on the network.
 */
object IntercomCrypto {
    private val random = SecureRandom()

    fun nonce() = ByteArray(16).also(random::nextBytes)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** The ECDH secret from our key pair and the other tablet's encoded public key. */
    fun agree(mine: KeyPair, theirs: ByteArray): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(mine.private)
        doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirs)), true)
        generateSecret()
    }

    fun pairKey(secret: ByteArray) = sha256("lightha-pair-key".toByteArray() + secret)

    /** Six digits both screens show while pairing, e.g. "482 913". */
    fun pairCode(secret: ByteArray): String {
        val h = sha256("lightha-pair-code".toByteArray() + secret)
        val n = ((h[0].toLong() and 0xFF) shl 24 or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)) % 1_000_000
        return "%06d".format(n).let { it.substring(0, 3) + " " + it.substring(3) }
    }

    /** Proof that [role] ("caller" or "callee") holds the pair key, bound to both sides' nonces. */
    fun proof(key: ByteArray, role: String, callerNonce: ByteArray, calleeNonce: ByteArray) = hmac(key, role.toByteArray() + callerNonce + calleeNonce)

    fun sessionKey(key: ByteArray, callerNonce: ByteArray, calleeNonce: ByteArray) = hmac(key, "audio".toByteArray() + callerNonce + calleeNonce)

    fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
}

/**
 * One call's encrypted audio. Each direction counts its own frames; the counter is the GCM nonce, so a frame
 * dropped, reordered or replayed fails to open. [caller] picks which direction is ours.
 */
class AudioSession(key: ByteArray, caller: Boolean) {
    private val secret = SecretKeySpec(key, "AES")
    private val sendDir: Byte = if (caller) 0 else 1
    private val recvDir: Byte = if (caller) 1 else 0
    private var sent = 0L
    private var received = 0L
    private val enc = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec = Cipher.getInstance("AES/GCM/NoPadding")

    fun seal(frame: ByteArray): ByteArray {
        enc.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv(sendDir, sent++)))
        return enc.doFinal(frame)
    }

    /** Throws when the frame was tampered with, replayed or is out of order. */
    fun open(sealed: ByteArray): ByteArray {
        dec.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv(recvDir, received++)))
        return dec.doFinal(sealed)
    }

    private fun iv(dir: Byte, n: Long) = ByteArray(12).also {
        it[0] = dir
        for (i in 0 until 8) it[4 + i] = (n ushr (56 - 8 * i)).toByte()
    }

    companion object {
        /** A sealed 640-byte frame: the frame plus GCM's 16-byte tag. */
        const val SEALED = 640 + 16
    }
}

fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
fun String.unhex() = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }

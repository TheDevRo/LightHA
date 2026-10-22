package home.kiosk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomCryptoTest {
    @Test fun pairingAgrees() {
        val a = IntercomCrypto.newKeyPair()
        val b = IntercomCrypto.newKeyPair()
        val sa = IntercomCrypto.agree(a, b.public.encoded)
        val sb = IntercomCrypto.agree(b, a.public.encoded)
        assertArrayEquals(IntercomCrypto.pairKey(sa), IntercomCrypto.pairKey(sb))
        assertEquals(IntercomCrypto.pairCode(sa), IntercomCrypto.pairCode(sb))
        assertTrue(Regex("\\d{3} \\d{3}").matches(IntercomCrypto.pairCode(sa)))
        // Someone in the middle ends up with a different secret on each side, so the codes differ.
        val m = IntercomCrypto.newKeyPair()
        assertNotEquals(IntercomCrypto.pairCode(IntercomCrypto.agree(a, m.public.encoded)), IntercomCrypto.pairCode(IntercomCrypto.agree(b, m.public.encoded)))
    }

    @Test fun wrongKeyFailsProof() {
        val key = ByteArray(32) { 1 }
        val other = ByteArray(32) { 2 }
        val n1 = IntercomCrypto.nonce()
        val n2 = IntercomCrypto.nonce()
        assertTrue(IntercomCrypto.same(IntercomCrypto.proof(key, "caller", n1, n2), IntercomCrypto.proof(key, "caller", n1, n2)))
        assertFalse(IntercomCrypto.same(IntercomCrypto.proof(other, "caller", n1, n2), IntercomCrypto.proof(key, "caller", n1, n2)))
        // A callee's proof can't be reflected back as a caller's.
        assertFalse(IntercomCrypto.same(IntercomCrypto.proof(key, "callee", n1, n2), IntercomCrypto.proof(key, "caller", n1, n2)))
    }

    @Test fun audioRoundTripsAndRejectsReplayAndTampering() {
        val key = IntercomCrypto.sessionKey(ByteArray(32) { 7 }, IntercomCrypto.nonce(), IntercomCrypto.nonce())
        val caller = AudioSession(key, caller = true)
        val callee = AudioSession(key, caller = false)
        val frame = ByteArray(640) { it.toByte() }
        val sealed = caller.seal(frame)
        assertEquals(AudioSession.SEALED, sealed.size)
        assertArrayEquals(frame, callee.open(sealed))
        assertTrue(runCatching { callee.open(sealed) }.isFailure) // replayed
        val next = caller.seal(frame).also { it[10] = (it[10] + 1).toByte() }
        assertTrue(runCatching { AudioSession(key, caller = false).apply { open(caller.seal(frame)) }.open(next) }.isFailure) // tampered
        // Each direction has its own counter: the callee's first frame opens on the caller.
        assertArrayEquals(frame, caller.open(callee.seal(frame)))
    }

    @Test fun pairsParse() {
        val p = parsePairs("""{"a1":{"name":"Kitchen","key":"00ff10"}}""")
        assertEquals("Kitchen", p["a1"]!!.name)
        assertArrayEquals(byteArrayOf(0, -1, 16), p["a1"]!!.key)
        assertTrue(parsePairs("junk").isEmpty())
    }
}

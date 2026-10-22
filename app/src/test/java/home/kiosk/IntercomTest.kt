package home.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomTest {
    @Test fun beaconParses() {
        val p = parseBeacon("""{"lightha":"intercom","id":"a1","name":"Kitchen","port":47811,"dnd":true}""", "192.168.20.5", 7)
        assertEquals(Peer("a1", "Kitchen", "192.168.20.5", 47811, 7, dnd = true), p)
    }

    @Test fun strangersIgnored() {
        assertNull(parseBeacon("""{"id":"a1","name":"Kitchen","port":47811}""", "x", 0))
        assertNull(parseBeacon("not json", "x", 0))
        assertNull(parseBeacon("""{"lightha":"intercom","id":"a1"}""", "x", 0))
    }

    @Test fun dndSchedule() {
        assertFalse(dndAt(0, 22, 7, 23))
        assertTrue(dndAt(1, 22, 7, 12))
        // Overnight: 22:00 until 07:00.
        assertTrue(dndAt(2, 22, 7, 22))
        assertTrue(dndAt(2, 22, 7, 3))
        assertFalse(dndAt(2, 22, 7, 7))
        assertFalse(dndAt(2, 22, 7, 12))
        // Daytime: 09:00 until 17:00.
        assertTrue(dndAt(2, 9, 17, 9))
        assertFalse(dndAt(2, 9, 17, 17))
        assertFalse(dndAt(2, 9, 17, 3))
    }

    @Test fun haStates() {
        assertEquals("idle", haStatus(null))
        assertEquals("ringing", haStatus(CallState(CallPhase.RINGING, "Kitchen")))
        assertEquals("in_call", haStatus(CallState(CallPhase.LIVE, "Kitchen")))
        assertEquals("announcing", haStatus(CallState(CallPhase.LIVE, "Everyone", announce = true, outgoing = true)))
        assertEquals("listening", haStatus(CallState(CallPhase.LIVE, "Kitchen", announce = true)))
        assertEquals("missed", haStatus(CallState(CallPhase.ENDED, "Kitchen", missed = true)))
        assertEquals("idle", haStatus(CallState(CallPhase.ENDED, "Kitchen")))
    }
}

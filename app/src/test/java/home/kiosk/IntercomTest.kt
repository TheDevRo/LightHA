package home.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntercomTest {
    @Test fun beaconParses() {
        val p = parseBeacon("""{"lightha":"intercom","id":"a1","name":"Kitchen","port":47811}""", "192.168.20.5", 7)
        assertEquals(Peer("a1", "Kitchen", "192.168.20.5", 47811, 7), p)
    }

    @Test fun strangersIgnored() {
        assertNull(parseBeacon("""{"id":"a1","name":"Kitchen","port":47811}""", "x", 0))
        assertNull(parseBeacon("not json", "x", 0))
        assertNull(parseBeacon("""{"lightha":"intercom","id":"a1"}""", "x", 0))
    }
}

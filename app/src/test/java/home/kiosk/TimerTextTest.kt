package home.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerTextTest {
    @Test fun readsHaTimerMessages() {
        assertEquals("1 minute timer finished", timerText("0:01:00 timer finished"))
        assertEquals("1 hour 30 minute timer finished", timerText("1:30:00 timer finished"))
        assertEquals("45 second timer finished", timerText("0:00:45 timer finished"))
        assertEquals("Pasta finished", timerText("Pasta finished"))
    }
}

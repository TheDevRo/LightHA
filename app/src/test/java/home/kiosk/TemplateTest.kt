package home.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TemplateTest {
    @Test fun mushroomColors() {
        assertNotNull(mushroomColor("amber"))
        assertNotNull(mushroomColor(" Light-Blue "))
        assertNull(mushroomColor("dark-grey"))  // the "off" colour in your Kitchen/Dining templates
        assertNull(mushroomColor(""))
        assertNull(mushroomColor(null))
        assertNull(mushroomColor("not-a-colour"))
        assertEquals(mushroomColor("amber"), mushroomColor("AMBER"))
    }
}

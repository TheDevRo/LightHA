package home.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAppTest {
    @Test fun batteryFromAndroidUnits() {
        assertEquals(Battery(50, true, 31), Battery.of(level = 25, scale = 50, plugged = 2, tenthsC = 312))
        assertEquals(Battery(87, false, 0), Battery.of(level = 87, scale = 0, plugged = 0, tenthsC = 0))
    }

    @Test fun booleanStateIsBinarySensor() {
        val (level, charger) = Battery(80, true, 30).sensors()
        assertEquals("sensor", level.type)
        assertEquals("%", level.registration().getString("unit_of_measurement"))
        assertEquals("binary_sensor", charger.type)
        assertEquals(true, charger.update().get("state"))
        assertFalse(charger.registration().has("unit_of_measurement"))
    }

    @Test fun reregisterWhenHaForgotWebhookOrSensor() {
        assertTrue(MobileApp.needsRegistering(""))
        assertTrue(MobileApp.needsRegistering("""{"charger":{"success":false,"error":{"code":"not_registered","message":"x"}}}"""))
        assertFalse(MobileApp.needsRegistering("""{"charger":{"success":true}}"""))
        assertFalse(MobileApp.needsRegistering("""{"success":true}"""))
    }
}

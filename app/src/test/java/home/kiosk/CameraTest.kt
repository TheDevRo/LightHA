package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTest {
    @Test fun findsCameras() {
        val nanit = JSONObject("""{"type":"picture-entity","entity":"sensor.nanit_temperature","camera_image":"camera.nanit_camera","camera_view":"live"}""")
        assertTrue(isCameraCard(nanit))
        assertEquals(listOf("camera.nanit_camera"), cameraIds(nanit))
        assertEquals(3_000L, cameraRefreshMs(nanit))

        val adv = JSONObject("""{"type":"custom:advanced-camera-card","cameras":[{"camera_entity":"camera.bulb_cam"},{"camera_entity":"camera.garage_cam_2"}]}""")
        assertEquals(listOf("camera.bulb_cam", "camera.garage_cam_2"), cameraIds(adv))

        assertFalse(isCameraCard(JSONObject("""{"type":"picture-entity","entity":"sensor.x"}""")))  // no camera: not a camera card
        assertTrue(isCameraCard(JSONObject("""{"type":"tile","entity":"camera.e1"}""")))
        assertEquals(10_000L, cameraRefreshMs(JSONObject("""{"type":"picture-entity"}""")))
    }

    @Test fun snapshotUrls() {
        assertEquals("http://ha:8123/api/camera_proxy/camera.e1?token=abc&t=5", snapshotUrl("http://ha:8123/", "/api/camera_proxy/camera.e1?token=abc", 5))
        assertNull(snapshotUrl("http://ha", "", 5))
        assertNull(snapshotUrl("http://ha", "null", 5))
    }
}

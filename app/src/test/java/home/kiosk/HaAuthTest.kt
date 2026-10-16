package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaAuthTest {
    @Test fun firstStepIsTheUsernamePasswordForm() { // HA's real reply (captured from .51)
        val s = HaAuth.parseStep(JSONObject("""{"type":"form","flow_id":"c434","handler":["homeassistant",null],"data_schema":[{"type":"string","name":"username"}],"errors":{},"step_id":"init"}"""))
        assertEquals("c434", s.flowId); assertEquals("init", s.stepId); assertNull(s.error); assertNull(s.code)
    }

    @Test fun errorsAndMfaAndSuccess() {
        assertEquals("Wrong username or password", HaAuth.parseStep(JSONObject("""{"type":"form","flow_id":"f","step_id":"init","errors":{"base":"invalid_auth"}}""")).error)
        val mfa = HaAuth.parseStep(JSONObject("""{"type":"form","flow_id":"f","step_id":"mfa","errors":{}}"""))
        assertEquals("mfa", mfa.stepId); assertNull(mfa.error)
        assertEquals("Wrong code", HaAuth.parseStep(JSONObject("""{"type":"form","flow_id":"f","step_id":"mfa","errors":{"base":"invalid_code"}}""")).error)
        assertEquals("abc123", HaAuth.parseStep(JSONObject("""{"type":"create_entry","result":"abc123"}""")).code)
        assertEquals("Sign-in timed out. Try again.", HaAuth.parseStep(JSONObject("""{"type":"abort","reason":"login_expired"}""")).error)
    }
}

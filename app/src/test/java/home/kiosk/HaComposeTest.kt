package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaComposeTest {
    @Test fun templateResults() {
        assertEquals("Motion: clear", templateResult(JSONObject("""{"result":"\n  Motion: clear\n","listeners":{}}""")))
        assertEquals("42", templateResult(JSONObject("""{"result":42}""")))
        assertNull(templateResult(JSONObject("""{"error":"UndefinedError","level":"ERROR"}""")))
        assertNull(templateResult(JSONObject("""{"result":null}""")))
    }
}

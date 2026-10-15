package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ReduceTest {
    private fun j(s: String) = JSONObject(s)

    @Test fun snapshotThenEventsThenRemoval() {
        var s = HaClient.reduce(emptyMap(), j("""{"id":2,"type":"result","success":true,"result":[
            {"entity_id":"light.desk","state":"off","attributes":{"friendly_name":"Desk"}}]}"""), getStatesId = 2)
        assertEquals("Desk", s["light.desk"]!!.name)

        // result for some other request id is ignored
        assertEquals(s, HaClient.reduce(s, j("""{"id":9,"type":"result","success":true,"result":[]}"""), 2))

        s = HaClient.reduce(s, j("""{"id":1,"type":"event","event":{"event_type":"state_changed","data":{
            "entity_id":"light.desk","new_state":{"entity_id":"light.desk","state":"on","attributes":{}}}}}"""), 2)
        assertEquals("on", s["light.desk"]!!.state)

        s = HaClient.reduce(s, j("""{"id":1,"type":"event","event":{"event_type":"state_changed","data":{
            "entity_id":"light.desk","new_state":null}}}"""), 2)
        assertEquals(emptyMap<String, Entity>(), s)
    }


}

package home.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoMarkdownTest {
    @Test fun todoItemsOpenFirst() {
        val items = parseTodo(JSONObject("""{"items":[
            {"uid":"1","summary":"Milk","status":"completed"},
            {"uid":"2","summary":"Jalapeño chips","status":"needs_action"},
            {"uid":"3","summary":null,"status":"needs_action"}]}"""))
        assertEquals(listOf("2", "3", "1"), items.map { it.uid })
        assertEquals("", items[1].summary) // null summary isn't the text "null"
    }

    @Test fun markdownBlocksAndInline() {
        val blocks = markdownBlocks("# Title\n\nSome **bold** text\n- one\n* two\n### Small")
        assertEquals(listOf(1, 0, BULLET, BULLET, 3), blocks.map { it.first })
        assertEquals("Some bold text", inline(blocks[1].second).text)
        assertEquals("see docs here", inline("see [docs](http://x) _here_").text)
    }
}

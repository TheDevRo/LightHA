package home.kiosk

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val Amber = Color(0xFFFFB74D)

fun isTodoCard(card: JSONObject) = card.optString("type") == "todo-list"
fun isMarkdownCard(card: JSONObject) = card.optString("type") == "markdown"

data class TodoItem(val uid: String, val summary: String, val done: Boolean)

/** `todo/item/subscribe` events: open items first (HA's order), then completed. */
fun parseTodo(event: JSONObject): List<TodoItem> =
    (event.optJSONArray("items")?.objects().orEmpty()).map {
        TodoItem(it.optString("uid"), if (it.isNull("summary")) "" else it.optString("summary"), it.optString("status") == "completed")
    }.sortedBy { it.done }

@Composable
fun TodoCard(card: JSONObject, ctx: CardContext) {
    val id = card.optString("entity")
    val items = rememberSubscription(id, "todo/item/subscribe", { put("entity_id", id) }) { parseTodo(it) }
    var adding by remember { mutableStateOf("") }
    fun add() {
        val text = adding.trim()
        if (text.isNotEmpty()) ctx.call("todo", "add_item", listOf(id), JSONObject().put("item", text))
        adding = ""
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(card.optString("title").ifEmpty { ctx.states[id]?.name ?: "To-do" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                adding, { adding = it }, placeholder = { Text("Add item") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
            )
            Spacer(Modifier.width(8.dp))
            RoundButton(Icons.Rounded.Add, Amber, CardBg, 48) { add() }
        }
        when {
            items == null -> Text("Loading…", color = TextDim, fontSize = 14.sp)
            items.isEmpty() -> Text("Nothing on the list", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            else -> items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                        ctx.call("todo", "update_item", listOf(id), JSONObject().put("item", item.uid).put("status", if (item.done) "needs_action" else "completed"))
                    }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (item.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                        tint = if (item.done) Amber else TextDim, modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        item.summary, fontSize = 16.sp,
                        color = if (item.done) TextDim else Color.White,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}

@Composable
fun MarkdownCard(card: JSONObject, ctx: CardContext) {
    // Templates in markdown are rendered by HA (e.g. {{ now().strftime('%I:%M %p') }}); HA re-renders them as things change.
    val content = rememberTemplate(card.optString("content")) ?: return
    if ("{{" in content || "{%" in content) return // waiting for HA's first render
    val mod = remember(card) { cardMod(card) }
    val scale = mod.fontScale ?: 1f
    val fg = mod.color?.let { Color(it) } ?: Color.White
    val align = when (mod.align) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> null }
    val weight = { normal: FontWeight -> when (mod.bold) { true -> FontWeight.Bold; false -> FontWeight.Normal; null -> normal } }
    @Composable fun line(text: AnnotatedString, size: Int, normal: FontWeight) =
        Text(text, Modifier.fillMaxWidth(), color = fg, fontSize = (size * scale).sp, lineHeight = (size * scale * 1.2f).sp, fontWeight = weight(normal), textAlign = align)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(mod.radiusPx?.dp ?: 24.dp)).background(mod.background?.let { Color(it) } ?: CardBg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        card.optString("title").takeIf { it.isNotEmpty() }?.let { Text(it, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        markdownBlocks(content).forEach { (kind, text) ->
            when (kind) {
                1 -> line(inline(text), 26, FontWeight.SemiBold)
                2 -> line(inline(text), 21, FontWeight.SemiBold)
                3 -> line(inline(text), 18, FontWeight.Medium)
                BULLET -> Row { Text("•  ", color = Amber, fontSize = (16 * scale).sp); line(inline(text), 16, FontWeight.Normal) }
                else -> line(inline(text), 16, FontWeight.Normal)
            }
        }
    }
}

const val BULLET = 10

/** Minimal Markdown: #/##/### headings (1–3), "- " / "* " bullets ([BULLET]), everything else a paragraph (0). */
fun markdownBlocks(md: String): List<Pair<Int, String>> = md.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
    when {
        line.startsWith("### ") -> 3 to line.removePrefix("### ")
        line.startsWith("## ") -> 2 to line.removePrefix("## ")
        line.startsWith("# ") -> 1 to line.removePrefix("# ")
        line.startsWith("- ") || line.startsWith("* ") -> BULLET to line.drop(2)
        else -> 0 to line
    }
}

/** Bold (double asterisks), italic (single asterisk or underscore), and [text](link) shown as its text. */
fun inline(text: String): AnnotatedString = buildAnnotatedString {
    val re = Regex("""\*\*(.+?)\*\*|[*_](.+?)[*_]|\[(.+?)]\((.+?)\)""")
    var i = 0
    for (m in re.findAll(text)) {
        append(text.substring(i, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(m.groupValues[2]) }
            else -> withStyle(SpanStyle(color = Amber)) { append(m.groupValues[3]) }
        }
        i = m.range.last + 1
    }
    append(text.substring(i))
}

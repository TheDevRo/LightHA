package home.kiosk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything a card needs to render and act. */
class CardContext(
    val states: Map<String, Entity>,
    val call: Call,
    val onLock: (Entity, String) -> Unit,
    val openPopup: (String) -> Unit,
    val openMoreInfo: (String) -> Unit,
    val confirm: (String, () -> Unit) -> Unit,
    val openView: (String) -> Unit,
    /** Edit mode shows hidden and unsupported cards (dimmed) so they can be found and removed. */
    val editing: Boolean,
) {
    fun copy(editing: Boolean) = CardContext(states, call, onLock, openPopup, openMoreInfo, confirm, openView, editing)

    /** Runs a resolved card action; null for Default, which the caller decides. */
    fun runner(act: Act, entityId: String?): (() -> Unit)? = when (act) {
        Act.Default -> null
        Act.None -> ({})
        Act.MoreInfo -> entityId?.let { id -> { openMoreInfo(id) } } ?: ({})
        is Act.Popup -> ({ openPopup(act.hash) })
        is Act.View -> ({ openView(act.path) })
        is Act.Service -> ({ call(act.domain, act.service, act.ids, act.data) })
        is Act.Confirm -> runner(act.then, entityId)?.let { run -> { confirm(act.text, run) } }
    }
}

/** HA card types drawn as a single entity row. */
private val ENTITY_CARDS = setOf(
    "entity", "tile", "button", "light", "sensor", "thermostat",
    "custom:mushroom-light-card", "custom:mushroom-entity-card", "custom:mushroom-template-card",
    "custom:mushroom-climate-card", "custom:mushroom-number-card", "custom:mushroom-fan-card",
    "custom:mushroom-cover-card", "custom:mushroom-lock-card", "custom:mushroom-media-player-card",
)

/** A top-level card (gets its own surface) or a card inside a stack ([top] = false, no surface). */
@Composable
fun CardView(card: JSONObject, ctx: CardContext, top: Boolean = true) {
    val shown = visible(card, ctx.states)
    if (!shown && !ctx.editing) return
    Box(Modifier.alpha(if (shown) 1f else 0.4f)) { CardBody(card, ctx, top) }
}

@Composable
private fun CardBody(card: JSONObject, ctx: CardContext, top: Boolean) {
    val type = card.optString("type")
    val children = card.optJSONArray("cards")?.objects().orEmpty()
    when {
        type == "heading" -> Text(
            card.optString("heading"),
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        )
        type == "vertical-stack" -> Surface(top) { Column { children.forEach { CardView(it, ctx, top = false) } } }
        type == "horizontal-stack" -> Surface(top) {
            Row { children.forEach { Box(Modifier.weight(1f)) { CardView(it, ctx, top = false) } } }
        }
        type == "grid" -> Surface(top) {
            val cols = card.optInt("columns", 3).coerceAtLeast(1)
            Column {
                children.chunked(cols).forEach { row ->
                    Row { row.forEach { Box(Modifier.weight(1f)) { CardView(it, ctx, top = false) } }; repeat(cols - row.size) { Box(Modifier.weight(1f)) {} } }
                }
            }
        }
        type == "entities" -> Surface(top) {
            Column {
                card.optString("title").takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
                card.optJSONArray("entities")?.let { arr ->
                    (0 until arr.length()).forEach { i ->
                        val row = arr.optJSONObject(i) ?: JSONObject().put("entity", arr.optString(i))
                        EntityCard(row, ctx)
                    }
                }
            }
        }
        type == "clock" -> Clock()
        isMediaCard(card) -> MediaCard(card, ctx)
        isTodoCard(card) -> TodoCard(card, ctx)
        isMarkdownCard(card) -> MarkdownCard(card, ctx)
        isGraphCard(card) -> GraphCard(card, ctx)
        isCalendarCard(card) -> CalendarCard(card, ctx)
        isWeatherCard(card) -> WeatherCard(card, ctx)
        isRemoteCard(card) -> RemoteCard(card, ctx)
        isPopup(card) -> if (ctx.editing) Placeholder("Pop-up · ${popupTitle(card)}", top)
        type in ENTITY_CARDS && card.has("entity") -> Surface(top) { EntityCard(card, ctx) }
        type == "button" -> Surface(top) { ActionButton(card, ctx) }
        else -> if (ctx.editing) Placeholder(type.removePrefix("custom:"), top)
    }
}

@Composable
private fun EntityCard(card: JSONObject, ctx: CardContext) {
    val id = card.optString("entity")
    val e = ctx.states[id]
    if (e == null) {
        if (ctx.editing) Placeholder("missing: $id", top = false)
        return
    }
    val isTemplate = card.optString("type") == "custom:mushroom-template-card"
    // Template cards: HA renders primary/secondary/color live; until then the plain name and state show.
    val primary = if (isTemplate) rememberTemplate(card.optString("primary").takeIf { it.isNotEmpty() }, id) else null
    val secondary = if (isTemplate) rememberTemplate(card.optString("secondary").takeIf { it.isNotEmpty() }, id) else null
    val colorSrc = card.optString("icon_color").ifEmpty { card.optString("color") }.takeIf { isTemplate && it.isNotEmpty() }
    val color = colorSrc?.let { rememberTemplate(it, id) }
    val label = primary?.takeIf { "{{" !in it && "{%" !in it } ?: cardName(card) ?: e.name
    // No tap_action and nothing sensible to toggle (thermostat, sensor): open more-info, like HA.
    val onTap = ctx.runner(tapAct(card, id), id) ?: if (tapService(e) == null) ({ ctx.openMoreInfo(id) }) else null
    EntityRow(
        e, label, ctx.call, ctx.onLock, onTap,
        onHold = ctx.runner(holdAct(card, id), id),
        // Only when configured: listening for double taps delays every single tap slightly.
        onDoubleTap = card.optJSONObject("double_tap_action")?.let { ctx.runner(doubleTapAct(card, id), id) },
        secondary = secondary?.takeIf { "{{" !in it && "{%" !in it },
        templateColor = mushroomColor(color),
        hasTemplateColor = color != null && "{{" !in color,
    )
}

/** Entity-less button card: a name that runs its tap_action (e.g. camera PTZ presets). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(card: JSONObject, ctx: CardContext) {
    val run = ctx.runner(tapAct(card, null), null)
    Box(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { run?.invoke() },
            onLongClick = card.optJSONObject("hold_action")?.let { ctx.runner(holdAct(card, null), null) },
            onDoubleClick = card.optJSONObject("double_tap_action")?.let { ctx.runner(doubleTapAct(card, null), null) },
        ).padding(16.dp),
    ) { Text(cardName(card) ?: "Button", color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** The card's own name; template strings ({{ }}) aren't rendered, so they fall back to the entity name. */
private fun cardName(card: JSONObject): String? =
    listOf("name", "primary").map { card.optString(it) }.firstOrNull { it.isNotEmpty() && "{{" !in it }

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun Badges(badges: List<JSONObject>, ctx: CardContext) {
    val shown = badges.filter { visible(it, ctx.states) }.mapNotNull { b -> ctx.states[b.optString("entity")]?.let { b to it } }
    if (shown.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { (b, e) ->
            val text = listOfNotNull(
                (cardName(b) ?: e.name).takeIf { b.optBoolean("show_name", true) },
                stateText(e).takeIf { b.optBoolean("show_state", true) && it.isNotEmpty() },
            ).joinToString(" · ")
            // Badges default to more-info on tap, like HA's.
            val tap = ctx.runner(tapAct(b, e.id), e.id) ?: { ctx.openMoreInfo(e.id) }
            Text(
                text, color = Color.White, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Chip)
                    .combinedClickable(
                        onClick = tap,
                        onLongClick = ctx.runner(holdAct(b, e.id), e.id),
                        onDoubleClick = b.optJSONObject("double_tap_action")?.let { ctx.runner(doubleTapAct(b, e.id), e.id) },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Surface(top: Boolean, content: @Composable () -> Unit) {
    if (!top) return content()
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg).padding(vertical = 6.dp)) { content() }
}

@Composable
private fun Placeholder(text: String, top: Boolean) {
    Box(
        Modifier.fillMaxWidth().padding(if (top) 0.dp else 8.dp)
            .border(1.dp, Chip, RoundedCornerShape(16.dp)).padding(16.dp),
    ) { Text(text, color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
fun Clock() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - now % 60_000)
            now = System.currentTimeMillis()
        }
    }
    Column(Modifier.padding(4.dp)) {
        Text(SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(now)), fontSize = 64.sp, fontWeight = FontWeight.Light, color = Color.White)
        Text(SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(now)), fontSize = 18.sp, color = TextDim)
    }
}

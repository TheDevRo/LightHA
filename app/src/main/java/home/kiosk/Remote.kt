package home.kiosk

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

fun isRemoteCard(card: JSONObject) = card.optString("type") == "custom:universal-remote-card"

// ---- pure: how a key reaches the device, per platform (unit-tested) ----

/** androidtv.adb_command's built-in shortcuts; anything else becomes `input keyevent KEYCODE_…`. */
private val ADB_SHORTCUTS = setOf("UP", "DOWN", "LEFT", "RIGHT", "CENTER", "BACK", "HOME", "MENU", "POWER", "MUTE", "VOLUME_UP", "VOLUME_DOWN", "SEARCH", "SETTINGS", "SLEEP", "WAKEUP", "ENTER")

/**
 * Default button names → each platform's raw key. Only names universal-remote-card itself defines for that platform
 * (src/models/maps/<platform>/defaultKeys.ts): HA silently skips any other name in `rows`, so we must too.
 */
private val DEFAULT_KEYS: Map<String, Map<String, String>> = mapOf(
    "adb" to mapOf(
        "up" to "UP", "down" to "DOWN", "left" to "LEFT", "right" to "RIGHT", "center" to "CENTER",
        "back" to "BACK", "home" to "HOME", "menu" to "MENU", "power" to "POWER",
        "volume_up" to "VOLUME_UP", "volume_down" to "VOLUME_DOWN", "volume_mute" to "MUTE",
        "play_pause" to "MEDIA_PLAY_PAUSE", "rewind" to "MEDIA_REWIND", "fast_forward" to "MEDIA_FAST_FORWARD",
        "search" to "SEARCH", "settings" to "SETTINGS",
    ),
    "apple" to mapOf(
        "up" to "up", "down" to "down", "left" to "left", "right" to "right", "center" to "select",
        "menu" to "menu", "top_menu" to "top_menu", "home" to "home", "play_pause" to "play_pause", "play" to "play", "pause" to "pause",
        "volume_up" to "volume_up", "volume_down" to "volume_down",
        "skip_backward" to "skip_backward", "skip_forward" to "skip_forward", "previous" to "previous", "next" to "next",
    ),
    "androidtv_remote" to mapOf(
        "up" to "DPAD_UP", "down" to "DPAD_DOWN", "left" to "DPAD_LEFT", "right" to "DPAD_RIGHT", "center" to "DPAD_CENTER",
        "back" to "BACK", "home" to "HOME", "menu" to "MENU", "power" to "POWER",
        "volume_up" to "VOLUME_UP", "volume_down" to "VOLUME_DOWN", "volume_mute" to "VOLUME_MUTE",
        "play_pause" to "MEDIA_PLAY_PAUSE", "rewind" to "MEDIA_REWIND", "fast_forward" to "MEDIA_FAST_FORWARD",
        "previous" to "MEDIA_PREVIOUS", "next" to "MEDIA_NEXT",
    ),
)

private fun family(card: JSONObject) = when (card.optString("platform")) {
    "Fire TV" -> "adb"
    "Apple TV" -> "apple"
    else -> "androidtv_remote"
}

/** The service call that presses [key] (a raw platform key, as in a custom `key` action). */
fun keyService(card: JSONObject, key: String): Act.Service = when (family(card)) {
    "adb" -> Act.Service(
        "androidtv", "adb_command", listOf(card.optString("media_player_id")),
        JSONObject().put("command", if (key in ADB_SHORTCUTS) key else "input keyevent KEYCODE_$key"),
    )
    else -> Act.Service("remote", "send_command", listOf(card.optString("remote_id")), JSONObject().put("command", key))
}

/** The call for one of the card's built-in button names ("back", "volume_up"…), or null if the platform has no such key. */
fun defaultKeyService(card: JSONObject, name: String): Act.Service? =
    DEFAULT_KEYS[family(card)]?.get(name)?.let { keyService(card, it) }

/** How text typed on the tablet reaches the device. */
fun textService(card: JSONObject, text: String): Act.Service? = when (family(card)) {
    "adb" -> Act.Service(
        "androidtv", "adb_command", listOf(card.optString("media_player_id")),
        JSONObject().put("command", "input text '" + text.replace("'", "").replace(" ", "%s") + "'"),
    )
    "apple" -> card.optString("config_entry_id").takeIf { it.isNotEmpty() }?.let {
        Act.Service("apple_tv", "set_keyboard_text", emptyList(), JSONObject().put("config_entry_id", it).put("text", text))
    }
    else -> null
}

/** A remote action: universal-remote-card's `key` and `source`, else any normal HA action. */
fun remoteAct(card: JSONObject, action: JSONObject?): Act {
    action ?: return Act.None
    return when (action.optString("action")) {
        "key" -> keyService(card, action.optString("key"))
        "source" -> Act.Service("media_player", "select_source", listOf(card.optString("media_player_id")), JSONObject().put("source", action.optString("source")))
        else -> parseAct(action, card.optString("remote_id")).let { if (it == Act.Default) Act.None else it }
    }
}

/** A named element: the card's custom_action of that name, else a built-in button. */
fun customAction(card: JSONObject, name: String): JSONObject? =
    card.optJSONArray("custom_actions")?.objects()?.firstOrNull { it.optString("name") == name }

/** null or a blank name in rows: an empty, button-sized slot, as universal-remote-card leaves it. */
fun isGap(items: JSONArray, i: Int) = items.isNull(i) || (items.opt(i) as? String)?.isBlank() == true

// ---- UI ----

@Composable
fun RemoteCard(card: JSONObject, ctx: CardContext) {
    var typing by remember { mutableStateOf(false) }
    val rows = card.optJSONArray("rows") ?: JSONArray("""[["back","home","menu"],["touchpad"],["volume_down","play_pause","volume_up"]]""")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        (0 until rows.length()).forEach { r ->
            val row = rows.optJSONArray(r)
            if (row == null || (0 until row.length()).all { isGap(row, it) }) {
                if (row != null || isGap(rows, r)) Spacer(Modifier.height(56.dp)) // a blank row, one button tall
                return@forEach
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                (0 until row.length()).forEach { i ->
                    val item = row.opt(i)
                    when {
                        isGap(row, i) -> Spacer(Modifier.size(56.dp))
                        item is JSONArray -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            (0 until item.length()).forEach { j ->
                                if (isGap(item, j)) Spacer(Modifier.size(56.dp)) else Element(card, item.optString(j), ctx) { typing = true }
                            }
                        }
                        // The touchpad takes whatever width the row's other items leave, like it does in HA.
                        item is String && isTouchpad(card, item) -> Box(Modifier.weight(1f).padding(end = 12.dp)) { Element(card, item, ctx) { typing = true } }
                        item is String -> Element(card, item, ctx) { typing = true }
                        else -> Spacer(Modifier.size(56.dp))
                    }
                }
            }
        }
    }
    if (typing) TextEntry(onDismiss = { typing = false }) { text -> textService(card, text)?.let { run(it, ctx) }; typing = false }
}

@Composable
private fun Element(card: JSONObject, name: String, ctx: CardContext, onType: () -> Unit) {
    val custom = customAction(card, name)
    when {
        isTouchpad(card, name) -> Touchpad(card, custom, ctx)
        custom == null && name in setOf("keyboard", "textbox") -> RemoteButton(Icons.Rounded.Keyboard, null, onTap = onType)
        custom != null -> {
            val tap = remoteAct(card, custom.optJSONObject("tap_action"))
            val holdJson = custom.optJSONObject("hold_action")
            val repeat = holdJson?.optString("action") == "repeat"
            RemoteButton(
                iconFor(custom.optString("icon"), name), name.takeIf { iconFor(custom.optString("icon"), name) == null },
                onTap = ctx.runner(tap, null) ?: {},
                onHold = if (repeat) null else holdJson?.let { ctx.runner(remoteAct(card, it), null) },
                onDoubleTap = custom.optJSONObject("double_tap_action")?.let { ctx.runner(remoteAct(card, it), null) },
                repeat = repeat,
            )
        }
        else -> {
            val svc = defaultKeyService(card, name) ?: return
            RemoteButton(iconFor("", name), name.takeIf { iconFor("", name) == null }, onTap = { run(svc, ctx) }, repeat = name.startsWith("volume_") && name != "volume_mute")
        }
    }
}

private fun run(act: Act, ctx: CardContext) { ctx.runner(act, null)?.invoke() }

private fun isTouchpad(card: JSONObject, name: String) =
    customAction(card, name).let { it?.optString("type") == "touchpad" || (it == null && name == "touchpad") }

/** Which way a swipe went, once it has travelled [step] px; null until then. */
fun swipeDirection(dx: Float, dy: Float, step: Float): String? = when {
    abs(dx) < step && abs(dy) < step -> null
    abs(dx) > abs(dy) -> if (dx > 0) "right" else "left"
    else -> if (dy > 0) "down" else "up"
}

/** Round button; [repeat] re-fires every 150 ms while held (universal-remote-card's `hold_action: repeat`). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteButton(
    icon: ImageVector?, label: String?, onTap: () -> Unit,
    onHold: (() -> Unit)? = null, onDoubleTap: (() -> Unit)? = null, repeat: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val fire by rememberUpdatedState(onTap)
    val gesture = if (repeat) Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            fire()
            val repeater: Job = scope.launch { delay(400); while (true) { fire(); delay(150) } }
            waitForUpOrCancellation()
            repeater.cancel()
        }
    } else Modifier.combinedClickable(onClick = onTap, onLongClick = onHold, onDoubleClick = onDoubleTap)

    Box(
        Modifier.widthIn(min = 56.dp).height(56.dp).clip(RoundedCornerShape(28.dp)).background(Chip).then(gesture).padding(horizontal = if (icon == null) 18.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp))
        else Text(label.orEmpty().replaceFirstChar { it.uppercase() }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Swipe pad, like universal-remote-card's: every ~28dp of travel sends that direction's key, holding still after a
 * swipe repeats it (when the direction's hold_action is `repeat`, the card's default), and a tap sends center.
 * An arrow flashes on each key so you can see it registered.
 */
@Composable
private fun Touchpad(card: JSONObject, custom: JSONObject?, ctx: CardContext) {
    fun dirAct(dir: String) = custom?.optJSONObject(dir)?.optJSONObject("tap_action")?.let { remoteAct(card, it) } ?: defaultKeyService(card, dir) ?: Act.None
    fun repeats(dir: String) = custom == null || custom.optJSONObject(dir)?.optJSONObject("hold_action")?.optString("action") == "repeat"
    val center = custom?.optJSONObject("tap_action")?.let { remoteAct(card, it) } ?: defaultKeyService(card, "center") ?: Act.None
    val stepPx = with(LocalDensity.current) { 28.dp.toPx() }
    val scope = rememberCoroutineScope()
    var flash by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(flash) { if (flash != null) { delay(250); flash = null } }
    fun press(dir: String) { run(dirAct(dir), ctx); flash = dir to System.nanoTime() }

    Box(
        Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(28.dp)).background(Chip)
            .pointerInput(card) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var acc = Offset.Zero
                    var moved = false
                    var repeater: Job? = null
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val d = change.positionChange()
                        if (d == Offset.Zero) continue
                        change.consume()
                        acc += d
                        if (acc.getDistance() > viewConfiguration.touchSlop) moved = true
                        val dir = swipeDirection(acc.x, acc.y, stepPx) ?: continue
                        acc = Offset.Zero
                        press(dir)
                        repeater?.cancel()
                        if (repeats(dir)) repeater = scope.launch { delay(400); while (true) { press(dir); delay(120) } }
                    }
                    repeater?.cancel()
                    if (!moved) { run(center, ctx); flash = "center" to System.nanoTime() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val arrow = when (flash?.first) {
            "up" -> Icons.Rounded.KeyboardArrowUp
            "down" -> Icons.Rounded.KeyboardArrowDown
            "left" -> Icons.Rounded.KeyboardArrowLeft
            "right" -> Icons.Rounded.KeyboardArrowRight
            else -> null
        }
        if (arrow != null) Icon(arrow, null, tint = Color.White, modifier = Modifier.size(56.dp))
        else Box(Modifier.size(if (flash != null) 22.dp else 14.dp).clip(CircleShape).background(if (flash != null) Color.White else TextDim))
    }
}

@Composable
private fun TextEntry(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type on the TV") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotEmpty(), onClick = { onSend(text) }) { Text("SEND") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

/** Material equivalents for the mdi icons and built-in names remotes use; null = show the name as text. */
private fun iconFor(mdi: String, name: String): ImageVector? = when (mdi.removePrefix("mdi:").ifEmpty { name }) {
    "power", "power-standby" -> Icons.Rounded.PowerSettingsNew
    "back", "arrow-left" -> Icons.AutoMirrored.Rounded.ArrowBack
    "home" -> Icons.Rounded.Home
    "menu" -> Icons.Rounded.Menu
    "keyboard", "textbox" -> Icons.Rounded.Keyboard
    "volume_up", "volume-plus", "volume-high" -> Icons.AutoMirrored.Rounded.VolumeUp
    "volume_down", "volume-minus", "volume-medium" -> Icons.AutoMirrored.Rounded.VolumeDown
    "volume_mute", "volume-mute", "volume-off" -> Icons.AutoMirrored.Rounded.VolumeOff
    "rewind", "skip_backward", "rewind-10" -> Icons.Rounded.FastRewind
    "fast_forward", "fast-forward", "skip_forward", "fast-forward-10" -> Icons.Rounded.FastForward
    "play_pause", "play-pause", "play" -> Icons.Rounded.PlayArrow
    "stop" -> Icons.Rounded.Stop
    "previous", "skip-previous" -> Icons.Rounded.SkipPrevious
    "next", "skip-next" -> Icons.Rounded.SkipNext
    "up", "chevron-up" -> Icons.Rounded.KeyboardArrowUp
    "down", "chevron-down" -> Icons.Rounded.KeyboardArrowDown
    "left", "chevron-left" -> Icons.Rounded.KeyboardArrowLeft
    "right", "chevron-right" -> Icons.Rounded.KeyboardArrowRight
    "microsoft-xbox-controller", "controller", "gamepad-variant" -> Icons.Rounded.SportsEsports
    else -> null
}

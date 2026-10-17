package home.kiosk

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject


fun isCameraCard(card: JSONObject) = when (card.optString("type")) {
    "picture-entity", "picture-glance" -> cameraIds(card).isNotEmpty()
    "custom:advanced-camera-card", "custom:frigate-card" -> true
    "tile", "entity" -> card.optString("entity").startsWith("camera.")
    else -> false
}

/** advanced-camera-card: cameras[].camera_entity; picture cards: camera_image, else a camera `entity`. */
fun cameraIds(card: JSONObject): List<String> {
    card.optJSONArray("cameras")?.let { arr -> return arr.objects().mapNotNull { it.optString("camera_entity").takeIf(String::isNotEmpty) } }
    val img = card.optString("camera_image")
    if (img.startsWith("camera.")) return listOf(img)
    return listOfNotNull(card.optString("entity").takeIf { it.startsWith("camera.") })
}

/** Snapshot refresh: "live" cards every 3 s, others every 10 s (HA's picture cards use 10 s). Live video isn't streamed. */
fun cameraRefreshMs(card: JSONObject) =
    if (card.optString("camera_view") == "live" || card.optString("type") in setOf("custom:advanced-camera-card", "custom:frigate-card")) 3_000L else 10_000L

/** The snapshot URL for this refresh; HA rotates the token inside entity_picture, so it's read fresh each time. */
fun snapshotUrl(base: String, entityPicture: String, now: Long): String? {
    if (entityPicture.isEmpty() || entityPicture == "null") return null
    val sep = if ('?' in entityPicture) '&' else '?'
    return base.trimEnd('/') + entityPicture + sep + "t=" + now
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraCard(card: JSONObject, ctx: CardContext) {
    val ids = cameraIds(card)
    var index by remember { mutableIntStateOf(0) }
    val camId = ids.getOrNull(index) ?: return
    val base = LocalSettings.current.haUrl
    val picture by rememberUpdatedState(ctx.states[camId]?.attributes?.optString("entity_picture").orEmpty())
    val refresh = cameraRefreshMs(card)

    // Refreshes only while the card is on screen; keeps the last frame while the next one loads.
    val frame = polled(camId, refresh) { snapshotUrl(base, picture, System.currentTimeMillis())?.let { fetchImage(it, maxPx = 800) } }

    // picture-entity overlays another entity's name/state (e.g. the nursery temperature over the Nanit camera).
    val labelEntity = card.optString("entity").takeIf { it.isNotEmpty() }?.let { ctx.states[it] } ?: ctx.states[camId]
    val name = card.optString("name").ifEmpty { labelEntity?.name ?: camId }
    val tapId = card.optJSONObject("tap_action")?.optString("entity")?.takeIf { it.isNotEmpty() } ?: camId
    val tap = ctx.runner(tapAct(card, tapId), tapId) ?: { ctx.openMoreInfo(camId) }
    val ptz = card.optJSONArray("cameras")?.optJSONObject(index)?.optJSONObject("ptz")

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .combinedClickable(onClick = tap, onLongClick = card.optJSONObject("hold_action")?.let { ctx.runner(holdAct(card, camId), camId) }),
        ) {
            if (frame != null) Image(frame, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Rounded.Videocam, null, tint = TextDim, modifier = Modifier.align(Alignment.Center).size(40.dp))
            if (card.optBoolean("show_name", true) || card.optBoolean("show_state", true)) {
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(14.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (card.optBoolean("show_name", true)) Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (card.optBoolean("show_state", true) && labelEntity != null && labelEntity.id != camId) Text(stateText(labelEntity), color = Color.White, fontSize = 15.sp)
                }
            }
            ptz?.let { PtzPad(it, ctx, Modifier.align(Alignment.TopEnd).padding(10.dp)) }
        }
        if (ids.size > 1) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ids.forEachIndexed { i, id ->
                    val on = i == index
                    Text(
                        ctx.states[id]?.name ?: id, color = if (on) CardBg else Color.White, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Amber else Chip)
                            .clickable { index = i }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** advanced-camera-card `ptz.actions_*` as a small arrow pad over the picture. */
@Composable
private fun PtzPad(ptz: JSONObject, ctx: CardContext, modifier: Modifier) {
    fun run(dir: String): (() -> Unit)? = ptz.optJSONObject("actions_$dir")?.let { a -> ctx.runner(parseAct(a, null), null) }
    val bg = Color(0x99000000)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        run("up")?.let { RoundButton(Icons.Rounded.KeyboardArrowUp, bg, size = 36, onClick = it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            run("left")?.let { RoundButton(Icons.Rounded.KeyboardArrowLeft, bg, size = 36, onClick = it) } ?: Spacer(Modifier.size(36.dp))
            run("home")?.let { RoundButton(Icons.Rounded.CenterFocusStrong, bg, size = 36, onClick = it) } ?: Spacer(Modifier.size(36.dp))
            run("right")?.let { RoundButton(Icons.Rounded.KeyboardArrowRight, bg, size = 36, onClick = it) } ?: Spacer(Modifier.size(36.dp))
        }
        run("down")?.let { RoundButton(Icons.Rounded.KeyboardArrowDown, bg, size = 36, onClick = it) }
    }
}

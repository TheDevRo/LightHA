package home.kiosk

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Date

private val Amber = Color(0xFFFFB74D)

/** Card types drawn as the media card (plus tiles pointing at a media_player). */
val MEDIA_CARDS = setOf("media-control", "custom:yet-another-media-player", "custom:mushroom-media-player-card")

fun isMediaCard(card: JSONObject) =
    card.optString("type") in MEDIA_CARDS || (card.optString("type") == "tile" && card.optString("entity").startsWith("media_player."))

/** media-control/mushroom/tile use `entity`; YAMP lists `entities` as strings or {entity_id}. */
fun mediaIds(card: JSONObject): List<String> {
    card.optJSONArray("entities")?.let { arr ->
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { it.optString("entity_id").ifEmpty { it.optString("entity") } } ?: arr.optString(i)
        }.filter { it.isNotEmpty() }
    }
    return listOfNotNull(card.optString("entity").takeIf { it.isNotEmpty() })
}

@Composable
fun MediaCard(card: JSONObject, ctx: CardContext) {
    val ids = mediaIds(card)
    var chosen by remember { mutableStateOf<String?>(null) }
    val id = chosen ?: activePlayer(ids, ctx.states) ?: return
    val e = ctx.states[id] ?: return
    val svc = { service: String, data: JSONObject? -> ctx.call("media_player", service, listOf(id), data) }
    val hold = ctx.runner(holdAct(card, id), id)
    val active = e.state == "playing" || e.state == "paused"

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg)) {
        if (active) {
            Box(Modifier.fillMaxWidth().height(200.dp).clickable { hold?.invoke() }) {
                Art(e)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0xEE17191E)))))
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text(e.attributes.text("media_title").ifEmpty { e.name }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(e.attributes.text("media_artist"), cardTitle(card, e)).filter { it.isNotEmpty() }.joinToString(" · "),
                        color = Color(0xFFCCCCCC), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Progress(e)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (supports(e, MP_PREVIOUS)) RoundButton(Icons.Rounded.SkipPrevious, size = 44) { svc("media_previous_track", null) }
                Spacer(Modifier.width(10.dp))
                RoundButton(if (e.state == "playing") Icons.Rounded.Pause else Icons.Rounded.PlayArrow, Amber, CardBg, 52) { svc("media_play_pause", null) }
                Spacer(Modifier.width(10.dp))
                if (supports(e, MP_NEXT)) RoundButton(Icons.Rounded.SkipNext, size = 44) { svc("media_next_track", null) }
                if (supports(e, MP_VOLUME_SET)) Volume(e, Modifier.weight(1f).padding(start = 16.dp)) { svc("volume_set", JSONObject().put("volume_level", it)) }
            }
        } else {
            // Idle or off: one quiet line instead of an empty art block.
            Row(Modifier.fillMaxWidth().clickable { hold?.invoke() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cardTitle(card, e), color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stateText(e).ifEmpty { e.state }, color = TextDim, fontSize = 12.sp)
                }
                if (!e.unavailable && supports(e, MP_PLAY)) RoundButton(Icons.Rounded.PlayArrow) { svc("media_play", null) }
            }
        }
        if (ids.size > 1) PlayerChips(ids, id, ctx.states) { chosen = it }
    }
}

private fun cardTitle(card: JSONObject, e: Entity) = card.optString("name").ifEmpty { e.name }

@Composable
private fun Art(e: Entity) {
    val img = rememberImage(e.attributes.text("entity_picture").takeIf { it.isNotEmpty() })
    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Box(Modifier.fillMaxSize().background(Chip))
}

@Composable
private fun Progress(e: Entity) {
    val duration = e.attributes.optDouble("media_duration").takeUnless { it.isNaN() || it <= 0 } ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(e.state) {
        while (e.state == "playing") { delay(1000); now = System.currentTimeMillis() }
    }
    val pos = mediaPosition(e, Date(now)) ?: return
    Column(Modifier.padding(horizontal = 20.dp)) {
        LinearProgressIndicator(
            progress = { (pos / duration).toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Amber, trackColor = Chip,
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(clockText(pos), color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(clockText(duration), color = TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Volume(e: Entity, modifier: Modifier, set: (Double) -> Unit) {
    val vol = e.attributes.optDouble("volume_level").takeUnless { it.isNaN() }?.toFloat() ?: return
    var local by remember(vol) { mutableFloatStateOf(vol) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeUp, null, tint = TextDim, modifier = Modifier.size(20.dp))
        Slider(
            local, { local = it }, onValueChangeFinished = { set(local.toDouble()) },
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Chip),
        )
    }
}

@Composable
private fun PlayerChips(ids: List<String>, current: String, states: Map<String, Entity>, pick: (String) -> Unit) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.forEach { id ->
            val on = id == current
            val playing = states[id]?.state == "playing"
            Text(
                (states[id]?.name ?: id) + if (playing && !on) " ♪" else "",
                color = if (on) CardBg else Color.White, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Amber else Chip)
                    .clickable { pick(id) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ---- album art: HA serves it through its own proxy (`entity_picture`), so it works without internet ----

private val imageHttp = OkHttpClient()
private val imageCache = LruCache<String, ImageBitmap>(12)

/** Fetches and decodes an image, downsampled to ~[maxPx] wide. Null on any failure. */
suspend fun fetchImage(fullUrl: String, maxPx: Int = 600): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = imageHttp.newCall(Request.Builder().url(fullUrl).build()).execute().use { it.body!!.bytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

/** Loads an HA-relative or absolute image URL, cached. Null while loading or on failure. */
@Composable
fun rememberImage(url: String?): ImageBitmap? {
    val base = LocalSettings.current.haUrl.trimEnd('/')
    return produceState(url?.let(imageCache::get), url) {
        if (url == null || value != null) return@produceState
        value = fetchImage(if (url.startsWith("/")) base + url else url)?.also { imageCache.put(url, it) }
    }.value
}

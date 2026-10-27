package home.kiosk

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** An Assist timer running for this tablet: [left] seconds as of [at] (elapsedRealtime), frozen while paused. */
data class AssistTimer(val id: String, val name: String, val left: Int, val at: Long, val active: Boolean) {
    fun secondsLeft(now: Long) = if (active) maxOf(0, left - ((now - at) / 1000).toInt()) else left
}

/** This tablet's timers, shown in the header; see MainActivity's timer refresh. */
val activeTimers = MutableStateFlow<List<AssistTimer>>(emptyList())

/**
 * HA's HassTimerStatus intent response (POST /api/intent/handle) lists every timer in the house in speech_slots;
 * keep the ones started from [deviceId], soonest first.
 */
fun parseTimers(response: String, deviceId: String, now: Long): List<AssistTimer> {
    val arr = JSONObject(response).optJSONObject("speech_slots")?.optJSONArray("timers") ?: return emptyList()
    return (0 until arr.length()).map { arr.getJSONObject(it) }
        .filter { it.optString("device_id") == deviceId }
        .map { AssistTimer(it.optString("id"), it.optString("name"), it.optInt("total_seconds_left"), now, it.optBoolean("is_active", true)) }
        .sortedBy { it.left }
}

/** 75 → "1:15", 3725 → "1:02:05". */
fun clock(seconds: Int) = (seconds / 3600).let { h ->
    val m = seconds / 60 % 60; val s = seconds % 60
    if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Header chips counting down each timer; one that reaches zero hides itself until HA's finished alert arrives. */
@Composable
fun TimerChips(timers: List<AssistTimer>) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(timers) { while (true) { now = SystemClock.elapsedRealtime(); delay(1_000) } }
    timers.forEach { t ->
        val left = t.secondsLeft(now).takeIf { it > 0 } ?: return@forEach
        Row(
            Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(50)).background(Chip).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (t.active) Icons.Rounded.Timer else Icons.Rounded.Pause, null, tint = Amber, modifier = Modifier.size(18.dp))
            Text(listOf(t.name.replaceFirstChar { it.uppercase() }, clock(left)).filter { it.isNotEmpty() }.joinToString(" "), color = Amber, fontSize = 15.sp, maxLines = 1)
        }
    }
}

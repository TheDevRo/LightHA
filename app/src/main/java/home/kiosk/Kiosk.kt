package home.kiosk

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Starts the dashboard after a reboot (when "Start on boot" is on). Reliable on Android 5–9. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Kiosk switches that live in the package manager (manifest components), not in preferences. */
object KioskComponents {
    private fun homeAlias(ctx: Context) = ComponentName(ctx, "home.kiosk.HomeAlias")
    private fun boot(ctx: Context) = ComponentName(ctx, BootReceiver::class.java)

    private fun enabled(ctx: Context, c: ComponentName) =
        ctx.packageManager.getComponentEnabledSetting(c) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun set(ctx: Context, c: ComponentName, on: Boolean) = ctx.packageManager.setComponentEnabledSetting(
        c,
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )

    fun isHomeApp(ctx: Context) = enabled(ctx, homeAlias(ctx))

    /** Turning it on offers LightHA as a home app; Android's picker then makes it the default. */
    fun setHomeApp(ctx: Context, on: Boolean) {
        set(ctx, homeAlias(ctx), on)
        val picker = Intent(AndroidSettings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(picker) }.onFailure {
            // Some builds (older Fire OS) lack the home settings screen; the HOME intent shows the chooser instead.
            runCatching { ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun startsOnBoot(ctx: Context) = enabled(ctx, boot(ctx))
    fun setStartOnBoot(ctx: Context, on: Boolean) = set(ctx, boot(ctx), on)
}

/** Where the screensaver clock sits this minute: walks a fixed path so no pixel stays lit for long (burn-in). */
fun saverOffset(minute: Long, width: Float, height: Float): Pair<Float, Float> {
    val xs = floatArrayOf(0.15f, 0.55f, 0.30f, 0.65f, 0.20f, 0.45f)
    val ys = floatArrayOf(0.20f, 0.60f, 0.40f, 0.15f, 0.70f, 0.35f)
    val i = (minute % xs.size).toInt()
    return xs[i] * width to ys[i] * height
}

/** Black screen with a dim clock that moves each minute. Any tap wakes. */
@Composable
fun Screensaver(onWake: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000 - now % 60_000); now = System.currentTimeMillis() }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { onWake() } },
    ) {
        val (x, y) = saverOffset(now / 60_000, maxWidth.value * 0.6f, maxHeight.value * 0.8f)
        Column(Modifier.offset(x.dp, y.dp)) {
            Text(SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(now)), color = Color(0xFF6A6F78), fontSize = 72.sp, fontWeight = FontWeight.Light)
            Text(SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(now)), color = Color(0xFF4A4F58), fontSize = 18.sp)
        }
    }
}

/** True when the dashboard has sat untouched long enough to start the screensaver. */
fun shouldStartSaver(idleMs: Long, minutes: Int, busy: Boolean) = minutes > 0 && !busy && idleMs >= minutes * 60_000L


/**
 * Screen follows an HA entity (motion, a room's lights, presence, a switch...): true = wake, false = sleep, null = leave it.
 * Sleep waits for a minute without touches, so a motion sensor clearing doesn't blank the screen mid-tap.
 */
fun followAction(e: Entity?, idleMs: Long, busy: Boolean): Boolean? = when {
    e == null || e.unavailable -> null
    isActive(e) || e.state == "home" || e.state == "detected" -> true
    busy || idleMs < 60_000L -> null
    else -> false
}

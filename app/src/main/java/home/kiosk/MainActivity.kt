package home.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Amber = Color(0xFFFFB74D)

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private var ha: HaClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
        settings = Settings(prefs)
        applyWindow()

        val ha = HaClient(settings.haUrl, settings.haToken, lifecycleScope, settings.haRefresh).also { this.ha = it }
        val lovelace = Lovelace(ha, lifecycleScope, prefs, java.io.File(filesDir, "gallery.json").takeIf { BuildConfig.DEBUG })
        val voice = Voice(ha, settings.haUrl, lifecycleScope, settings)

        // Wake word: listens while no Assist turn is running, hands the mic over when it hears its word.
        wake = WakeWordListener(this, onMicBroken = { settings.micBroken = true }) { wakeScreen(); beep(); voice.start(settings.voicePipeline) }
        lifecycleScope.launch {
            combine(voice.state, snapshotFlow { (settings.wakeWord to settings.wakeSensitivity).takeIf { !settings.micBroken } }) { turn, w -> w.takeIf { turn == null } }
                .distinctUntilChanged()
                .collect { w -> if (w == null) wake?.stop() else wake?.start(w.first, w.second) }
        }
        // Watchdog: if the listener ever ends while no turn is running (a missed state change, an odd failure),
        // bring it back within a few seconds. start() is a no-op while it's healthy.
        lifecycleScope.launch {
            while (true) {
                delay(5_000)
                if (voice.state.value == null && settings.wakeWord.isNotEmpty() && !settings.micBroken) wake?.start(settings.wakeWord, settings.wakeSensitivity)
            }
        }
        ha.start()

        // Follow the chosen entity even while Android's own screensaver (e.g. ImmichFrame) covers the app,
        // when Compose is paused: on wakes the screen and keeps it on, off hands it back to the system timeout.
        lifecycleScope.launch {
            ha.states.map { it[settings.wakeEntity]?.let { e -> followAction(e, Long.MAX_VALUE, busy = false) } }
                .distinctUntilChanged()
                .collect { on ->
                    if (on == null) return@collect
                    followOn = on
                    if (on) wakeScreen()
                    applyWindow()
                }
        }
        lifecycleScope.launch { snapshotFlow { settings.cameraMotion }.collect { applyMotion() } }

        // Sensors go to HA when switched on; everything known so far is resent after each (re)connect.
        mobile = MobileApp(ha, settings)
        lifecycleScope.launch {
            combine(ha.status, snapshotFlow { settings.reportSensors }) { s, on -> s == Status.CONNECTED && on }
                .distinctUntilChanged()
                .collect { if (it) send(sensors.values.toList()) }
        }
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        setContent {
            CompositionLocalProvider(LocalSettings provides settings, LocalHa provides ha) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Amber, onPrimary = CardBg, background = Bg, surface = Bg)) {
                    Box(Modifier.fillMaxSize().background(Bg)) { Home(ha, lovelace, voice, onReconnect = ::recreate) }
                }
            }
        }
    }

    override fun onDestroy() {
        wake?.stop()
        unregisterReceiver(battery)
        motion?.stop()
        ha?.stop()
        super.onDestroy()
    }

    // Sheets and dialogs bring the bars back; hide them again whenever the dashboard regains focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyWindow()
    }

    /** True while the followed entity is on: keeps the screen awake like [Settings.keepScreenOn]. */
    private var followOn = false

    private var motion: CameraMotion? = null
    private var motionOff: Job? = null

    /** Camera watching runs for the activity's whole life, so it keeps working under a system screensaver. */
    private fun applyMotion() {
        motion?.stop()
        motion = null
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (settings.cameraMotion) reportMotion(false)
        if (settings.cameraMotion && granted) motion = CameraMotion({ settings.motionSensitivity / 100f }, ::onMotion).takeIf { it.start() }
    }

    /** Wake, then report binary_sensor.kiosk_<device>_motion to HA: on now, off after a quiet minute. */
    private fun onMotion() {
        wakeScreen()
        lastMotion.value = SystemClock.elapsedRealtime()
        if (motionOff?.isActive != true) reportMotion(true)
        motionOff?.cancel()
        motionOff = lifecycleScope.launch { delay(60_000); reportMotion(false) }
    }

    private fun reportMotion(on: Boolean) = report(Sensor("camera_motion", "Camera motion", on, "motion"))

    private var wake: WakeWordListener? = null

    private fun beep() = ToneGenerator(AudioManager.STREAM_MUSIC, 70).let { t ->
        t.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        window.decorView.postDelayed({ t.release() }, 300)
    }

    private var mobile: MobileApp? = null
    /** Latest value of every sensor, by id. */
    private val sensors = mutableMapOf<String, Sensor>()

    private fun report(vararg changed: Sensor) {
        changed.forEach { sensors[it.id] = it }
        if (settings.reportSensors && ha?.status?.value == Status.CONNECTED) send(changed.toList())
    }

    private fun send(list: List<Sensor>) {
        val m = mobile ?: return
        if (list.isNotEmpty()) lifecycleScope.launch { runCatching { m.report(list) }.onFailure { Log.w("MobileApp", "report failed: $it") } }
    }

    // Fires on every small change (voltage too); only level, charger and whole degrees go to HA.
    private val battery = object : BroadcastReceiver() {
        private var last: Battery? = null
        override fun onReceive(context: Context, intent: Intent) {
            val now = Battery.from(intent)
            if (now != last) { last = now; report(*now.sensors().toTypedArray()) }
        }
    }

    /** Turns the screen on, which also ends a system screensaver, and restarts the screen timeout. */
    @Suppress("DEPRECATION") // no non-deprecated way to wake the screen from an app
    private fun wakeScreen() = (getSystemService(POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "kiosk:follow").acquire(3_000)

    /** True while the screensaver is showing: it dims the screen to its own brightness. */
    var saverActive = false

    /** Screen-on, brightness and fullscreen from [Settings]. */
    fun applyWindow() {
        if (settings.keepScreenOn || followOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = when {
                saverActive -> settings.screensaverBrightness
                settings.brightness < 0 -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                else -> settings.brightness
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).run {
            if (settings.fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(ha: HaClient, lovelace: Lovelace, voice: Voice, onReconnect: () -> Unit) {
    val settings = LocalSettings.current
    val activity = LocalContext.current as MainActivity
    val states by ha.states.collectAsStateWithLifecycle()
    val status by ha.status.collectAsStateWithLifecycle()
    val config by lovelace.config.collectAsStateWithLifecycle()
    val error by lovelace.error.collectAsStateWithLifecycle()
    val dashboard by lovelace.urlPath.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Edit mode = a draft copy of the config; null when just viewing.
    var draft by remember { mutableStateOf<JSONObject?>(null) }
    var original by remember { mutableStateOf<JSONObject?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    // First launch of a release build has no server yet: start in Settings.
    var showSettings by remember { mutableStateOf(settings.haUrl.isBlank() || (settings.haToken.isBlank() && settings.haRefresh.isBlank())) }
    var pinFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var popup by remember { mutableStateOf<String?>(null) }
    var moreInfo by remember { mutableStateOf<String?>(null) }
    var subview by remember { mutableStateOf<Int?>(null) }
    var confirmAct by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var confirmLock by remember { mutableStateOf<Pair<Entity, String>?>(null) }
    var renaming by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var addingTo by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    var saver by remember { mutableStateOf(false) }
    LaunchedEffect(settings.keepScreenOn, settings.brightness, settings.fullscreen, saver, settings.screensaverBrightness) {
        activity.saverActive = saver
        activity.applyWindow()
    }

    val editing = draft != null
    val cfg = draft ?: config
    val views = cfg?.let(::viewsOf).orEmpty()
    // Tabs: every non-subview view this HA user may see and the tablet hasn't switched off. Shown by default.
    val user by ha.user.collectAsStateWithLifecycle()
    val tabs = views.indices.filter {
        !views[it].optBoolean("subview") && viewVisible(views[it], user?.id) && !settings.isViewHidden(dashboard, viewKey(views[it], it))
    }
        .ifEmpty { views.indices.take(1) }
    val pager = rememberPagerState { tabs.size }
    val currentView = subview ?: tabs.getOrNull(pager.currentPage) ?: 0

    // Reopen on the last view, then remember where we are.
    var restored by remember(dashboard) { mutableStateOf(false) }
    LaunchedEffect(tabs, restored) {
        if (!restored && tabs.isNotEmpty()) {
            pager.scrollToPage(tabs.indexOfFirst { viewKey(views[it], it) == settings.lastView }.coerceAtLeast(0))
            restored = true
        }
    }
    LaunchedEffect(pager.currentPage, restored) {
        if (restored) tabs.getOrNull(pager.currentPage)?.let { settings.lastView = viewKey(views[it], it) }
    }

    // Return to the first view after N idle minutes.
    val lastTouch = remember { longArrayOf(System.currentTimeMillis()) }
    LaunchedEffect(settings.returnHomeMinutes) {
        val limit = settings.returnHomeMinutes * 60_000L
        while (limit > 0) {
            delay(10_000)
            val idle = System.currentTimeMillis() - lastTouch[0] > limit
            if (idle && draft == null && (pager.currentPage != 0 || subview != null)) {
                subview = null; popup = null; moreInfo = null
                pager.animateScrollToPage(0)
            }
        }
    }

    // Screensaver after N idle minutes (not while editing or with a sheet open).
    LaunchedEffect(settings.screensaverMinutes) {
        while (settings.screensaverMinutes > 0) {
            delay(5_000)
            val busy = draft != null || popup != null || moreInfo != null || sheet || showSettings
            if (!saver && shouldStartSaver(System.currentTimeMillis() - lastTouch[0], settings.screensaverMinutes, busy)) saver = true
        }
    }
    // Camera motion wakes the in-app screensaver too (the activity already woke the screen).
    val motionAt by lastMotion.collectAsStateWithLifecycle()
    LaunchedEffect(motionAt) { if (motionAt > 0) { saver = false; lastTouch[0] = System.currentTimeMillis() } }

    // Screen follows the chosen entity: wakes when it turns on, sleeps when it turns off.
    val follow = states[settings.wakeEntity]
    LaunchedEffect(follow?.state) {
        val busy = draft != null || popup != null || moreInfo != null || sheet || showSettings
        when (followAction(follow, System.currentTimeMillis() - lastTouch[0], busy)) {
            true -> { saver = false; lastTouch[0] = System.currentTimeMillis() }
            false -> saver = true
            null -> {}
        }
    }

    fun guarded(action: () -> Unit) { if (settings.pin.isEmpty()) action() else pinFor = action }
    fun openView(path: String) {
        val idx = cfg?.let { viewIndexFor(it, path) } ?: return
        if (idx in tabs) { subview = null; scope.launch { pager.animateScrollToPage(tabs.indexOf(idx)) } } else subview = idx
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(settings, ha, lovelace, onReconnect, onClose = { showSettings = false })
        return
    }
    BackHandler(enabled = subview != null) { subview = null }

    val call = Call { domain, service, ids, data -> ha.callService(domain, service, ids, data) }
    val ctx = CardContext(states, call, { e, s -> confirmLock = e to s }, { popup = it }, { moreInfo = it }, { text, run -> confirmAct = text to run }, ::openView, editing)
    val editsFor = { view: Int, section: Int ->
        if (!editing) null else SectionEdits(
            move = { i, d -> draft = Edits.move(draft!!, view, section, i, d) },
            remove = { i -> draft = Edits.remove(draft!!, view, section, i) },
            rename = { i -> renaming = Triple(view, section, i) },
            add = { addingTo = view to section },
        )
    }

    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) { awaitPointerEvent(PointerEventPass.Initial); lastTouch[0] = System.currentTimeMillis() }
            }
        },
    ) {
        // Header: view tabs on the left; settings + pencil (or Cancel/Save while editing) on the right.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (subview != null) {
                    Row(Modifier.clip(RoundedCornerShape(50)).clickable { subview = null }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                        Text("  " + viewTitle(views[subview!!], subview!!), color = Color.White, fontSize = 15.sp)
                    }
                } else if (tabs.size > 1) tabs.forEachIndexed { page, v ->
                    // Text tabs with an underline, so they don't read as badges/chips.
                    val on = page == pager.currentPage
                    Column(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { scope.launch { pager.animateScrollToPage(page) } }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            viewTitle(views[v], v), color = if (on) Color.White else TextDim, maxLines = 1,
                            fontSize = 17.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Box(Modifier.padding(top = 6.dp).width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) Amber else Color.Transparent))
                    }
                }
            }
            if (editing) {
                TextButton(onClick = { draft = null; saveError = null }) { Text("CANCEL") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    scope.launch {
                        runCatching { lovelace.save(draft!!, original!!) }
                            .onSuccess { ok -> if (ok) { draft = null; saveError = null } else conflict = true }
                            .onFailure { saveError = "Save failed: ${it.message}" }
                    }
                }) { Text("SAVE") }
            } else {
                // Amber while the wake word is listening: the only sign the mic is open (Android 7 shows none).
                val hearing by wakeListening.collectAsStateWithLifecycle()
                if (settings.voiceButton || settings.wakeWord.isNotEmpty()) IconButton(onClick = { voice.start(settings.voicePipeline) }) {
                    Icon(Icons.Rounded.Mic, "Ask Assist", tint = if (hearing) Amber else TextDim)
                }
                IconButton(onClick = { guarded { showSettings = true } }) { Icon(Icons.Rounded.Settings, "Settings", tint = TextDim) }
                IconButton(onClick = { guarded { sheet = true } }) { Icon(Icons.Rounded.Edit, "Edit dashboard", tint = TextDim) }
            }
        }
        val msg = when {
            status == Status.AUTH_FAILED -> "Home Assistant rejected the token. Open Settings to fix it."
            status == Status.NOT_CONFIGURED -> "Not connected: set up Home Assistant in Settings."
            status != Status.CONNECTED -> "Connecting to Home Assistant…"
            else -> saveError ?: error
        }
        msg?.let { Text(it, color = Color(0xFFE57373), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp)) }

        if (cfg != null) {
            if (subview != null) ViewPage(cfg, subview!!, ctx, editsFor)
            else HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = !editing, key = { tabs.getOrElse(it) { it } }) { page ->
                ViewPage(cfg, tabs[page], ctx, editsFor)
            }
        }
    }

    if (saver) Screensaver(onWake = { saver = false; lastTouch[0] = System.currentTimeMillis() })

    val talking by voice.state.collectAsStateWithLifecycle()
    talking?.let { VoiceOverlay(it, onClose = voice::stop) }

    pinFor?.let { action ->
        PinDialog("Enter PIN", onDismiss = { pinFor = null }) { if (it == settings.pin) { pinFor = null; action(); true } else false }
    }

    if (sheet) DashboardSheet(
        lovelace,
        onEditHere = { config?.let { draft = it; original = it }; sheet = false },
        onDismiss = { sheet = false },
    )

    popup?.let { hash ->
        val cards = cfg?.let { popupsOf(it)[hash] }.orEmpty()
        ModalBottomSheet(onDismissRequest = { popup = null }, containerColor = Bg) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cards.forEach { CardView(it, ctx.copy(editing = false)) }
            }
        }
    }

    moreInfo?.let { id -> MoreInfoSheet(id, states, call, onDismiss = { moreInfo = null }) }

    renaming?.let { (v, s, i) ->
        val card = sectionsOf(draft!!, v)[s][i]
        RenameDialog(cardLabel(card, states), onDone = { draft = Edits.rename(draft!!, v, s, i, it); renaming = null }, onDismiss = { renaming = null })
    }

    addingTo?.let { (v, s) ->
        EntityPicker(states, onPick = { draft = Edits.add(draft!!, v, s, Edits.cardFor(it)); addingTo = null }, onDismiss = { addingTo = null })
    }

    if (conflict) AlertDialog(
        onDismissRequest = { conflict = false },
        title = { Text("Dashboard changed elsewhere") },
        text = { Text("Someone saved this dashboard since you started editing. Reload to see their version (your edits here are discarded).") },
        confirmButton = { TextButton(onClick = { conflict = false; draft = null; scope.launch { lovelace.load() } }) { Text("RELOAD") } },
        dismissButton = { TextButton(onClick = { conflict = false }) { Text("KEEP EDITING") } },
    )

    confirmAct?.let { (text, run) ->
        AlertDialog(
            onDismissRequest = { confirmAct = null },
            title = { Text(text) },
            confirmButton = { TextButton(onClick = { run(); confirmAct = null }) { Text("YES") } },
            dismissButton = { TextButton(onClick = { confirmAct = null }) { Text("CANCEL") } },
        )
    }

    confirmLock?.let { (e, service) ->
        AlertDialog(
            onDismissRequest = { confirmLock = null },
            title = { Text("${service.replaceFirstChar { it.uppercase() }} ${e.name}?") },
            confirmButton = { TextButton(onClick = { ha.callService(e.domain, service, listOf(e.id)); confirmLock = null }) { Text(service.uppercase()) } },
            dismissButton = { TextButton(onClick = { confirmLock = null }) { Text("CANCEL") } },
        )
    }
}

/** One view: badges, then its sections in a staggered grid. */
@Composable
private fun ViewPage(cfg: JSONObject, view: Int, ctx: CardContext, editsFor: (Int, Int) -> SectionEdits?) {
    Box(Modifier.fillMaxSize()) {
        // The view's HA background image, faded to its opacity, under the cards (decoded once, cached).
        viewBackground(viewsOf(cfg).getOrNull(view))?.let { (url, alpha) ->
            rememberImage(url)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = alpha) }
        }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(300.dp),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalItemSpacing = 16.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = StaggeredGridItemSpan.FullLine) { Badges(badgesOf(cfg, view), ctx) }
            sectionsOf(cfg, view).forEachIndexed { s, cards ->
                item(key = "v$view-s$s") { Section(cards, ctx, editsFor(view, s)) }
            }
        }
    }
}

class SectionEdits(val move: (Int, Int) -> Unit, val remove: (Int) -> Unit, val rename: (Int) -> Unit, val add: () -> Unit)

/** One HA section: cards packed into rows of the 12-column grid. [edit] non-null = edit mode. */
@Composable
private fun Section(cards: List<JSONObject>, ctx: CardContext, edit: SectionEdits?) {
    val shown = if (edit != null) cards else cards.filter { visible(it, ctx.states) && !isPopup(it) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        packRows(shown).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { card ->
                    Box(Modifier.weight(widthOf(card).toFloat())) {
                        if (edit == null) CardView(card, ctx) else {
                            val i = cards.indexOf(card)
                            EditFrame({ edit.move(i, -1) }, { edit.move(i, 1) }, { edit.rename(i) }, { edit.remove(i) }) { CardView(card, ctx) }
                        }
                    }
                }
                val used = row.sumOf(::widthOf)
                if (used < 12) Spacer(Modifier.weight((12 - used).toFloat()))
            }
        }
        if (edit != null) OutlinedButton(onClick = edit.add, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, null)
            Text(" Add card", fontWeight = FontWeight.Medium)
        }
    }
}

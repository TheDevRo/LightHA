package home.kiosk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject


/** Full-screen on-tablet settings. [onReconnect] restarts the HA connection after URL/token changes. */
@Composable
fun SettingsScreen(settings: Settings, ha: HaClient, lovelace: Lovelace, onReconnect: () -> Unit, onClose: () -> Unit) {
    val status by ha.status.collectAsStateWithLifecycle()
    val states by ha.states.collectAsStateWithLifecycle()
    val config by lovelace.config.collectAsStateWithLifecycle()
    val dashboard by lovelace.urlPath.collectAsStateWithLifecycle()
    var dashboards by remember { mutableStateOf<List<DashboardInfo>>(emptyList()) }
    var haVersion by remember { mutableStateOf<String?>(null) }
    val me by ha.user.collectAsStateWithLifecycle()
    LaunchedEffect(status, me) { // re-list once we know who's signed in (admin-only dashboards drop out)
        if (status == Status.CONNECTED) {
            dashboards = runCatching { lovelace.dashboards() }.getOrDefault(dashboards)
            haVersion = runCatching { (ha.request("get_config") as JSONObject).optString("version") }.getOrNull()
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 40.dp).widthIn(max = 640.dp)) {

            Group("Home Assistant") {
                var url by remember { mutableStateOf(settings.haUrl) }
                var token by remember { mutableStateOf(settings.haToken) }
                // Fresh installs default to signing in with an account; tablets already on a token stay on it.
                var useAccount by remember { mutableStateOf(settings.haToken.isEmpty()) }
                val scope = rememberCoroutineScope()
                Text(
                    when (status) {
                        Status.CONNECTED -> "Connected" + (haVersion?.let { " · HA $it" } ?: "")
                        Status.CONNECTING -> "Connecting…"
                        Status.AUTH_FAILED -> if (settings.haRefresh.isNotEmpty()) "Signed out in Home Assistant. Sign in again." else "Token rejected"
                        Status.NOT_CONFIGURED -> "Enter your Home Assistant address, then sign in."
                    },
                    color = if (status == Status.CONNECTED) Color(0xFF81C784) else Color(0xFFE57373), fontSize = 14.sp,
                )
                OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, placeholder = { Text("http://homeassistant.local:8123") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Choices(listOf(1 to "Account", 0 to "Long-lived token"), if (useAccount) 1 else 0) { useAccount = it == 1 }
                /** Drop the account login (and remove it from the user's HA profile) when switching away from it. */
                suspend fun forgetAccount() {
                    if (settings.haRefresh.isNotEmpty()) HaAuth.revoke(settings.haUrl, settings.haRefresh)
                    settings.haRefresh = ""
                    settings.haUser = ""
                }
                if (useAccount) {
                    if (settings.haRefresh.isNotEmpty() && url.trim() == settings.haUrl && status != Status.AUTH_FAILED) {
                        Text("Signed in as ${settings.haUser}", color = Color.White, fontSize = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onReconnect) { Text("Reconnect") }
                            OutlinedButton(onClick = { scope.launch { forgetAccount(); onReconnect() } }) { Text("Sign out") }
                        }
                    } else {
                        SignInForm(url.trim()) { refresh, user ->
                            scope.launch {
                                forgetAccount()
                                settings.haUrl = url.trim(); settings.haRefresh = refresh; settings.haUser = user; settings.haToken = ""
                                onReconnect()
                            }
                        }
                    }
                } else {
                    OutlinedTextField(token, { token = it }, label = { Text("Long-lived access token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation())
                    Text("Profile → Security → Long-lived access tokens in Home Assistant.", color = TextDim, fontSize = 13.sp)
                    Button(
                        enabled = url.isNotBlank() && token.isNotBlank(),
                        onClick = { scope.launch { forgetAccount(); settings.haUrl = url.trim(); settings.haToken = token.trim(); onReconnect() } },
                    ) { Text(if (url == settings.haUrl && token == settings.haToken) "Reconnect" else "Save & reconnect") }
                }
            }

            if (dashboards.isNotEmpty()) Group("Dashboard") {
                dashboards.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { lovelace.select(d.urlPath) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (d.urlPath == dashboard) Icon(Icons.Rounded.Check, null, tint = Amber)
                    }
                }
            }

            config?.let { cfg ->
                Group("Views") {
                    Text("Switched-off views don't get a tab, but cards can still open them.", color = TextDim, fontSize = 13.sp)
                    viewsOf(cfg).forEachIndexed { i, v ->
                        if (!viewVisible(v, me?.id)) return@forEachIndexed // HA hides it from this user
                        val key = viewKey(v, i)
                        Toggle(viewTitle(v, i) + if (v.optBoolean("subview")) " (subview)" else "", !settings.isViewHidden(dashboard, key)) {
                            settings.setViewHidden(dashboard, key, !it)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Return to the first view after no touches for", color = Color.White, fontSize = 15.sp)
                    Choices(listOf(0 to "Never", 1 to "1 min", 2 to "2 min", 5 to "5 min", 10 to "10 min", 30 to "30 min"), settings.returnHomeMinutes) {
                        settings.returnHomeMinutes = it
                    }
                }
            }

            Group("Kiosk") {
                val context = LocalContext.current
                var home by remember { mutableStateOf(KioskComponents.isHomeApp(context)) }
                var boot by remember { mutableStateOf(KioskComponents.startsOnBoot(context)) }
                Toggle("Use as home screen", home) { KioskComponents.setHomeApp(context, it); home = it }
                Text(
                    if (home) "Pick LightHA in the list that opens, then Home and reboots land on the dashboard. Turn this off and pick your old launcher to leave."
                    else "Makes the Home button and reboots open the dashboard.",
                    color = TextDim, fontSize = 13.sp,
                )
                Toggle("Start after reboot", boot) { KioskComponents.setStartOnBoot(context, it); boot = it }
                Spacer(Modifier.height(8.dp))
                Text("Screensaver after no touches for", color = Color.White, fontSize = 15.sp)
                Choices(listOf(0 to "Never", 1 to "1 min", 2 to "2 min", 5 to "5 min", 10 to "10 min", 30 to "30 min"), settings.screensaverMinutes) {
                    settings.screensaverMinutes = it
                }
                if (settings.screensaverMinutes > 0) {
                    Text("Screensaver brightness", color = Color.White, fontSize = 15.sp)
                    Slider(
                        settings.screensaverBrightness, { settings.screensaverBrightness = it }, valueRange = 0.01f..0.5f,
                        colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Chip),
                    )
                }
                var picking by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Screen follows an entity", color = Color.White, fontSize = 15.sp)
                        Text(states[settings.wakeEntity]?.name ?: "Off", color = TextDim, fontSize = 13.sp)
                    }
                    if (settings.wakeEntity.isNotEmpty()) TextButton(onClick = { settings.wakeEntity = "" }) { Text("CLEAR") }
                    OutlinedButton(onClick = { picking = true }) { Text("Choose") }
                }
                if (picking) EntityPicker(
                    states,
                    onPick = { settings.wakeEntity = it; picking = false }, onDismiss = { picking = false },
                )
                Text("Wakes when it turns on (motion, a room's lights, someone home) and sleeps when it turns off, unless the screen was touched in the last minute.", color = TextDim, fontSize = 13.sp)
                val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { settings.cameraMotion = it }
                Toggle("Wake on camera motion", settings.cameraMotion) { on ->
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (on && !granted) askCamera.launch(Manifest.permission.CAMERA) else settings.cameraMotion = on
                }
                if (settings.cameraMotion) {
                    Text("Sensitivity", color = Color.White, fontSize = 15.sp)
                    Choices(listOf(15 to "Low", 6 to "Medium", 2 to "High"), settings.motionSensitivity) { settings.motionSensitivity = it }
                    Text(
                        "Compares tiny grayscale snapshots once a second. Nothing is saved or sent. Reported to Home Assistant when Sensors is on below.",
                        color = TextDim, fontSize = 13.sp,
                    )
                }
            }

            Group("Sensors") {
                Toggle("Report this tablet to Home Assistant", settings.reportSensors) { settings.reportSensors = it }
                Text(
                    "Adds a ${Build.MODEL} device in Home Assistant with battery level, charger and battery temperature, plus camera motion when that's on. Works with any account.",
                    color = TextDim, fontSize = 13.sp,
                )
            }

            Group("Voice") {
                val context = LocalContext.current
                val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { settings.voiceButton = it; if (it) settings.micBroken = false }
                Toggle("Assist button (push to talk)", settings.voiceButton) { on ->
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (on && !granted) askMic.launch(Manifest.permission.RECORD_AUDIO) else { settings.voiceButton = on; if (on) settings.micBroken = false }
                }
                var pendingWake by remember { mutableStateOf("") }
                val askMicWake = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) { settings.wakeWord = pendingWake; settings.micBroken = false } }
                Text("Wake word", color = Color.White, fontSize = 15.sp)
                Choices(listOf(-1 to "Off") + WAKE_WORDS.mapIndexed { i, w -> i to w.second }, WAKE_WORDS.indexOfFirst { it.first == settings.wakeWord }) { i ->
                    val id = WAKE_WORDS.getOrNull(i)?.first.orEmpty()
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (id.isNotEmpty() && !granted) { pendingWake = id; askMicWake.launch(Manifest.permission.RECORD_AUDIO) } else { settings.wakeWord = id; if (id.isNotEmpty()) settings.micBroken = false }
                }
                val wakeProblem by wakeError.collectAsStateWithLifecycle()
                (if (settings.micBroken) Voice.MIC_BROKEN else wakeProblem.takeIf { settings.wakeWord.isNotEmpty() })
                    ?.let { Text(it, color = Color(0xFFE57373), fontSize = 13.sp) }
                if (settings.wakeWord.isNotEmpty()) {
                    Text("Wake word sensitivity", color = Color.White, fontSize = 15.sp)
                    Choices(listOf(1 to "Low", 0 to "Medium", -1 to "High"), settings.wakeSensitivity) { settings.wakeSensitivity = it }
                    Text("Listens on the tablet itself. No audio leaves it until the wake word is heard. The mic in the header turns amber while it's listening. Try Low if a TV sets it off.", color = TextDim, fontSize = 13.sp)
                }
                if (settings.voiceButton || settings.wakeWord.isNotEmpty()) {
                    var pipelines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                    var preferred by remember { mutableStateOf("") }
                    LaunchedEffect(status) {
                        if (status != Status.CONNECTED) return@LaunchedEffect
                        runCatching { ha.request("assist_pipeline/pipeline/list") as JSONObject }.onSuccess { r ->
                            val arr = r.getJSONArray("pipelines")
                            pipelines = (0 until arr.length()).map { arr.getJSONObject(it).let { p -> p.getString("id") to p.optString("name") } }
                            preferred = pipelines.firstOrNull { it.first == r.optString("preferred_pipeline") }?.second.orEmpty()
                        }
                    }
                    Text("Pipeline", color = Color.White, fontSize = 15.sp)
                    Choices(
                        listOf(-1 to if (preferred.isEmpty()) "Default" else "Default ($preferred)") + pipelines.mapIndexed { i, p -> i to p.second },
                        pipelines.indexOfFirst { it.first == settings.voicePipeline },
                    ) { settings.voicePipeline = pipelines.getOrNull(it)?.first.orEmpty() }
                    Text("Say the wake word or tap the mic in the header, then talk. Home Assistant hears when you stop. Set up pipelines under Settings > Voice assistants in Home Assistant.", color = TextDim, fontSize = 13.sp)
                }
            }

            Group("Intercom") {
                val context = LocalContext.current
                // Calls work without the mic too (listen only), so a refusal still turns the switch on.
                var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
                val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pending?.invoke(); pending = null }
                fun withMic(set: () -> Unit) {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) set() else { pending = set; askMic.launch(Manifest.permission.RECORD_AUDIO) }
                }
                Toggle("Make calls", settings.intercomCall) { on -> if (on) withMic { settings.intercomCall = true } else settings.intercomCall = false }
                Toggle("Receive calls", settings.intercomReceive) { on -> if (on) withMic { settings.intercomReceive = true } else settings.intercomReceive = false }
                if (settings.intercomCall || settings.intercomReceive) {
                    OutlinedTextField(
                        settings.intercomName, { settings.intercomName = it.take(40) }, label = { Text("This tablet's name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "Make calls puts a call button in the header that lists every tablet with Receive calls on. " +
                        "Receive calls makes this tablet ring when called. With it off nobody can call this tablet. " +
                        "Hold the button to talk. Calls go straight between tablets on your network, and the wake word pauses during a call.",
                    color = TextDim, fontSize = 13.sp,
                )
            }

            Group("Display") {
                Toggle("Keep screen on", settings.keepScreenOn) { settings.keepScreenOn = it }
                Toggle("Fullscreen (hide status and navigation bars)", settings.fullscreen) { settings.fullscreen = it }
                Toggle("Use system brightness", settings.brightness < 0) { settings.brightness = if (it) -1f else 0.8f }
                if (settings.brightness >= 0) {
                    Slider(
                        settings.brightness, { settings.brightness = it }, valueRange = 0.05f..1f,
                        colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Chip),
                    )
                }
            }

            Group("Editing & security") {
                Toggle("Allow editing dashboards on this tablet", settings.allowTabletEditing) { settings.allowTabletEditing = it }
                var settingPin by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("PIN for settings and editing", color = Color.White, fontSize = 15.sp)
                        Text(if (settings.pin.isEmpty()) "Off" else "On", color = TextDim, fontSize = 13.sp)
                    }
                    if (settings.pin.isEmpty()) OutlinedButton(onClick = { settingPin = true }) { Text("Set PIN") }
                    else OutlinedButton(onClick = { settings.pin = "" }) { Text("Remove") }
                }
                if (settingPin) PinDialog(title = "Choose a PIN", onDismiss = { settingPin = false }) { settings.pin = it; settingPin = false; true }
            }

            Group("About") {
                Text("LightHA ${BuildConfig.VERSION_NAME}", color = Color.White, fontSize = 15.sp)
                Text("github.com/TheDevRo/LightHA", color = TextDim, fontSize = 13.sp)
            }
        }
    }
}

/** Asks for a PIN; [onPin] returns whether it was accepted (wrong PINs keep the dialog open). */
@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onPin: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(8); wrong = false },
                singleLine = true, isError = wrong, label = { Text(if (wrong) "Wrong PIN" else "4–8 digits") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = { TextButton(enabled = pin.length >= 4, onClick = { wrong = !onPin(pin) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text(title.uppercase(), color = TextDim, fontSize = 12.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { set(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = Amber, checkedThumbColor = CardBg, uncheckedTrackColor = Chip, uncheckedBorderColor = Chip))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(options: List<Pair<Int, String>>, selected: Int, pick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label, color = if (on) CardBg else Color.White, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Amber else Chip).clickable { pick(value) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** Username + password, then a 2-factor code if the account has one. [onSignedIn] gets the refresh token. */
@Composable
private fun SignInForm(url: String, onSignedIn: (refresh: String, user: String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var mfa by remember { mutableStateOf<HaAuth.Step?>(null) } // set while HA waits for the 2-factor code
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (mfa == null) {
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    } else {
        Text("Enter the code from your authenticator app.", color = Color.White, fontSize = 14.sp)
        OutlinedTextField(code, { code = it }, label = { Text("Code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    error?.let { Text(it, color = Color(0xFFE57373), fontSize = 14.sp) }
    Button(
        enabled = !busy && url.isNotBlank() && (if (mfa == null) user.isNotBlank() && pass.isNotEmpty() else code.isNotBlank()),
        onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    val step = mfa?.let { HaAuth.answer(url, it.flowId, mapOf("code" to code.trim())) }
                        ?: HaAuth.begin(url).let { HaAuth.answer(url, it.flowId, mapOf("username" to user.trim(), "password" to pass)) }
                    when {
                        step.code != null -> { pass = ""; onSignedIn(HaAuth.exchange(url, step.code).refresh, user.trim()) }
                        step.error != null -> { error = step.error; if (step.stepId != "mfa") mfa = null; code = "" }
                        step.stepId == "mfa" -> mfa = step
                        else -> { error = "This account's sign-in step (${step.stepId}) isn't supported yet"; mfa = null }
                    }
                } catch (e: Exception) {
                    error = "Couldn't reach Home Assistant: ${e.message}"
                } finally { busy = false }
            }
        },
    ) { Text(if (busy) "Signing in…" else "Sign in") }
}


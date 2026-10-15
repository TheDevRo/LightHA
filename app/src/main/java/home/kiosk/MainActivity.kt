package home.kiosk

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val ha = HaClient(BuildConfig.HA_URL, BuildConfig.HA_TOKEN, lifecycleScope)
        val lovelace = Lovelace(ha, lifecycleScope, getSharedPreferences("kiosk", Context.MODE_PRIVATE))
        ha.start()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFB74D), onPrimary = CardBg, background = Bg, surface = Bg)) {
                Box(Modifier.fillMaxSize().background(Bg)) { Home(ha, lovelace) }
            }
        }
    }

    // Sheets and dialogs bring the bars back; hide them again whenever the dashboard regains focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() = WindowCompat.getInsetsController(window, window.decorView).run {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(ha: HaClient, lovelace: Lovelace) {
    val states by ha.states.collectAsStateWithLifecycle()
    val status by ha.status.collectAsStateWithLifecycle()
    val config by lovelace.config.collectAsStateWithLifecycle()
    val error by lovelace.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Edit mode = a draft copy of the config; null when just viewing.
    var draft by remember { mutableStateOf<JSONObject?>(null) }
    var original by remember { mutableStateOf<JSONObject?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var popup by remember { mutableStateOf<String?>(null) }
    var moreInfo by remember { mutableStateOf<String?>(null) }
    var confirmAct by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var confirmLock by remember { mutableStateOf<Pair<Entity, String>?>(null) }
    var renaming by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var addingTo by remember { mutableStateOf<Int?>(null) }

    val editing = draft != null
    val shown = draft ?: config
    val call = Call { domain, service, ids, data -> ha.callService(domain, service, ids, data) }
    val ctx = CardContext(states, call, { e, s -> confirmLock = e to s }, { popup = it }, { moreInfo = it }, { text, run -> confirmAct = text to run }, editing)

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(300.dp),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalItemSpacing = 16.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val msg = when {
                    status == Status.AUTH_FAILED -> "HA token rejected"
                    status != Status.CONNECTED -> "Connecting…"
                    else -> saveError ?: error
                }
                Text(msg ?: "", color = Color(0xFFE57373), fontSize = 14.sp, modifier = Modifier.weight(1f))
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
                    IconButton(onClick = { sheet = true }) { Icon(Icons.Rounded.Edit, "Edit dashboard", tint = TextDim) }
                }
            }
        }
        shown?.let { cfg ->
            item(span = StaggeredGridItemSpan.FullLine) { Badges(badgesOf(cfg), ctx) }
            sectionsOf(cfg).forEachIndexed { s, cards ->
                item(key = "section$s") {
                    Section(
                        cards, ctx,
                        edit = if (!editing) null else SectionEdits(
                            move = { i, d -> draft = Edits.move(draft!!, s, i, d) },
                            remove = { i -> draft = Edits.remove(draft!!, s, i) },
                            rename = { i -> renaming = s to i },
                            add = { addingTo = s },
                        ),
                    )
                }
            }
        }
    }

    if (sheet) DashboardSheet(
        lovelace,
        onEditHere = { config?.let { draft = it; original = it }; sheet = false },
        onDismiss = { sheet = false },
    )

    popup?.let { hash ->
        val cards = shown?.let { popupsOf(it)[hash] }.orEmpty()
        ModalBottomSheet(onDismissRequest = { popup = null }, containerColor = Bg) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cards.forEach { CardView(it, ctx.copy(editing = false)) }
            }
        }
    }

    moreInfo?.let { id -> MoreInfoSheet(id, states, call, onDismiss = { moreInfo = null }) }

    renaming?.let { (s, i) ->
        val card = sectionsOf(draft!!)[s][i]
        RenameDialog(cardLabel(card, states), onDone = { draft = Edits.rename(draft!!, s, i, it); renaming = null }, onDismiss = { renaming = null })
    }

    addingTo?.let { s ->
        EntityPicker(states, onPick = { draft = Edits.add(draft!!, s, Edits.cardFor(it)); addingTo = null }, onDismiss = { addingTo = null })
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


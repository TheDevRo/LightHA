package home.kiosk

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The pencil's sheet: pick a dashboard, scan to edit on a phone, or start editing here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardSheet(lovelace: Lovelace, onEditHere: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dashboards by remember { mutableStateOf<List<DashboardInfo>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    val current = lovelace.urlPath.value
    LaunchedEffect(Unit) { dashboards = runCatching { lovelace.dashboards() }.getOrDefault(emptyList()) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardBg) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            val editUrl = "${LocalSettings.current.haUrl.trimEnd('/')}/$current/0?edit=1"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(remember(editUrl) { qr(editUrl) }, null, Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("Edit on your phone", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Scan to open this dashboard in Home Assistant's editor. The tablet updates when you save.", color = TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    if (!LocalSettings.current.allowTabletEditing) {
                        Text("Editing on this tablet is off (Settings).", color = TextDim, fontSize = 12.sp)
                    } else if (lovelace.editable) {
                        Button(onClick = onEditHere) { Text("Edit on this tablet") }
                    } else {
                        val info = dashboards.firstOrNull { it.urlPath == current }
                        OutlinedButton(enabled = info != null && busy == null, onClick = {
                            busy = "Copying…"
                            scope.launch {
                                runCatching { lovelace.createWallCopy(info!!) }
                                    .onSuccess { onDismiss() }
                                    .onFailure { busy = "Couldn't copy: ${it.message}" }
                            }
                        }) { Text("Make a wall copy to edit here") }
                        busy?.let { Text(it, color = TextDim, fontSize = 12.sp) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("DASHBOARD", color = TextDim, fontSize = 12.sp, letterSpacing = 1.5.sp)
            LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
                items(dashboards, key = { it.urlPath }) { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable { lovelace.select(d.urlPath); onDismiss() }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(d.title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (d.urlPath == current) Icon(Icons.Rounded.Check, null, tint = Color.White)
                    }
                }
            }
        }
    }
}

/** Edit-mode chrome around one top-level card: move up/down, rename, remove. */
@Composable
fun EditFrame(onUp: () -> Unit, onDown: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, TextDim, RoundedCornerShape(26.dp)).padding(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            RoundButton(Icons.Rounded.ArrowUpward, size = 32, onClick = onUp)
            RoundButton(Icons.Rounded.ArrowDownward, size = 32, onClick = onDown)
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.Rounded.Edit, size = 32, onClick = onRename)
            RoundButton(Icons.Rounded.Close, bg = Color(0xFF5A2A2A), size = 32, onClick = onRemove)
        }
        content()
    }
}

@Composable
fun EntityPicker(states: Map<String, Entity>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, states) {
        states.values.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }
            .sortedBy { it.name }.take(60)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a card") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search entities") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.height(360.dp)) {
                    items(matches, key = { it.id }) { e ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(e.id) }.padding(vertical = 10.dp)) {
                            Text(e.name, color = Color.White, fontSize = 15.sp)
                            Text(e.id, color = TextDim, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

@Composable
fun RenameDialog(current: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Card name") },
        text = { OutlinedTextField(name, { name = it }, placeholder = { Text("Entity's own name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(name) }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

fun cardLabel(card: JSONObject, states: Map<String, Entity>): String =
    card.optString("name").ifEmpty { card.optString("primary") }.takeIf { it.isNotEmpty() && "{{" !in it }
        ?: states[card.optString("entity")]?.name ?: ""

private fun qr(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
    val scale = 8
    val bmp = Bitmap.createBitmap((m.width + 4) * scale, (m.height + 4) * scale, Bitmap.Config.RGB_565)
    bmp.eraseColor(android.graphics.Color.WHITE)
    for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) {
        for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel((x + 2) * scale + dx, (y + 2) * scale + dy, android.graphics.Color.BLACK)
    }
    return bmp.asImageBitmap()
}

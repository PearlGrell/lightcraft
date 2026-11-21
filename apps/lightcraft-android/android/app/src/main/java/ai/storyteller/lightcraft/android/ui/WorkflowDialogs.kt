package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.ExportSettings
import ai.storyteller.lightcraft.android.LightCraftViewModel

/** Which workflow dialog is open from the editor's ⋮ menu. */
private enum class Dialog { None, History, Versions, Preset, Export }

/**
 * The editor's ⋮ menu: History, Versions, Copy / Paste Settings, Save as Preset and Export with
 * options. Sits with the other icons over the photo.
 */
@Composable
internal fun WorkflowMenu(vm: LightCraftViewModel) {
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(Dialog.None) }
    Box {
        OverlayIcon(Icons.Outlined.MoreVert, "More", Color.White) { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("History") }, onClick = { menu = false; vm.loadHistory(); dialog = Dialog.History })
            DropdownMenuItem(text = { Text("Versions") }, onClick = { menu = false; vm.loadHistory(); dialog = Dialog.Versions })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Copy settings") }, onClick = { menu = false; vm.copySettings() })
            DropdownMenuItem(text = { Text("Paste settings") }, onClick = { menu = false; vm.pasteSettings() })
            DropdownMenuItem(text = { Text("Save as preset…") }, onClick = { menu = false; dialog = Dialog.Preset })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Export…") }, onClick = { menu = false; dialog = Dialog.Export })
        }
    }
    when (dialog) {
        Dialog.History -> HistoryDialog(vm) { dialog = Dialog.None }
        Dialog.Versions -> VersionsDialog(vm) { dialog = Dialog.None }
        Dialog.Preset -> NameDialog("Save as preset", "Preset name", "", "Save", { vm.savePreset(it) }) { dialog = Dialog.None }
        Dialog.Export -> ExportDialog(vm) { dialog = Dialog.None }
        Dialog.None -> {}
    }
}

/** History: every step, newest last; tap one to go back to it (later steps stay until a new edit). */
@Composable
private fun HistoryDialog(vm: LightCraftViewModel, onClose: () -> Unit) {
    val h by vm.history.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("History") },
        text = {
            if (h.steps.isEmpty()) {
                Text("No edits yet.", color = Lc.label)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(h.steps) { i, label ->
                        val at = i == h.cursor
                        Text(
                            label.ifEmpty { "Edit" },
                            fontSize = 15.sp,
                            color = if (at) Lc.accent else if (i > h.cursor) Lc.muted else Lc.text,
                            modifier = Modifier.fillMaxWidth().clickable { vm.restoreHistory(i) }.padding(vertical = 9.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

/** Versions: named snapshots of the photo's settings. */
@Composable
private fun VersionsDialog(vm: LightCraftViewModel, onClose: () -> Unit) {
    val h by vm.history.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Versions") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (h.versions.isEmpty()) Text("No versions yet. Save one to come back to this look later.", color = Lc.label)
                h.versions.forEachIndexed { i, name ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontSize = 15.sp, color = Lc.text, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.restoreVersion(i) }) { Text("Restore") }
                        TextButton(onClick = { naming = i }) { Text("Rename") }
                        TextButton(onClick = { vm.deleteVersion(i) }) { Text("Delete", color = Color(0xFFFF6B6B)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { naming = -1 }) { Text("Save version") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
    naming?.let { i ->
        if (i < 0) {
            NameDialog("Save version", "Version name", "Version ${h.versions.size + 1}", "Save", { vm.createVersion(it) }) { naming = null }
        } else {
            NameDialog("Rename version", "Version name", h.versions.getOrElse(i) { "" }, "Rename", { vm.renameVersion(i, it) }) { naming = null }
        }
    }
}

/** A one-field name dialog. */
@Composable
private fun NameDialog(title: String, label: String, start: String, action: String, onDone: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onDone(name)
                onClose()
            }) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Export: format, size, quality, colour space and metadata, then the share sheet. */
@Composable
private fun ExportDialog(vm: LightCraftViewModel, onClose: () -> Unit) {
    val last by vm.exportSettings.collectAsStateWithLifecycle()
    var o by remember { mutableStateOf(last) }
    val lossy = o.format in setOf("jpeg", "webp", "avif", "jxl")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Export") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Label("Format")
                Chips(ExportSettings.formats, o.format) { o = o.copy(format = it) }
                Label("Size")
                Chips(ExportSettings.sizes.map { it.first.toString() to it.second }, o.longEdge.toString()) { o = o.copy(longEdge = it.toIntOrNull() ?: 0) }
                if (lossy) {
                    LrSlider("Quality", o.quality.toFloat(), 1f, 100f, 90f, 0, "", { o = o.copy(quality = it.toInt()) }, {}, { o = o.copy(quality = 90) })
                }
                Label("Color space")
                Chips(ExportSettings.spaces, o.colorSpace) { o = o.copy(colorSpace = it) }
                Label("Metadata")
                Chips(ExportSettings.metadataModes, o.metadata) { o = o.copy(metadata = it) }
                Toggle("Remove location", o.removeLocation, Modifier.fillMaxWidth()) { o = o.copy(removeLocation = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.export(o)
                onClose()
            }) { Text("Export & share") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 13.sp, color = Lc.label, modifier = Modifier.padding(top = 8.dp))
}

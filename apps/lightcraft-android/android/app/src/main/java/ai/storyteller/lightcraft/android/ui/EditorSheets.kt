package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.ExportFormat
import ai.storyteller.lightcraft.android.ExportSettings
import ai.storyteller.lightcraft.android.HistoryInfo
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.PhotoDetails
import ai.storyteller.lightcraft.android.PhotoInfo

/** The editor's bottom sheets, opened from the share button and the ⋮ menu. */
enum class EditorSheet { Export, Info, History }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorSheetHost(sheet: EditorSheet, vm: LightCraftViewModel, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Lc.sheet,
        scrimColor = Color(0x99000000),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        when (sheet) {
            EditorSheet.Export -> {
                val settings by vm.exportSettings.collectAsStateWithLifecycle()
                ExportSheet(settings, vm::setExport) { share ->
                    vm.exportPhoto(share)
                    onDismiss()
                }
            }
            EditorSheet.Info -> {
                LaunchedEffect(Unit) { vm.loadInfo() }
                val info by vm.info.collectAsStateWithLifecycle()
                InfoSheet(info, vm::rate, vm::flag, vm::label, vm::setDetails)
            }
            EditorSheet.History -> {
                LaunchedEffect(Unit) { vm.loadHistory() }
                val history by vm.history.collectAsStateWithLifecycle()
                HistorySheet(history, vm::restoreHistory, vm::restoreVersion, vm::createVersion)
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 14.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Lc.text)
        if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = Lc.muted, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = Lc.muted, modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 8.dp))
}

/** A row of choice chips, one selected (file type, size). */
@Composable
internal fun <T> ChoiceChips(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { (label, value) ->
            val on = value == selected
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) Color.White else Lc.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) Lc.accent else Lc.raised)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

// ---- export ------------------------------------------------------------------------------------

private val SIZES = listOf("Full size" to 0, "4096 px" to 4096, "2048 px" to 2048, "1080 px" to 1080)

/** Export as…: file type, size, quality, watermark, location; then Share or Save to device. */
@Composable
internal fun ExportSheet(settings: ExportSettings, onChange: (ExportSettings) -> Unit, onExport: (share: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        SheetTitle("Export as", "Your original is never changed")
        SectionLabel("File type")
        ChoiceChips(ExportFormat.entries.map { it.label to it }, settings.format) { onChange(settings.copy(format = it)) }
        SectionLabel("Dimensions")
        ChoiceChips(SIZES, settings.longEdge) { onChange(settings.copy(longEdge = it)) }
        if (settings.format.lossy) {
            Box(Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp)) {
                LrSlider("Quality", settings.quality.toFloat(), 10f, 100f, 90f, 0, "", { onChange(settings.copy(quality = it.toInt())) }, {}, { onChange(settings.copy(quality = 90)) })
            }
        }
        SectionLabel("Watermark")
        OutlinedTextField(
            value = settings.watermark,
            onValueChange = { onChange(settings.copy(watermark = it.take(80))) },
            singleLine = true,
            placeholder = { Text("© Your name", color = Lc.muted) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lc.accent, unfocusedBorderColor = Lc.divider, cursorColor = Lc.accent),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        )
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remove location info", fontSize = 15.sp, color = Lc.text)
                Text("GPS is left out of the copy", fontSize = 13.sp, color = Lc.muted)
            }
            Switch(
                checked = settings.removeLocation,
                onCheckedChange = { onChange(settings.copy(removeLocation = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = Lc.accent, checkedThumbColor = Color.White),
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onExport(false) }, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Outlined.Download, null, tint = Lc.text, modifier = Modifier.size(20.dp))
                Text("Save to device", color = Lc.text, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
            }
            Button(
                onClick = { onExport(true) },
                colors = ButtonDefaults.buttonColors(containerColor = Lc.accent),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.Outlined.Share, null, modifier = Modifier.size(20.dp))
                Text("Share", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

// ---- info and rating ---------------------------------------------------------------------------

@Composable
internal fun InfoSheet(
    info: PhotoInfo,
    onRate: (Int) -> Unit,
    onFlag: (String) -> Unit,
    onLabel: (String) -> Unit = {},
    onDetails: (PhotoDetails) -> Unit = {},
) {
    var details by remember(info.details) { mutableStateOf(info.details) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        SheetTitle("Info")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            (1..5).forEach { n ->
                Icon(
                    if (n <= info.rating) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    "$n stars",
                    tint = if (n <= info.rating) Lc.star else Lc.muted,
                    modifier = Modifier.clip(CircleShape).clickable { onRate(n) }.padding(5.dp).size(27.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            FlagButton(Icons.Filled.Flag, Icons.Outlined.Flag, "Pick", info.flag == "pick", Color.White) { onFlag("pick") }
            FlagButton(Icons.Outlined.Block, Icons.Outlined.Block, "Reject", info.flag == "reject", Color(0xFFFF6B6B)) { onFlag("reject") }
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Label", fontSize = 14.sp, color = Lc.muted, modifier = Modifier.width(64.dp))
            LABELS.forEach { (name, color) ->
                val on = info.label == name
                Box(
                    Modifier
                        .padding(end = 12.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(2.dp, if (on) Color.White else Color.Transparent, CircleShape)
                        .clickable { onLabel(name) },
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(Icons.Outlined.Check, "$name label", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
        HorizontalDivider(color = Lc.divider, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        if (info.lines.isEmpty()) {
            Text("No camera details in this file.", fontSize = 14.sp, color = Lc.muted, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
        }
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(info.lines) { line ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 9.dp)) {
                    Text(line.label, fontSize = 14.sp, color = Lc.muted, modifier = Modifier.width(120.dp))
                    Text(line.value, fontSize = 14.sp, color = Lc.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            item { SectionLabel("Details") }
            item { DetailField("Title", details.title) { details = details.copy(title = it) } }
            item { DetailField("Caption", details.caption) { details = details.copy(caption = it) } }
            item { DetailField("Copyright", details.copyright) { details = details.copy(copyright = it) } }
            item { DetailField("Creator", details.creator) { details = details.copy(creator = it) } }
            item {
                Button(
                    onClick = { onDetails(details) },
                    enabled = details != info.details,
                    colors = ButtonDefaults.buttonColors(containerColor = Lc.accent),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).height(44.dp),
                ) { Text("Save details", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun FlagButton(
    on: androidx.compose.ui.graphics.vector.ImageVector,
    off: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Lc.raised else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) on else off, null, tint = if (selected) tint else Lc.muted, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false, color = if (selected) Lc.text else Lc.muted, modifier = Modifier.padding(start = 5.dp))
    }
}

// ---- versions and history ----------------------------------------------------------------------

@Composable
internal fun HistorySheet(history: HistoryInfo, onStep: (Int) -> Unit, onVersion: (Int) -> Unit, onSaveVersion: (String) -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
        SheetTitle("Versions")
        UnderlineTabs(listOf("Versions", "History"), page) { page = it }
        HorizontalDivider(color = Lc.divider)
        if (page == 0) {
            if (naming) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        singleLine = true,
                        placeholder = { Text("Version ${history.versions.size + 1}", color = Lc.muted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lc.accent, unfocusedBorderColor = Lc.divider, cursorColor = Lc.accent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            onSaveVersion(name)
                            name = ""
                            naming = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Lc.accent),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(start = 10.dp),
                    ) { Text("Save") }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.BookmarkAdd, null, tint = Lc.accentText, modifier = Modifier.size(22.dp))
                    Text("Save current look as a version", fontSize = 15.sp, color = Lc.accentText, modifier = Modifier.padding(start = 14.dp))
                }
            }
            if (history.versions.isEmpty()) {
                Text("Versions keep a look you can come back to while you keep editing.", fontSize = 14.sp, color = Lc.muted, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(history.versions) { i, v ->
                    Row(Modifier.fillMaxWidth().clickable { onVersion(i) }.padding(horizontal = 22.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Bookmark, null, tint = Lc.icon, modifier = Modifier.size(20.dp))
                        Text(v, fontSize = 15.sp, color = Lc.text, modifier = Modifier.padding(start = 14.dp).weight(1f))
                        Text("Restore", fontSize = 13.sp, color = Lc.accentText)
                    }
                }
            }
        } else {
            if (history.steps.isEmpty()) {
                Text("No edits yet.", fontSize = 14.sp, color = Lc.muted, modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
            }
            LazyColumn(Modifier.heightIn(max = 420.dp), reverseLayout = false) {
                val newestFirst = history.steps.indices.reversed().toList()
                items(newestFirst) { i ->
                    val current = i == history.cursor
                    val undone = history.cursor in 0 until i
                    Row(
                        Modifier.fillMaxWidth().background(if (current) Lc.raised else Color.Transparent).clickable { onStep(i) }.padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(history.steps[i].ifEmpty { "Edit" }, fontSize = 15.sp, color = if (undone) Lc.disabled else Lc.text, modifier = Modifier.weight(1f))
                        if (current) Icon(Icons.Outlined.Check, "Current step", tint = Lc.accentText, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** The colour labels with the colours they show as. */
private val LABELS = listOf(
    "red" to Color(0xFFE5484D),
    "yellow" to Color(0xFFE6C23A),
    "green" to Color(0xFF3FBF4F),
    "blue" to Color(0xFF2F6FE0),
    "purple" to Color(0xFF9B5DE5),
)

@Composable
private fun DetailField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(500)) },
        label = { Text(label) },
        singleLine = label != "Caption",
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lc.accent, unfocusedBorderColor = Lc.divider, cursorColor = Lc.accent, focusedLabelColor = Lc.accentText),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
    )
}

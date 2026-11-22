package ai.storyteller.lightcraft.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.AiModelStatus
import ai.storyteller.lightcraft.android.Engine
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.Page
import ai.storyteller.lightcraft.android.SegModelStatus
import ai.storyteller.lightcraft.android.StorageUse
import kotlinx.coroutines.delay

/**
 * Downloads & Storage: every model LightCraft for Android downloads, smallest first, with its
 * state and what can be done with it (download, cancel, delete), then what LightCraft keeps on
 * the phone (the library's edits, photo copies, the export cache, models) with Clear where it is
 * safe. Reached from the sidebar and from the AI card in Masking.
 */
@Composable
fun DownloadsScreen(vm: LightCraftViewModel, snackbar: SnackbarHostState) {
    val ai by vm.ai.collectAsStateWithLifecycle()
    val seg by vm.seg.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val models by vm.aiModels.collectAsStateWithLifecycle()
    BackHandler { vm.openPage(Page.None) }
    LaunchedEffect(Unit) {
        vm.refreshDownloads()
        // keep the page current while something downloads
        while (true) {
            delay(1500)
            if (vm.ai.value.downloading || vm.seg.value.downloading || vm.aiModels.value.any { it.downloading }) vm.refreshDownloads()
        }
    }
    Box(Modifier.fillMaxSize().background(Lc.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(Lc.chrome).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LcIconButton(DeskIcon.Back, "Back") { vm.openPage(Page.None) }
                    Text("Downloads & Storage", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Lc.text, modifier = Modifier.weight(1f).padding(start = 4.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                Heading("Models", "Downloaded on demand, smallest first. Photos never leave the phone.")
                SegModelRow(vm, seg)
                models.sortedBy { it.size }.forEach { LcModelRow(vm, it) }
                Heading("Storage", "What LightCraft keeps on this phone. Your photos stay in the gallery and aren't counted here.")
                StorageRows(vm, storage)
                Spacer(Modifier.height(24.dp))
            }
        }
        LcSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp))
    }
}

@Composable
internal fun Heading(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 8.dp)) {
        Text(title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Lc.text)
        Spacer(Modifier.height(2.dp))
        Text(body, fontFamily = Inter, fontSize = 12.sp, color = Lc.dim)
    }
}

/** One model or storage item: an icon tile, name, what it's for, its size and state, and actions. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ItemCard(
    icon: DeskIcon,
    name: String,
    detail: String,
    size: String,
    state: String,
    stateColor: androidx.compose.ui.graphics.Color = Lc.dim,
    progress: Float? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(Lc.chrome, RoundedCornerShape(6.dp))
            .border(1.dp, Lc.fieldBorder, RoundedCornerShape(6.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).background(Lc.inset, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { LcIcon(icon, Lc.label, 20.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f))
                    Text(size, style = Numbers, fontSize = 13.sp, color = Lc.label)
                }
                Spacer(Modifier.height(3.dp))
                Text(detail, fontFamily = Inter, fontSize = 12.sp, color = Lc.dim)
                Spacer(Modifier.height(6.dp))
                Text(state, fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = stateColor)
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(2.dp), color = Lc.accent, trackColor = Lc.track)
        }
        if (actions != null) androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun SegModelRow(vm: LightCraftViewModel, seg: SegModelStatus) {
    val state = when {
        seg.downloading -> "Downloading… ${(seg.progress * 100).toInt()} %"
        !seg.checked -> "Checking…"
        seg.installed -> "Installed"
        seg.error != null -> "Not installed: ${seg.error}"
        else -> "Not installed: downloads with the first Subject, Background or Remove BG"
    }
    ItemCard(
        DeskIcon.Subject,
        "Subject Segmentation",
        "Subject, Background, Pop Subject and Remove BG. Google ML Kit through Google Play services, which downloads, updates and stores it.",
        "Small",
        state,
        if (seg.installed) Lc.text else Lc.dim,
        progress = if (seg.downloading) seg.progress else null,
    ) {
        when {
            seg.downloading -> {}
            seg.installed -> LcButton("Release") { vm.releaseSegModel() }
            else -> LcButton("Download", on = true) { vm.downloadSegModel() }
        }
    }
}

/** One of LightCraft's own optional models: what it does, size, licence, download / cancel / delete. */
@Composable
private fun LcModelRow(vm: LightCraftViewModel, m: ai.storyteller.lightcraft.android.LcAiModel) {
    var confirm by remember { mutableStateOf<String?>(null) }
    val total = m.total.takeIf { it > 0 } ?: m.size
    val state = when {
        m.downloading -> "Downloading… ${mb(m.done)} of ${mb(total)}"
        m.ready -> "Installed"
        m.error != null -> "Not installed: ${m.error}"
        else -> "Not installed"
    }
    ItemCard(
        DeskIcon.Download,
        m.name,
        "${m.purpose}. ${m.licence}, not LightCraft's; runs on the phone.",
        mb(m.size),
        state,
        if (m.ready) Lc.text else Lc.dim,
        progress = if (m.downloading && total > 0) m.done.toFloat() / total else null,
    ) {
        when {
            m.downloading -> LcButton("Cancel") { vm.cancelLcModel(m.id) }
            m.ready -> LcButton("Delete") { confirm = "delete" }
            else -> LcButton("Download", on = true) { confirm = "download" }
        }
    }
    when (confirm) {
        "download" -> Confirm("Download ${m.name}?", "About ${mb(m.size)} from the model's public source, under ${m.licence}. It stays in LightCraft's private storage.", "Download", Lc.accent, onYes = { vm.downloadLcModel(m.id) }) { confirm = null }
        "delete" -> Confirm("Delete ${m.name}?", "This frees ${mb(m.size)}. Edits you already made keep working.", "Delete", Lc.reject, onYes = { vm.removeLcModel(m.id) }) { confirm = null }
    }
}

private fun mb(bytes: Long): String = if (bytes >= 1_000_000_000) gb(bytes) else "${(bytes + 500_000) / 1_000_000} MB"

@Composable
private fun StorageRows(vm: LightCraftViewModel, s: StorageUse) {
    var confirm by remember { mutableStateOf<String?>(null) }
    ItemCard(DeskIcon.Sliders, "Library and Edits", "Your edits, ratings, flags, masks and history. Kept until you clear the app's data.", gb(s.catalog), "In use", Lc.text)
    ItemCard(
        DeskIcon.Photos, "Photo Copies", "Copies of photos Android wouldn't let LightCraft read in place. They are made again when you open the photo.", gb(s.copies),
        if (s.copies > 0) "Can be cleared" else "Empty",
    ) { if (s.copies > 0) LcButton("Clear") { confirm = "copies" } }
    ItemCard(
        DeskIcon.Share, "Export Cache", "Files made for the share sheet. Photos saved to the gallery aren't here.", gb(s.exports),
        if (s.exports > 0) "Can be cleared" else "Empty",
    ) { if (s.exports > 0) LcButton("Clear", onClick = vm::clearExports) }
    ItemCard(DeskIcon.Download, "Models", "The AI models you downloaded. Manage them above.", gb(s.models), if (s.models > 0) "In use" else "Empty")
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text("Total", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f))
        Text(gb(s.catalog + s.copies + s.exports + s.models), style = Numbers, fontSize = 14.sp, color = Lc.text)
    }
    if (confirm == "copies") {
        Confirm("Clear photo copies?", "LightCraft makes a copy again the next time you open one of those photos. Your edits stay.", "Clear", Lc.reject, onYes = vm::clearCopies) { confirm = null }
    }
}

@Composable
private fun Confirm(title: String, body: String, yes: String, yesColor: androidx.compose.ui.graphics.Color, onYes: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Lc.chrome,
        title = { Text(title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Lc.text) },
        text = { Text(body, fontFamily = Inter, fontSize = 14.sp, color = Lc.label) },
        confirmButton = { TextButton(onClick = { onClose(); onYes() }) { Text(yes, color = yesColor, fontFamily = Inter) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Lc.label, fontFamily = Inter) } },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().height(38.dp).background(Lc.field, RoundedCornerShape(4.dp)).border(1.dp, Lc.fieldBorder, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, fontFamily = Inter, fontSize = 14.sp, color = Lc.dim)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(300)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Lc.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Lc.accent),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

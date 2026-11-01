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
    BackHandler { vm.openPage(Page.None) }
    LaunchedEffect(Unit) {
        vm.refreshDownloads()
        // keep the page current while something downloads
        while (true) {
            delay(1500)
            if (vm.ai.value.downloading || vm.seg.value.downloading) vm.refreshDownloads()
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
                if (Engine.aiMasksAvailable) SamRow(vm, ai)
                Heading("Storage", "What LightCraft keeps on this phone. Your photos stay in the gallery and aren't counted here.")
                StorageRows(vm, storage)
                Spacer(Modifier.height(24.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp))
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
        if (actions != null) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
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

@Composable
private fun SamRow(vm: LightCraftViewModel, ai: AiModelStatus) {
    val context = LocalContext.current
    val ramGb = remember { phoneMemoryGb(context) }
    val enough = ramGb >= MIN_AI_RAM_GB
    var confirm by remember { mutableStateOf<String?>(null) }
    val total = ai.total.takeIf { it > 0 } ?: ai.sizeBytes
    val state = when {
        ai.downloading -> "Downloading… ${gb(ai.done)} of ${gb(total)}"
        ai.installed -> "Installed" + if (ramGb < COMFORT_AI_RAM_GB) ". With $ramGb GB of memory Android may close LightCraft during a selection." else ""
        !enough -> "Needs a phone with $MIN_AI_RAM_GB GB of memory or more; this one has $ramGb GB"
        ai.done > 0 -> "Paused at ${gb(ai.done)}: download again to resume"
        ai.error != null -> "Not installed: ${ai.error}"
        else -> "Not installed"
    }
    ItemCard(
        DeskIcon.Target,
        "SAM 3",
        "Object and Describe masks. Meta's Segment Anything 3, run on the phone; ${ai.license}, not LightCraft's. Use Wi-Fi.",
        gb(ai.sizeBytes),
        state,
        if (ai.installed) Lc.text else if (!enough) Lc.caution else Lc.dim,
        progress = if (ai.downloading && total > 0) ai.done.toFloat() / total else null,
    ) {
        when {
            ai.downloading -> LcButton("Cancel", onClick = vm::cancelAiDownload)
            ai.installed -> LcButton("Delete") { confirm = "delete" }
            enough -> {
                if (ai.done > 0) {
                    LcButton("Delete Partial") { confirm = "delete" }
                    Spacer(Modifier.width(8.dp))
                }
                LcButton(if (ai.done > 0) "Resume" else "Download", on = true) { confirm = "download" }
            }
        }
    }
    when (confirm) {
        "download" -> Confirm(
            "Download SAM 3?",
            "About ${gb(ai.sizeBytes)} from the model's public mirrors, under Meta's ${ai.license}. Use Wi-Fi. It stays in LightCraft's private storage." +
                if (ramGb < COMFORT_AI_RAM_GB) " With $ramGb GB of memory, Android may close LightCraft during a selection." else "",
            "Download",
            Lc.accent,
            onYes = vm::downloadAiModel,
        ) { confirm = null }
        "delete" -> Confirm(
            "Delete SAM 3?",
            "This frees ${gb(ai.sizeBytes.coerceAtMost(maxOf(ai.done, ai.sizeBytes)))}. Masks you already made keep working; Object and Describe need the model again.",
            "Delete",
            Lc.reject,
            onYes = vm::deleteAiModel,
        ) { confirm = null }
    }
}

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
    ItemCard(DeskIcon.Download, "Models", "SAM 3, when downloaded. Manage it above.", gb(s.models), if (s.models > 0) "In use" else "Empty")
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

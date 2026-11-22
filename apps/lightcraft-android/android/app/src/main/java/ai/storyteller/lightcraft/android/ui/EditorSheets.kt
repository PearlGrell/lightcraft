package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.ExportChoice
import ai.storyteller.lightcraft.android.LightCraftViewModel
import java.util.Locale
import org.json.JSONObject

private val FORMATS = listOf("jpeg" to "JPEG", "png" to "PNG", "webp" to "WebP", "avif" to "AVIF")
private val SIZES = listOf(0 to "Full", 4096 to "4096", 2048 to "2048", 1080 to "1080")

@Composable
private fun SheetLabel(text: String) {
    Text(text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Lc.text, modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 6.dp))
}

/** Export, as the desktop's export dialog in short: format, size, quality; save to the gallery or share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: LightCraftViewModel, onDismiss: () -> Unit) {
    var format by rememberSaveable { mutableStateOf("jpeg") }
    var quality by rememberSaveable { mutableFloatStateOf(90f) }
    var edge by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Lc.chrome) {
        ExportBody(
            format, { format = it }, quality, { quality = it }, edge, { edge = it },
            onShare = { onDismiss(); vm.export(ExportChoice(format, quality.toInt(), edge), toGallery = false) },
            onSave = { onDismiss(); vm.export(ExportChoice(format, quality.toInt(), edge), toGallery = true) },
        )
    }
}

/** [ExportSheet]'s contents, without its state. */
@Composable
internal fun ExportBody(
    format: String,
    onFormat: (String) -> Unit,
    quality: Float,
    onQuality: (Float) -> Unit,
    edge: Int,
    onEdge: (Int) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        Text("Export", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Lc.text, modifier = Modifier.padding(horizontal = 18.dp))
        Text("Your original stays as it is: this makes a new file with your edits.", fontFamily = Inter, fontSize = 12.sp, color = Lc.dim, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 6.dp))

        SheetLabel("Format")
        Segmented(FORMATS, format, onFormat)
        Text(FORMAT_NOTES[format] ?: "", fontFamily = Inter, fontSize = 11.sp, color = Lc.dim, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp))

        SheetLabel("Size")
        Segmented(SIZES, edge, onEdge)
        Text("Long edge, in pixels. Full keeps the photo's own size.", fontFamily = Inter, fontSize = 11.sp, color = Lc.dim, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp))

        if (format != "png") {
            SheetLabel("Quality")
            LcSlider(
                SliderSpec("quality", "Smaller file  ·  Better quality", quality, 10f, 100f, 90f),
                SliderActions(change = { _, v -> onQuality(v) }, done = {}, reset = { onQuality(90f) }),
            )
        }

        val summary = listOfNotNull(
            FORMATS.firstOrNull { it.first == format }?.second,
            SIZES.firstOrNull { it.first == edge }?.second?.let { if (edge == 0) "full size" else "long edge $it" },
            if (format != "png") "quality ${quality.toInt()}" else "lossless",
        ).joinToString("  ·  ")
        Text(summary, fontFamily = Inter, fontSize = 12.sp, color = Lc.label, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 12.dp))

        SheetButton("Save to Gallery", primary = true, onClick = onSave)
        Spacer(Modifier.height(8.dp))
        SheetButton("Share…", primary = false, onClick = onShare)
    }
}

/** Format notes under the format choice. */
private val FORMAT_NOTES = mapOf(
    "jpeg" to "Works everywhere.",
    "png" to "Lossless, larger files.",
    "webp" to "Smaller than JPEG; most apps and browsers open it.",
    "avif" to "Smallest files; newer apps only.",
)

/** A full-width choice of equal parts, the chosen one filled. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Lc.field)
            .border(1.dp, Lc.fieldBorder, RoundedCornerShape(8.dp))
            .padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) Lc.toolActive else Color.Transparent)
                    .semantics { this.selected = on; role = Role.RadioButton }
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontFamily = Inter, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = 13.sp, color = if (on) Lc.text else Lc.label, maxLines = 1)
            }
        }
    }
}

/** A full-width sheet button, label centred: the main action filled, the other outlined. */
@Composable
private fun SheetButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) Lc.accent else Color.Transparent)
            .border(1.dp, if (primary) Lc.accent else Lc.fieldBorder, RoundedCornerShape(8.dp))
            .semantics { role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (primary) Color.White else Lc.text)
    }
}

/** Photo info: file, size, date, camera and exposure; the rating stars and the pick / reject flags. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(vm: LightCraftViewModel, onDismiss: () -> Unit) {
    val info by vm.info.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadInfo() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Lc.chrome) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            val o = info
            if (o == null) {
                Text("Reading…", fontFamily = Inter, fontSize = 14.sp, color = Lc.dim, modifier = Modifier.padding(18.dp))
                return@Column
            }
            val meta = o.optJSONObject("meta") ?: JSONObject()
            Text(o.optString("file_name", "Photo"), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Lc.text, modifier = Modifier.padding(horizontal = 18.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Stars(o.optInt("rating"), 22.dp, Lc.star, Lc.icon, onRate = vm::setRating)
                Spacer(Modifier.weight(1f))
                val flag = o.optString("flag", "none")
                LcIconButton(if (flag == "pick") DeskIcon.FlagPickFilled else DeskIcon.FlagPick, "Pick", active = flag == "pick") { vm.setFlag(if (flag == "pick") "none" else "pick") }
                Spacer(Modifier.width(4.dp))
                LcIconButton(DeskIcon.FlagReject, "Reject", active = flag == "reject", tint = if (flag == "reject") Lc.reject else Lc.icon) { vm.setFlag(if (flag == "reject") "none" else "reject") }
            }
            SectionDivider()
            Spacer(Modifier.height(6.dp))
            Field("Dimensions", "${o.optInt("width")} × ${o.optInt("height")}  ·  ${"%.1f".format(Locale.ROOT, o.optInt("width") * o.optInt("height") / 1e6)} MP")
            Field("Format", listOfNotNull(o.optString("format").takeIf { it.isNotEmpty() }, size(o.optLong("file_size"))).joinToString("  ·  "))
            Field("Capture Time", o.optString("captured").replace('T', ' ').takeIf { it.isNotEmpty() && it != "null" })
            Field("Camera", meta.optString("camera").takeIf { it.isNotEmpty() })
            Field("Lens", meta.optString("lens").takeIf { it.isNotEmpty() })
            val exposure = listOfNotNull(
                meta.optString("shutter").takeIf { it.isNotEmpty() }?.let { "$it s" },
                meta.optDouble("aperture").takeIf { !it.isNaN() && it > 0 }?.let { "f/" + "%.1f".format(Locale.ROOT, it).removeSuffix(".0") },
                meta.optInt("iso").takeIf { it > 0 }?.let { "ISO $it" },
                meta.optDouble("focal_mm").takeIf { !it.isNaN() && it > 0 }?.let { "%.0f mm".format(Locale.ROOT, it) },
            )
            Field("Exposure", exposure.joinToString("  ·  ").takeIf { it.isNotEmpty() })
            Field("Location", listOf("location", "city", "country").mapNotNull { meta.optString(it).takeIf { s -> s.isNotEmpty() } }.joinToString(", ").takeIf { it.isNotEmpty() })
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp)) {
        Text(label, fontFamily = Inter, fontSize = 13.sp, color = Lc.dim, modifier = Modifier.width(110.dp))
        Text(value, style = Numbers, fontSize = 13.sp, color = Lc.text)
    }
}

private fun size(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes >= 1_000_000 -> "%.1f MB".format(Locale.ROOT, bytes / 1e6)
    else -> "%.0f KB".format(Locale.ROOT, bytes / 1e3)
}

/** History: every step of the photo's edit, newest first; tap one to go back to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(vm: LightCraftViewModel, onDismiss: () -> Unit) {
    val h by vm.history.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadHistory() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Lc.chrome) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("History", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Lc.text, modifier = Modifier.padding(horizontal = 18.dp))
            if (h.steps.isEmpty()) {
                Text("No edits yet.", fontFamily = Inter, fontSize = 13.sp, color = Lc.dim, modifier = Modifier.padding(18.dp))
                return@Column
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp)) {
                items(h.steps.size) { k ->
                    val i = h.steps.lastIndex - k
                    SheetRow(h.steps[i].ifBlank { "Edit" }, on = i == h.cursor) { vm.restoreHistory(i) }
                }
            }
        }
    }
}

/** Versions: named snapshots of the photo's edit; save the current one, restore or delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsSheet(vm: LightCraftViewModel, onDismiss: () -> Unit) {
    val h by vm.history.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadHistory() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Lc.chrome) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Versions", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Lc.text, modifier = Modifier.weight(1f))
                LcButton("Save Current", on = true) { vm.createVersion("Version ${h.versions.size + 1}") }
            }
            if (h.versions.isEmpty()) {
                Text("Save the current edit as a version to come back to it later.", fontFamily = Inter, fontSize = 13.sp, color = Lc.dim, modifier = Modifier.padding(18.dp))
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            h.versions.forEachIndexed { i, name ->
                Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontFamily = Inter, fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f))
                    LcButton("Restore") { vm.restoreVersion(i) }
                    Spacer(Modifier.width(4.dp))
                    LcIconButton(DeskIcon.Trash, "Delete $name", size = 32.dp, iconSize = 16.dp) { vm.deleteVersion(i) }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .padding(horizontal = 10.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(if (on) Lc.toolActive else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontFamily = Inter, fontSize = 14.sp, color = if (on) Lc.text else Lc.label)
    }
}

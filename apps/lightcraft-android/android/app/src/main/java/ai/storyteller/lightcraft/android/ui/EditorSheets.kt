package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.ExportChoice
import ai.storyteller.lightcraft.android.LightCraftViewModel
import java.util.Locale
import org.json.JSONObject

private val FORMATS = listOf("jpeg" to "JPEG", "png" to "PNG", "webp" to "WebP", "avif" to "AVIF")
private val SIZES = listOf(0 to "Full Size", 4096 to "4096 px", 2048 to "2048 px", 1080 to "1080 px")

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
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Export", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Lc.text, modifier = Modifier.padding(horizontal = 18.dp))
            Text("The original stays as it is; this writes a new file with your edits.", fontFamily = Inter, fontSize = 12.sp, color = Lc.dim, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            SheetLabel("File Format")
            Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FORMATS.forEach { (id, label) -> LcButton(label, on = format == id) { format = id } }
            }
            SheetLabel("Image Size")
            Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SIZES.forEach { (px, label) -> LcButton(label, on = edge == px) { edge = px } }
            }
            if (format != "png") {
                Spacer(Modifier.height(6.dp))
                LcSlider(
                    SliderSpec("quality", "Quality", quality, 10f, 100f, 90f),
                    SliderActions(change = { _, v -> quality = v }, done = {}, reset = { quality = 90f }),
                )
            }
            Spacer(Modifier.height(14.dp))
            val choice = ExportChoice(format, quality.toInt(), edge)
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LcButton("Share", Modifier.weight(1f)) { onDismiss(); vm.export(choice, toGallery = false) }
                LcButton("Save to Gallery", Modifier.weight(1f), on = true) { onDismiss(); vm.export(choice, toGallery = true) }
            }
        }
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

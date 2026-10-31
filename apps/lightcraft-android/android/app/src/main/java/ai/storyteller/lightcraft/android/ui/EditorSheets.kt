package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.ExportChoice
import ai.storyteller.lightcraft.android.LightCraftViewModel
import java.util.Locale
import org.json.JSONObject

private val FORMATS = listOf("jpeg" to "JPEG", "png" to "PNG", "webp" to "WebP", "avif" to "AVIF")
private val SIZES = listOf(0 to "Full size", 4096 to "4096 px", 2048 to "2048 px", 1080 to "1080 px")

/** Export: format, quality and size, then save to the phone's gallery or share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: LightCraftViewModel, onDismiss: () -> Unit) {
    var format by rememberSaveable { mutableStateOf("jpeg") }
    var quality by rememberSaveable { mutableStateOf(90f) }
    var edge by rememberSaveable { mutableStateOf(0) }
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Lc.bar) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Export", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Text("Your original stays as it is; this saves a new file with your edits.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Spacer(Modifier.height(12.dp))
            Label("Format")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FORMATS.forEach { (id, label) -> Chip(label, format == id) { format = id } }
            }
            Spacer(Modifier.height(14.dp))
            Label("Size")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SIZES.forEach { (px, label) -> Chip(label, edge == px) { edge = px } }
            }
            if (format != "png") {
                Spacer(Modifier.height(10.dp))
                DialGrid(
                    listOf(DialSpec("quality", "Quality", quality, 10f, 100f, 90f)),
                    focus, { focus = if (focus == it) null else it },
                    DialActions(change = { _, v -> quality = v }, done = {}, reset = { quality = 90f }),
                    maxHeight = 100.dp,
                )
            }
            Spacer(Modifier.height(16.dp))
            val choice = ExportChoice(format, quality.toInt(), edge)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onDismiss(); vm.export(choice, toGallery = false) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("Share", color = Lc.text) }
                Button(
                    onClick = { onDismiss(); vm.export(choice, toGallery = true) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Lc.text, contentColor = Lc.onAccent),
                    modifier = Modifier.weight(1f),
                ) { Text("Save to gallery") }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = Lc.muted, modifier = Modifier.padding(start = 20.dp, bottom = 6.dp))
}

/** Photo info: file, size, date, camera and exposure; rating and favourite. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(vm: LightCraftViewModel, onDismiss: () -> Unit) {
    val info by vm.info.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadInfo() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Lc.bar) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            val o = info
            if (o == null) {
                Text("Reading…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val meta = o.optJSONObject("meta") ?: JSONObject()
            Text(o.optString("file_name", "Photo"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val rating = o.optInt("rating")
                (1..5).forEach { i ->
                    Icon(
                        if (i <= rating) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = "$i star${if (i > 1) "s" else ""}",
                        tint = if (i <= rating) Lc.accent else Lc.muted,
                        modifier = Modifier.size(30.dp).clickable { vm.setRating(if (rating == i) 0 else i) }.padding(3.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                val fav = o.optString("flag") == "pick"
                IconButton(onClick = { vm.setFavourite(!fav) }) {
                    Icon(if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (fav) "Remove from Favourites" else "Add to Favourites", tint = if (fav) Lc.accent else Lc.label)
                }
            }
            HorizontalDivider(color = Lc.divider, modifier = Modifier.padding(vertical = 10.dp))
            Field("Dimensions", "${o.optInt("width")} × ${o.optInt("height")}  ·  ${"%.1f".format(Locale.ROOT, o.optInt("width") * o.optInt("height") / 1e6)} MP")
            Field("Format", listOfNotNull(o.optString("format").takeIf { it.isNotEmpty() }, size(o.optLong("file_size"))).joinToString("  ·  "))
            Field("Taken", o.optString("captured").replace('T', ' ').takeIf { it.isNotEmpty() && it != "null" })
            Field("Camera", meta.optString("camera").takeIf { it.isNotEmpty() })
            Field("Lens", meta.optString("lens").takeIf { it.isNotEmpty() })
            val exposure = listOfNotNull(
                meta.optString("shutter").takeIf { it.isNotEmpty() }?.let { "$it s" },
                meta.optDouble("aperture").takeIf { !it.isNaN() && it > 0 }?.let { "f/" + "%.1f".format(Locale.ROOT, it).removeSuffix(".0") },
                meta.optInt("iso").takeIf { it > 0 }?.let { "ISO $it" },
                meta.optDouble("focal_mm").takeIf { !it.isNaN() && it > 0 }?.let { "%.0f mm".format(Locale.ROOT, it) },
            )
            Field("Exposure", exposure.joinToString("  ·  ").takeIf { it.isNotEmpty() })
            Field("Place", listOf("location", "city", "country").mapNotNull { meta.optString(it).takeIf { s -> s.isNotEmpty() } }.joinToString(", ").takeIf { it.isNotEmpty() })
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 13.sp, color = Lc.muted, modifier = Modifier.width(96.dp))
        Text(value, fontSize = 13.sp, color = Lc.text, style = Numbers)
    }
}

private fun size(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes >= 1_000_000 -> "%.1f MB".format(Locale.ROOT, bytes / 1e6)
    else -> "%.0f KB".format(Locale.ROOT, bytes / 1e3)
}

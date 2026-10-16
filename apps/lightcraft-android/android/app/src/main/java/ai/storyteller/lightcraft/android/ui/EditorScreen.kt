package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.Control
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.Tool
import ai.storyteller.lightcraft.android.Tools
import java.util.Locale
import kotlin.math.abs

/**
 * The loupe: full-bleed photo (pinch to zoom, press and hold for the original), undo/redo always in
 * reach, and a Lightroom-mobile tool rail at the bottom whose tools open a slider panel above it.
 */
@Composable
fun EditorScreen(vm: LightCraftViewModel, photo: Long, snackbar: SnackbarHostState) {
    val preview by vm.preview.collectAsStateWithLifecycle()
    val before by vm.before.collectAsStateWithLifecycle()
    val tool by vm.tool.collectAsStateWithLifecycle()
    val controls by vm.controls.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var showBefore by remember { mutableStateOf(false) }
    var zoom by remember(photo) { mutableFloatStateOf(1f) }
    var panX by remember(photo) { mutableFloatStateOf(0f) }
    var panY by remember(photo) { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to library") }
                Box(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                IconButton(onClick = vm::undo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = vm::redo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                IconButton(onClick = vm::share) { Icon(Icons.Filled.Share, "Export and share") }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { vm.setViewport(it.width, it.height) }
                    .pointerInput(photo) {
                        detectTransformGestures { _, pan, z, _ ->
                            zoom = (zoom * z).coerceIn(1f, 8f)
                            panX = if (zoom == 1f) 0f else panX + pan.x
                            panY = if (zoom == 1f) 0f else panY + pan.y
                        }
                    }
                    .pointerInput(photo) {
                        detectTapGestures(
                            onDoubleTap = { zoom = if (zoom > 1f) 1f else 2.5f; panX = 0f; panY = 0f },
                            onPress = {
                                vm.loadBefore()
                                val hold = kotlinx.coroutines.withTimeoutOrNull(350) { tryAwaitRelease() }
                                if (hold == null) {
                                    showBefore = true
                                    tryAwaitRelease()
                                    showBefore = false
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val shown = if (showBefore) before ?: preview else preview
                shown?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom; scaleY = zoom; translationX = panX; translationY = panY
                        },
                    )
                } ?: CircularProgressIndicator()
                if (showBefore) {
                    Text(
                        "Original",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                            .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                if (tool != null && controls.isNotEmpty()) {
                    SliderPanel(controls, onChange = vm::setControl, onDone = vm::finishInteraction, onReset = vm::resetControl)
                }
                ToolRail(selected = tool, onSelect = vm::selectTool)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))
    }
}

@Composable
private fun SliderPanel(controls: List<Control>, onChange: (String, Float) -> Unit, onDone: () -> Unit, onReset: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(controls, key = { it.id }) { c -> ControlSlider(c, onChange, onDone, onReset) }
    }
}

@Composable
private fun ControlSlider(c: Control, onChange: (String, Float) -> Unit, onDone: () -> Unit, onReset: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().pointerInput(c.id) { detectTapGestures(onDoubleTap = { onReset(c.id) }) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(c.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(c), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = c.value.coerceIn(c.min, c.max),
            valueRange = c.min..c.max,
            onValueChange = { v ->
                // snap to and tick at the default, so "back to zero" is easy to feel
                val snap = (c.max - c.min) * 0.01f
                val next = if (abs(v - c.default) < snap) c.default else v
                if (next == c.default && c.value != c.default) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onChange(c.id, next)
            },
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.heightIn(max = 36.dp),
        )
    }
}

private fun format(c: Control): String {
    val text = String.format(Locale.ROOT, "%.${c.decimals}f", c.value)
    return if (c.min < 0 && c.value > 0) "+$text" else text
}

private fun iconOf(tool: Tool): ImageVector = when (tool.id) {
    Tools.AUTO -> Icons.Filled.AutoAwesome
    "light" -> Icons.Filled.WbSunny
    "color" -> Icons.Filled.Palette
    "effects" -> Icons.Filled.BlurOn
    "detail" -> Icons.Filled.Details
    "optics" -> Icons.Filled.CameraAlt
    "geometry" -> Icons.Filled.Straighten
    "crop" -> Icons.Filled.Crop
    "masking" -> Icons.Filled.Brush
    "remove" -> Icons.Filled.Healing
    "presets" -> Icons.Filled.Style
    else -> Icons.Filled.AutoFixHigh
}

@Composable
private fun ToolRail(selected: Tool?, onSelect: (Tool) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(Tools.rail, key = { it.id }) { t ->
            val active = selected?.id == t.id
            val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Column(
                Modifier.width(64.dp).clickable { onSelect(t) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(iconOf(t), contentDescription = null, tint = tint)
                Text(t.label, color = tint, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.Control
import ai.storyteller.lightcraft.android.Group
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.Preset
import ai.storyteller.lightcraft.android.Tool
import ai.storyteller.lightcraft.android.ToolKind
import ai.storyteller.lightcraft.android.Tools

/**
 * The loupe in the phone-editor layout: a thin top bar (back, undo, redo, compare, share), the photo
 * edge to edge on black (pinch or double-tap to zoom, press and hold for the original), and at the
 * bottom the open tool's panel above a scrolling rail of tools.
 */
@Composable
fun EditorScreen(vm: LightCraftViewModel, photo: Long, snackbar: SnackbarHostState) {
    val preview by vm.preview.collectAsStateWithLifecycle()
    val before by vm.before.collectAsStateWithLifecycle()
    val tool by vm.tool.collectAsStateWithLifecycle()
    val controls by vm.controls.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var holding by remember { mutableStateOf(false) }
    var compare by remember(photo) { mutableStateOf(false) }
    var zoom by remember(photo) { mutableFloatStateOf(1f) }
    var panX by remember(photo) { mutableFloatStateOf(0f) }
    var panY by remember(photo) { mutableFloatStateOf(0f) }
    val showBefore = holding || compare

    Box(Modifier.fillMaxSize().background(Lc.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Lc.chrome).statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::close) { Icon(Icons.Filled.ArrowBackIosNew, "Back to library", tint = Lc.text, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp, color = Lc.accent)
                IconButton(onClick = vm::undo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo", tint = Lc.text) }
                IconButton(onClick = vm::redo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo", tint = Lc.text) }
                IconButton(onClick = {
                    compare = !compare
                    if (compare) vm.loadBefore()
                }) { Icon(Icons.Outlined.Compare, "Show original", tint = if (compare) Lc.accent else Lc.text) }
                IconButton(onClick = vm::share) { Icon(Icons.Outlined.Share, "Export and share", tint = Lc.text) }
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
                            onDoubleTap = {
                                zoom = if (zoom > 1f) 1f else 2.5f
                                panX = 0f
                                panY = 0f
                            },
                            onPress = {
                                vm.loadBefore()
                                val released = kotlinx.coroutines.withTimeoutOrNull(300) { tryAwaitRelease() }
                                if (released == null) {
                                    holding = true
                                    tryAwaitRelease()
                                    holding = false
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val shown = if (showBefore) before ?: preview else preview
                if (shown != null) {
                    Image(
                        shown.asImageBitmap(),
                        contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 0.dp).graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = panX
                            translationY = panY
                        },
                    )
                } else {
                    CircularProgressIndicator(color = Lc.accent)
                }
                if (showBefore) {
                    Text(
                        "Original",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(10.dp)
                            .background(Color(0xB3000000), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().background(Lc.chrome).navigationBarsPadding()) {
                val t = tool
                if (t != null) {
                    Box(Modifier.fillMaxWidth().background(Lc.panel)) {
                        when (t.kind) {
                            ToolKind.Presets -> PresetPanel(presets, vm::applyPreset)
                            ToolKind.Crop -> CropPanel(vm, controls.filter { c -> t.groups.any { it.matches(c.id) } })
                            else -> SliderPanel(t, controls, vm)
                        }
                    }
                }
                ToolRail(selected = t, onSelect = vm::selectTool)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
    }
}

@Composable
private fun SliderPanel(tool: Tool, all: List<Control>, vm: LightCraftViewModel) {
    // groups the engine has no sliders for (none yet for this photo) are left out
    val groups = tool.groups.filter { g -> all.any { g.matches(it.id) } }
    var selected by remember(tool.id) { mutableStateOf(0) }
    val group = groups.getOrNull(selected) ?: groups.firstOrNull() ?: return
    val controls = all.filter { group.matches(it.id) }
    Column(Modifier.fillMaxWidth()) {
        PanelHeader(groups.map { it.title }, groups.indexOf(group), onSelect = { selected = it }, onReset = { vm.resetGroup(group) })
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
            items(controls, key = { it.id }) { c -> LrSlider(c, vm::setControl, vm::finishInteraction, vm::resetControl) }
        }
    }
}

/** Sub-panel pills ("Light · Curve") on the left, Reset on the right. */
@Composable
private fun PanelHeader(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, onReset: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(titles.size) { i ->
                val on = i == selected
                Text(
                    titles[i],
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Lc.text else Lc.label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) Lc.raised else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        if (onReset != null) {
            Text(
                "Reset",
                fontSize = 13.sp,
                color = Lc.accent,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onReset).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun PresetPanel(presets: List<Preset>, onApply: (String) -> Unit) {
    val groups = presets.map { it.group.ifEmpty { "Presets" } }.distinct()
    var selected by remember { mutableStateOf(0) }
    val group = groups.getOrNull(selected) ?: groups.firstOrNull()
    Column(Modifier.fillMaxWidth()) {
        PanelHeader(groups, groups.indexOf(group).coerceAtLeast(0), onSelect = { selected = it }, onReset = null)
        if (presets.isEmpty()) {
            Text("No presets", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(presets.filter { it.group.ifEmpty { "Presets" } == group }, key = { it.id }) { p ->
                Text(
                    p.name,
                    fontSize = 13.sp,
                    color = Lc.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Lc.raised)
                        .clickable { onApply(p.id) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun CropPanel(vm: LightCraftViewModel, straighten: List<Control>) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        PanelHeader(listOf("Crop & Rotate"), 0, onSelect = {}, onReset = { vm.cropCommand("crop.reset") })
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Tools.aspects) { a ->
                Text(
                    a.label,
                    fontSize = 13.sp,
                    color = Lc.text,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Lc.raised)
                        .clickable { vm.cropAspect(a.ratio) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            CropButton(Icons.AutoMirrored.Outlined.RotateLeft, "Rotate left") { vm.cropCommand("photo.rotateLeft") }
            CropButton(Icons.AutoMirrored.Outlined.RotateRight, "Rotate right") { vm.cropCommand("photo.rotateRight") }
            CropButton(Icons.Outlined.Flip, "Flip horizontal") { vm.cropCommand("photo.flipHorizontal") }
            CropButton(Icons.Outlined.Flip, "Flip vertical", rotated = true) { vm.cropCommand("photo.flipVertical") }
        }
        straighten.forEach { c ->
            LrSlider(c, vm::setControl, vm::finishInteraction, vm::resetControl, Modifier.padding(horizontal = 20.dp))
        }
    }
}

@Composable
private fun CropButton(icon: ImageVector, label: String, rotated: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, label, tint = Lc.text, modifier = Modifier.graphicsLayer { if (rotated) rotationZ = 90f })
    }
}

private fun iconOf(tool: Tool): ImageVector = when (tool.id) {
    "presets" -> Icons.Outlined.AutoAwesomeMosaic
    "auto" -> Icons.Outlined.AutoAwesome
    "light" -> Icons.Outlined.WbSunny
    "color" -> Icons.Outlined.Palette
    "effects" -> Icons.Outlined.BlurOn
    "detail" -> Icons.Outlined.Grain
    "optics" -> Icons.Outlined.CameraAlt
    "geometry" -> Icons.Outlined.Straighten
    "crop" -> Icons.Outlined.Crop
    else -> Icons.Outlined.Tune
}

@Composable
private fun ToolRail(selected: Tool?, onSelect: (Tool) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().height(68.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        items(Tools.rail, key = { it.id }) { t ->
            val active = selected?.id == t.id
            val tint = if (active) Lc.accent else Lc.label
            Column(
                Modifier.width(66.dp).clip(RoundedCornerShape(10.dp)).clickable { onSelect(t) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(iconOf(t), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text(t.label, color = tint, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center)
                Box(Modifier.padding(top = 3.dp).size(4.dp).clip(CircleShape).background(if (active) Lc.accent else Color.Transparent))
            }
        }
    }
}


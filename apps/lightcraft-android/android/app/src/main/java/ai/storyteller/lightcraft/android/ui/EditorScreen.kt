package ai.storyteller.lightcraft.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.Control
import ai.storyteller.lightcraft.android.EraseSettings
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.MainTab
import ai.storyteller.lightcraft.android.Tool
import ai.storyteller.lightcraft.android.ToolKind
import ai.storyteller.lightcraft.android.Tools

/**
 * The editor: the photo edge to edge on black with a few icons over its top corner (back; undo,
 * redo, compare, share), and at the bottom the open tool's panel, a row of labelled tools, and the
 * bar of the six main tabs (Quick actions, Presets, Crop, Edit, Masking, Remove).
 */
@Composable
fun EditorScreen(vm: LightCraftViewModel, photo: Long, snackbar: SnackbarHostState) {
    val preview by vm.preview.collectAsStateWithLifecycle()
    val before by vm.before.collectAsStateWithLifecycle()
    val cutout by vm.cutout.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val tool by vm.tool.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var compare by remember(photo) { mutableStateOf(false) }
    var split by remember(photo) { mutableFloatStateOf(0.5f) }
    var holding by remember { mutableStateOf(false) }
    var zoom by remember(photo) { mutableFloatStateOf(1f) }
    var pan by remember(photo) { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val removing = tab == MainTab.Remove

    Column(Modifier.fillMaxSize().background(Lc.canvas)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged {
                    area = it
                    vm.setViewport(it.width, it.height)
                },
        ) {
            val shown: Bitmap? = when {
                removing -> cutout ?: preview
                holding -> before ?: preview
                else -> preview
            }
            val fit = shown?.let { fitRect(it.width, it.height, area) }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(photo, removing) {
                        if (!removing) {
                            detectTransformGestures { _, p, z, _ ->
                                zoom = (zoom * z).coerceIn(1f, 8f)
                                pan = if (zoom == 1f) Offset.Zero else pan + p
                            }
                        }
                    }
                    .pointerInput(photo, removing, fit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (!removing) {
                                    zoom = if (zoom > 1f) 1f else 2.5f
                                    pan = Offset.Zero
                                }
                            },
                            onTap = { o ->
                                val r = fit ?: return@detectTapGestures
                                if (removing && tool?.id == "eraser" && r.contains(o)) {
                                    vm.eraseAt((o.x - r.left) / r.width, (o.y - r.top) / r.height)
                                }
                            },
                            onPress = {
                                if (removing) return@detectTapGestures
                                vm.loadBefore()
                                val released = kotlinx.coroutines.withTimeoutOrNull(320) { tryAwaitRelease() }
                                if (released == null) {
                                    holding = true
                                    tryAwaitRelease()
                                    holding = false
                                }
                            },
                        )
                    },
            ) {
                if (removing && fit != null) Checkerboard(fit)
                val layer = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                }
                val b = before
                if (compare && !removing && b != null && preview != null && fit != null) {
                    val cut = fit.left + fit.width * split
                    Image(b.asImageBitmap(), "Before", contentScale = ContentScale.Fit, modifier = layer.drawWithContent { clipRect(right = cut) { this@drawWithContent.drawContent() } })
                    Image(preview?.asImageBitmap() ?: b.asImageBitmap(), "After", contentScale = ContentScale.Fit, modifier = layer.drawWithContent { clipRect(left = cut) { this@drawWithContent.drawContent() } })
                    SplitHandle(fit, split, onMove = { split = it })
                } else if (shown != null) {
                    Image(shown.asImageBitmap(), "Photo", contentScale = ContentScale.Fit, modifier = layer)
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Lc.accent)
                }
                if (holding && !removing) Pill("BEFORE", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 56.dp))
            }
            // icons over the photo
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library", vm::close)
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                OverlayIcon(Icons.AutoMirrored.Outlined.Undo, "Undo", vm::undo)
                OverlayIcon(Icons.AutoMirrored.Outlined.Redo, "Redo", vm::redo)
                OverlayIcon(LcIcons.Compare, "Before and after", tint = if (compare) Lc.accent else Color.White) {
                    compare = !compare
                    if (compare) vm.loadBefore()
                }
                OverlayIcon(Icons.Outlined.Share, "Export and share", vm::share)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        }
        BottomChrome(vm, tab, tool)
    }
}

/** Where a [w]×[h] bitmap lands when fitted into [area]. */
internal fun fitRect(w: Int, h: Int, area: IntSize): Rect? {
    if (w <= 0 || h <= 0 || area.width <= 0 || area.height <= 0) return null
    val s = minOf(area.width / w.toFloat(), area.height / h.toFloat())
    val dw = w * s
    val dh = h * s
    val left = (area.width - dw) / 2f
    val top = (area.height - dh) / 2f
    return Rect(left, top, left + dw, top + dh)
}

@Composable
internal fun Checkerboard(r: Rect) {
    Canvas(Modifier.fillMaxSize()) {
        val cell = 12.dp.toPx()
        clipRect(r.left, r.top, r.right, r.bottom) {
            var y = r.top
            var row = 0
            while (y < r.bottom) {
                var x = r.left
                var col = row % 2
                while (x < r.right) {
                    drawRect(if (col % 2 == 0) Color(0xFF8C8C8C) else Color(0xFFBDBDBD), Offset(x, y), Size(cell, cell))
                    x += cell
                    col++
                }
                y += cell
                row++
            }
        }
    }
}

/** The before/after divider: a white line with a round handle, BEFORE and AFTER labels. */
@Composable
internal fun SplitHandle(fit: Rect, split: Float, onMove: (Float) -> Unit) {
    val density = LocalDensity.current
    val x = fit.left + fit.width * split
    Box(
        Modifier.fillMaxSize().pointerInput(fit) {
            detectDragGestures { c, _ ->
                c.consume()
                onMove(((c.position.x - fit.left) / fit.width).coerceIn(0.02f, 0.98f))
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(Color.White, Offset(x, fit.top), Offset(x, fit.bottom), 1.5.dp.toPx())
            val c = Offset(x, fit.center.y)
            drawCircle(Color.White, 13.dp.toPx(), c)
            drawCircle(Color(0x33000000), 13.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        val top = with(density) { (fit.top).toDp() }
        Pill("BEFORE", Modifier.padding(start = with(density) { fit.left.toDp() } + 12.dp, top = maxOf(top, 0.dp) + 60.dp))
        Pill(
            "AFTER",
            Modifier.align(Alignment.TopEnd).padding(end = with(density) { (fit.left).toDp() } + 12.dp, top = maxOf(top, 0.dp) + 60.dp),
        )
    }
}

@Composable
internal fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.background(Lc.pill, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun OverlayIcon(icon: ImageVector, label: String, onClick: () -> Unit) = OverlayIcon(icon, label, Color.White, onClick)

@Composable
internal fun OverlayIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(Modifier.size(36.dp).background(Color(0x40000000), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

// ---- bottom ------------------------------------------------------------------------------------

@Composable
private fun BottomChrome(vm: LightCraftViewModel, tab: MainTab, tool: Tool?) {
    Column(Modifier.fillMaxWidth().background(Lc.chrome)) {
        Panel(vm, tab, tool)
        val row = when (tab) {
            MainTab.Quick -> Tools.quick
            MainTab.Edit -> Tools.edit
            MainTab.Masking -> Tools.masking
            MainTab.Remove -> Tools.remove
            else -> emptyList()
        }
        if (row.isNotEmpty()) ToolRow(row, tool, divideAfterFirst = tab == MainTab.Edit, onSelect = vm::selectTool)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2E2E2E)))
        MainBar(tab, vm::selectTab)
    }
}

@Composable
private fun Panel(vm: LightCraftViewModel, tab: MainTab, tool: Tool?) {
    val controls by vm.controls.collectAsStateWithLifecycle()
    when (tab) {
        MainTab.Presets -> PresetPanel(vm)
        MainTab.Crop -> CropPanel(vm, controls.filter { it.id == "crop.angle" })
        MainTab.Masking -> MaskPanel(vm)
        MainTab.Remove -> if (tool?.id == "eraser") ErasePanel(vm)
        MainTab.Edit -> when (tool?.kind) {
            ToolKind.Curve -> {
                val curves by vm.curves.collectAsStateWithLifecycle()
                CurveEditor(curves, vm::setCurve, vm::finishInteraction, vm::resetCurve)
            }
            ToolKind.Sliders -> SliderPanel(vm, tool, controls)
            else -> {}
        }
        MainTab.Quick -> {}
    }
}

@Composable
private fun SliderPanel(vm: LightCraftViewModel, tool: Tool, all: List<Control>) {
    val groups = tool.groups.filter { g -> all.any { g.matches(it.id) } }
    var selected by remember(tool.id) { mutableIntStateOf(0) }
    val group = groups.getOrNull(selected) ?: groups.firstOrNull() ?: return
    val controls = all.filter { group.matches(it.id) }
    Column(Modifier.fillMaxWidth()) {
        if (groups.size > 1) Pills(groups.map { it.title }, groups.indexOf(group), { selected = it }, onReset = { vm.resetGroup(group) })
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 4.dp)) {
            items(controls, key = { it.id }) { c ->
                LrSlider(
                    c.label, c.value, c.min, c.max, c.default, c.decimals, c.unit,
                    onChange = { vm.setControl(c.id, it) },
                    onDone = vm::finishInteraction,
                    onReset = { vm.resetControl(c.id) },
                )
            }
        }
    }
}

@Composable
internal fun Pills(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, onReset: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(titles.size) { i ->
                val on = i == selected
                Text(
                    titles[i],
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Lc.text else Lc.muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) Lc.raised else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (onReset != null) {
            Text("Reset", fontSize = 14.sp, color = Lc.label, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onReset).padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun PresetPanel(vm: LightCraftViewModel) {
    val presets by vm.presets.collectAsStateWithLifecycle()
    val thumbs by vm.presetThumbs.collectAsStateWithLifecycle()
    val groups = presets.map { it.group.ifEmpty { "Presets" } }.distinct()
    var selected by remember { mutableIntStateOf(0) }
    val group = groups.getOrNull(selected) ?: groups.firstOrNull()
    val shown = presets.filter { it.group.ifEmpty { "Presets" } == group }
    var applied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shown.map { it.id }) { vm.loadPresetThumbs(shown.map { it.id }) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Pills(groups.map { "Style: $it" }.ifEmpty { listOf("Presets") }, groups.indexOf(group).coerceAtLeast(0), { selected = it }, onReset = null)
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { p ->
                Column(Modifier.width(84.dp).clickable { applied = p.id; vm.applyPreset(p.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Lc.raised)
                            .border(if (applied == p.id) 2.dp else 0.dp, if (applied == p.id) Color.White else Color.Transparent, RoundedCornerShape(6.dp)),
                    ) {
                        thumbs[p.id]?.let { Image(it.asImageBitmap(), p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Text(p.name, fontSize = 12.sp, color = Lc.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun CropPanel(vm: LightCraftViewModel, straighten: List<Control>) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Tools.aspects) { a ->
                Text(
                    a.label,
                    fontSize = 14.sp,
                    color = Lc.text,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Lc.raised).clickable { vm.cropAspect(a.ratio) }.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            item {
                Text("Reset", fontSize = 14.sp, color = Lc.label, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { vm.cropCommand("crop.reset") }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { vm.cropCommand("photo.rotateLeft") }) { Icon(Icons.AutoMirrored.Outlined.RotateLeft, "Rotate left", tint = Lc.text) }
            IconButton(onClick = { vm.cropCommand("photo.rotateRight") }) { Icon(Icons.AutoMirrored.Outlined.RotateRight, "Rotate right", tint = Lc.text) }
            IconButton(onClick = { vm.cropCommand("photo.flipHorizontal") }) { Icon(Icons.Outlined.Flip, "Flip horizontal", tint = Lc.text) }
            IconButton(onClick = { vm.cropCommand("photo.flipVertical") }) { Icon(Icons.Outlined.Flip, "Flip vertical", tint = Lc.text, modifier = Modifier.graphicsLayer { rotationZ = 90f }) }
        }
        straighten.forEach { c ->
            LrSlider(c.label, c.value, c.min, c.max, c.default, c.decimals, "°", { vm.setControl(c.id, it) }, vm::finishInteraction, { vm.resetControl(c.id) }, Modifier.padding(horizontal = 22.dp))
        }
    }
}

@Composable
private fun MaskPanel(vm: LightCraftViewModel) {
    val masks by vm.masks.collectAsStateWithLifecycle()
    val active by vm.activeMask.collectAsStateWithLifecycle()
    val mask = masks.firstOrNull { it.id == active }
    Column(Modifier.fillMaxWidth()) {
        if (masks.isEmpty()) {
            Text(
                "Pick Subject, Background or Sky to select it with on-device AI, or add a Linear or Radial gradient.",
                fontSize = 13.sp,
                color = Lc.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            )
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(46.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(masks, key = { it.id }) { m ->
                    val on = m.id == active
                    Text(
                        m.name,
                        fontSize = 14.sp,
                        color = if (on) Color.White else Lc.label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) Lc.accent else Lc.raised)
                            .clickable { vm.selectMask(m.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            IconButton(onClick = { vm.maskCommand("mask.invert") }) { Icon(Icons.Outlined.InvertColors, "Invert mask", tint = Lc.label) }
            IconButton(onClick = { vm.maskCommand("mask.delete") }) { Icon(Icons.Outlined.Delete, "Delete mask", tint = Lc.label) }
        }
        if (mask != null) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp)) {
                items(Tools.local, key = { it.key }) { s ->
                    LrSlider(
                        s.label, mask.adjust[s.key] ?: 0f, s.min, s.max, 0f, s.decimals, if (s.key == "exposure") "EV" else "",
                        onChange = { vm.setMaskValue(s.key, it) },
                        onDone = vm::finishInteraction,
                        onReset = { vm.setMaskValue(s.key, 0f); vm.finishInteraction() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ErasePanel(vm: LightCraftViewModel) {
    val o by vm.erase.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
        Text("Tap the photo to erase similar colours", fontSize = 13.sp, color = Lc.label, modifier = Modifier.padding(vertical = 4.dp))
        LrSlider("Tolerance", o.tolerance, 0f, 100f, 32f, 0, "", { vm.setErase(o.copy(tolerance = it)) }, {}, { vm.setErase(o.copy(tolerance = 32f)) })
        LrSlider("Feather", o.feather, 0f, 20f, 0f, 0, "px", { vm.setErase(o.copy(feather = it)) }, {}, { vm.setErase(o.copy(feather = 0f)) })
        LrSlider("Opacity", o.opacity, 0f, 100f, 100f, 0, "%", { vm.setErase(o.copy(opacity = it)) }, {}, { vm.setErase(o.copy(opacity = 100f)) })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Toggle("Contiguous", o.contiguous, Modifier.weight(1f)) { vm.setErase(o.copy(contiguous = it)) }
            Toggle("Anti-alias", o.antiAlias, Modifier.weight(1f)) { vm.setErase(o.copy(antiAlias = it)) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sample", fontSize = 15.sp, color = Lc.text, modifier = Modifier.weight(1f))
            listOf("Point", "3×3", "5×5").forEachIndexed { i, label ->
                val on = o.sample == i
                Text(
                    label,
                    fontSize = 13.sp,
                    color = if (on) Color.White else Lc.label,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Lc.accent else Lc.raised).clickable { vm.setErase(o.copy(sample = i)) }.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
internal fun Toggle(label: String, on: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = Lc.text, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Lc.accent, checkedThumbColor = Color.White),
            modifier = Modifier.graphicsLayer { scaleX = 0.8f; scaleY = 0.8f },
        )
        Spacer(Modifier.width(10.dp))
    }
}

private fun iconOf(t: Tool, tab: MainTab): ImageVector = when (tab) {
    MainTab.Quick -> when (t.id) {
        "auto" -> Icons.Outlined.AutoAwesome
        "pop" -> Icons.Outlined.Person
        "sky" -> Icons.Outlined.Cloud
        "bw" -> Icons.Outlined.Contrast
        "cutout" -> Icons.Outlined.ContentCut
        else -> Icons.Outlined.RestartAlt
    }
    MainTab.Masking -> when (t.id) {
        "subject" -> Icons.Outlined.Person
        "background" -> Icons.Outlined.Wallpaper
        "sky" -> Icons.Outlined.Cloud
        "linear" -> Icons.Outlined.Gradient
        else -> Icons.Outlined.RadioButtonUnchecked
    }
    MainTab.Remove -> when (t.id) {
        "eraser" -> LcIcons.Remove
        "ai" -> Icons.Outlined.AutoAwesome
        "keep" -> Icons.Outlined.Wallpaper
        "undo" -> Icons.AutoMirrored.Outlined.Undo
        "reset" -> Icons.Outlined.Restore
        else -> Icons.Outlined.SaveAlt
    }
    else -> when (t.id) {
        "auto" -> Icons.Outlined.AutoAwesome
        "light" -> Icons.Outlined.WbSunny
        "color" -> Icons.Outlined.Thermostat
        "curve" -> LcIcons.Curve
        "effects" -> Icons.Outlined.Vignette
        "detail" -> Icons.Outlined.Details
        "optics" -> Icons.Outlined.CameraAlt
        else -> Icons.Outlined.Straighten
    }
}

/** The labelled tools above the main bar; the open one sits on a lighter rounded square. */
@Composable
internal fun ToolRow(tools: List<Tool>, selected: Tool?, divideAfterFirst: Boolean, onSelect: (Tool) -> Unit) {
    val tab = when (tools) {
        Tools.quick -> MainTab.Quick
        Tools.masking -> MainTab.Masking
        Tools.remove -> MainTab.Remove
        else -> MainTab.Edit
    }
    LazyRow(
        Modifier.fillMaxWidth().background(Lc.bar).padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tools.forEachIndexed { i, t ->
            item(key = t.id) {
                val on = selected?.id == t.id
                Column(
                    Modifier
                        .width(68.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) Lc.raised else Color.Transparent)
                        .clickable { onSelect(t) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(iconOf(t, tab), contentDescription = null, tint = Lc.text, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(t.label, color = Lc.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (divideAfterFirst && i == 0) {
                item(key = "divider") { Box(Modifier.width(1.dp).height(34.dp).background(Color(0xFF4A4A4A))) }
            }
        }
    }
}

/** The six main tabs; the current one in a blue rounded square, a dot under each. */
@Composable
internal fun MainBar(tab: MainTab, onSelect: (MainTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Lc.bar).navigationBarsPadding().padding(top = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        MainTab.entries.forEach { t ->
            val on = t == tab
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onSelect(t) }.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(46.dp, 42.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Lc.accent else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when (t) {
                            MainTab.Quick -> Icons.Outlined.AutoFixHigh
                            MainTab.Presets -> LcIcons.Presets
                            MainTab.Crop -> Icons.Outlined.Crop
                            MainTab.Edit -> Icons.Outlined.Tune
                            MainTab.Masking -> LcIcons.Masking
                            MainTab.Remove -> LcIcons.Remove
                        },
                        contentDescription = t.label,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Box(Modifier.padding(top = 5.dp).size(3.dp).clip(CircleShape).background(Lc.muted))
            }
        }
    }
}


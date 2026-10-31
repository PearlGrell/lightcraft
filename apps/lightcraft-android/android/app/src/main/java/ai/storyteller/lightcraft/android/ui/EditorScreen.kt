package ai.storyteller.lightcraft.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.Engine
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.MainTab
import ai.storyteller.lightcraft.android.Tool
import ai.storyteller.lightcraft.android.ToolKind
import ai.storyteller.lightcraft.android.Tools

/**
 * The editor: the photo edge to edge on near-black, a slim bar over its top (back; histogram,
 * undo, redo, compare, more, export), and at the bottom the open tool's panel, a row of tools and
 * the six main tabs (Quick, Presets, Crop, Edit, Masking, Remove).
 */
@Composable
fun EditorScreen(vm: LightCraftViewModel, photo: Long, snackbar: SnackbarHostState) {
    val preview by vm.preview.collectAsStateWithLifecycle()
    val before by vm.before.collectAsStateWithLifecycle()
    val cutout by vm.cutout.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val tool by vm.tool.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val showHistogram by vm.showHistogram.collectAsStateWithLifecycle()
    val histogram by vm.histogram.collectAsStateWithLifecycle()
    val brush by vm.brush.collectAsStateWithLifecycle()
    val activeMask by vm.activeMask.collectAsStateWithLifecycle()
    val objectExclude by vm.objectExclude.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    var compare by remember(photo) { mutableStateOf(false) }
    var split by remember(photo) { mutableFloatStateOf(0.5f) }
    var holding by remember { mutableStateOf(false) }
    var zoom by remember(photo) { mutableFloatStateOf(1f) }
    var pan by remember(photo) { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var sheet by remember { mutableStateOf<String?>(null) }
    val stroke = remember { mutableStateListOf<Offset>() }
    val removing = tab == MainTab.Remove
    val painting = tab == MainTab.Masking && tool?.kind == ToolKind.Brush && activeMask != null
    val tapping = tab == MainTab.Masking && tool?.kind == ToolKind.Object && ai.installed
    val density = LocalDensity.current

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
            // painting and tapping work on the whole, unzoomed photo
            if ((painting || tapping) && zoom != 1f) {
                zoom = 1f
                pan = Offset.Zero
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(photo, removing, painting, tapping) {
                        if (!removing && !painting && !tapping) {
                            detectTransformGestures { _, p, z, _ ->
                                zoom = (zoom * z).coerceIn(1f, 8f)
                                pan = if (zoom == 1f) Offset.Zero else pan + p
                            }
                        }
                    }
                    .pointerInput(photo, painting, fit) {
                        val r = fit ?: return@pointerInput
                        if (!painting) return@pointerInput
                        detectDragGestures(
                            onDragStart = { o -> stroke.clear(); if (r.contains(o)) stroke.add(o) },
                            onDragEnd = {
                                val pts = stroke.map { ((it.x - r.left) / r.width) to ((it.y - r.top) / r.height) }
                                stroke.clear()
                                vm.brushStroke(thin(pts))
                            },
                            onDragCancel = { stroke.clear() },
                        ) { c, _ ->
                            c.consume()
                            if (r.contains(c.position)) stroke.add(c.position)
                        }
                    }
                    .pointerInput(photo, removing, painting, tapping, fit, tool?.id) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (!removing && !painting && !tapping) {
                                    zoom = if (zoom > 1f) 1f else 2.5f
                                    pan = Offset.Zero
                                }
                            },
                            onTap = { o ->
                                val r = fit ?: return@detectTapGestures
                                if (!r.contains(o)) return@detectTapGestures
                                val u = (o.x - r.left) / r.width
                                val v = (o.y - r.top) / r.height
                                when {
                                    removing && tool?.id == "eraser" -> vm.eraseAt(u, v)
                                    tapping -> vm.objectTap(u, v, objectExclude)
                                    painting -> vm.brushStroke(listOf(u to v))
                                }
                            },
                            onPress = {
                                if (removing || painting || tapping) return@detectTapGestures
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
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Lc.accent, strokeWidth = 2.dp)
                }
                if (painting && fit != null) {
                    // the stroke as it is drawn, the brush's own size
                    val radius = brush.size * maxOf(fit.width, fit.height)
                    Canvas(Modifier.fillMaxSize()) {
                        val color = if (brush.erase) Color(0x66E5484D) else Color(0x66F2A516)
                        stroke.forEach { drawCircle(color, radius, it) }
                    }
                }
                if (holding && !removing) Pill("Before", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 60.dp))
                if (showHistogram && !removing) {
                    HistogramView(
                        histogram,
                        onZoneDrag = vm::dragZone,
                        onDone = vm::finishInteraction,
                        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 10.dp),
                    )
                }
                if (painting || tapping) {
                    val hint = when {
                        painting && brush.erase -> "Erasing · drag on the photo"
                        painting -> "Painting · drag on the photo"
                        objectExclude -> "Tap parts to remove"
                        else -> "Tap an object to select it"
                    }
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(Lc.pill).padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(hint, fontSize = 12.sp, color = Lc.text)
                        Text("Done", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Lc.accent, modifier = Modifier.clickable(onClick = vm::closeTool).padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
            }
            TopBar(vm, busy, compare, showHistogram, onCompare = { compare = !compare; if (compare) vm.loadBefore() }, onSheet = { sheet = it })
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        }
        BottomChrome(vm, tab, tool)
    }

    when (sheet) {
        "export" -> ExportSheet(vm) { sheet = null }
        "info" -> InfoSheet(vm) { sheet = null }
    }
}

/** At most one point every ~1 % of the photo, so a long stroke stays a small command. */
private fun thin(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
    val out = ArrayList<Pair<Float, Float>>()
    for (p in points) {
        val last = out.lastOrNull()
        if (last == null || kotlin.math.hypot(p.first - last.first, p.second - last.second) > 0.01f) out += p
    }
    points.lastOrNull()?.let { if (out.lastOrNull() != it) out += it }
    return out.take(512)
}

@Composable
private fun TopBar(
    vm: LightCraftViewModel,
    busy: Boolean,
    compare: Boolean,
    histogram: Boolean,
    onCompare: () -> Unit,
    onSheet: (String) -> Unit,
) {
    val copied by vm.copied.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlayIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library", Color.White, vm::close)
        Spacer(Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Lc.text)
        OverlayIcon(Icons.Outlined.BarChart, "Histogram", if (histogram) Lc.accent else Color.White, vm::toggleHistogram)
        OverlayIcon(Icons.AutoMirrored.Outlined.Undo, "Undo", Color.White, vm::undo)
        OverlayIcon(Icons.AutoMirrored.Outlined.Redo, "Redo", Color.White, vm::redo)
        OverlayIcon(LcIcons.Compare, "Before and after", if (compare) Lc.accent else Color.White, onCompare)
        Box {
            OverlayIcon(Icons.Outlined.MoreVert, "More", Color.White) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Lc.bar) {
                MenuItem(Icons.Outlined.Info, "Info and rating") { menu = false; onSheet("info") }
                MenuItem(Icons.Outlined.ContentCopy, "Copy edits") { menu = false; vm.copyEdits() }
                if (copied) MenuItem(Icons.Outlined.ContentPaste, "Paste edits") { menu = false; vm.pasteEdits() }
                MenuItem(Icons.Outlined.RestartAlt, "Reset all edits") { menu = false; vm.cropCommand("develop.reset") }
                MenuItem(Icons.Outlined.Delete, "Delete photo", Lc.danger) { menu = false; vm.deleteCurrent() }
            }
        }
        Text(
            "Export",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Lc.onAccent,
            modifier = Modifier
                .padding(start = 4.dp, end = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Lc.text)
                .clickable { onSheet("export") }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, tint: Color = Lc.text, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = tint, fontSize = 14.sp) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
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

/** The before/after divider: a thin line with a round handle, Before and After labels. */
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
            drawLine(Lc.text, Offset(x, fit.top), Offset(x, fit.bottom), 1.dp.toPx())
            val c = Offset(x, fit.center.y)
            drawCircle(Lc.text, 12.dp.toPx(), c)
            drawCircle(Color(0x33000000), 12.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            // two small chevrons
            val a = 4.dp.toPx()
            drawLine(Lc.onAccent, Offset(c.x - 2 * a + a, c.y - a), Offset(c.x - 2 * a, c.y), 1.5.dp.toPx())
            drawLine(Lc.onAccent, Offset(c.x - 2 * a, c.y), Offset(c.x - 2 * a + a, c.y + a), 1.5.dp.toPx())
            drawLine(Lc.onAccent, Offset(c.x + 2 * a - a, c.y - a), Offset(c.x + 2 * a, c.y), 1.5.dp.toPx())
            drawLine(Lc.onAccent, Offset(c.x + 2 * a, c.y), Offset(c.x + 2 * a - a, c.y + a), 1.5.dp.toPx())
        }
        val top = with(density) { (fit.top).toDp() }
        Pill("Before", Modifier.padding(start = with(density) { fit.left.toDp() } + 12.dp, top = maxOf(top, 0.dp) + 60.dp))
        Pill(
            "After",
            Modifier.align(Alignment.TopEnd).padding(end = with(density) { (fit.left).toDp() } + 12.dp, top = maxOf(top, 0.dp) + 60.dp),
        )
    }
}

@Composable
internal fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Lc.text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        modifier = modifier.background(Lc.pill, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
internal fun OverlayIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Box(Modifier.size(34.dp).background(Color(0x47000000), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
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
            MainTab.Masking -> Tools.masking(Engine.aiMasksAvailable)
            MainTab.Remove -> Tools.remove
            else -> emptyList()
        }
        if (row.isNotEmpty()) ToolRow(row, tab, tool, divideAfterFirst = tab == MainTab.Edit, onSelect = vm::selectTool)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
        MainBar(tab, vm::selectTab)
    }
}

private fun iconOf(t: Tool, tab: MainTab): ImageVector = when (tab) {
    MainTab.Quick -> when (t.id) {
        "auto" -> Icons.Outlined.AutoFixHigh
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
        "object" -> Icons.Outlined.TouchApp
        "describe" -> Icons.Outlined.TextFields
        "brush" -> Icons.Outlined.Brush
        "dodge" -> Icons.Outlined.LightMode
        "burn" -> Icons.Outlined.DarkMode
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
        "auto" -> Icons.Outlined.AutoFixHigh
        "light" -> Icons.Outlined.WbSunny
        "color" -> Icons.Outlined.Thermostat
        "curve" -> LcIcons.Curve
        "effects" -> Icons.Outlined.Vignette
        "detail" -> Icons.Outlined.Details
        "optics" -> Icons.Outlined.CameraAlt
        else -> Icons.Outlined.Straighten
    }
}

/** The labelled tools above the main bar; the open one is paper white with an amber mark. */
@Composable
internal fun ToolRow(tools: List<Tool>, tab: MainTab, selected: Tool?, divideAfterFirst: Boolean, onSelect: (Tool) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().background(Lc.bar).padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tools.forEachIndexed { i, t ->
            item(key = t.id) {
                val on = selected?.id == t.id
                Column(
                    Modifier
                        .width(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(t) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(iconOf(t, tab), contentDescription = null, tint = if (on) Lc.text else Lc.label, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.height(5.dp))
                    Text(
                        t.label,
                        color = if (on) Lc.text else Lc.label,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(16.dp).height(2.dp).background(if (on) Lc.accent else Color.Transparent, RoundedCornerShape(1.dp)))
                }
            }
            if (divideAfterFirst && i == 0) {
                item(key = "divider") { Box(Modifier.width(1.dp).height(30.dp).background(Lc.divider)) }
            }
        }
    }
}

/** The six main tabs: icon and label; the current one in paper white with an amber mark. */
@Composable
internal fun MainBar(tab: MainTab, onSelect: (MainTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Lc.chrome).navigationBarsPadding().padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        MainTab.entries.forEach { t ->
            val on = t == tab
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onSelect(t) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
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
                    tint = if (on) Lc.text else Lc.muted,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    when (t) {
                        MainTab.Quick -> "Quick"
                        else -> t.label
                    },
                    fontSize = 10.5.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Lc.text else Lc.muted,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Box(Modifier.width(18.dp).height(2.dp).background(if (on) Lc.accent else Color.Transparent, RoundedCornerShape(1.dp)))
            }
        }
    }
}

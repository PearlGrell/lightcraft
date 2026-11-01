package ai.storyteller.lightcraft.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.MainTab
import ai.storyteller.lightcraft.android.ToolKind

/**
 * The editor, laid out as the desktop's develop view for a phone: a top bar (back to the library,
 * the file name, undo, redo, before / after, info, share, more), the photo on the canvas, the open
 * tool's panel under it (the desktop's right panel: histogram, Edit, Profile, sections), and the
 * tool rail (Edit, Crop, Remove, Masking, Presets). Tap the open tool again to hide its panel.
 */
@Composable
fun EditorScreen(vm: LightCraftViewModel, photo: Long, snackbar: SnackbarHostState) {
    val preview by vm.preview.collectAsStateWithLifecycle()
    val before by vm.before.collectAsStateWithLifecycle()
    val cutout by vm.cutout.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val tool by vm.tool.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val brush by vm.brush.collectAsStateWithLifecycle()
    val activeMask by vm.activeMask.collectAsStateWithLifecycle()
    val objectExclude by vm.objectExclude.collectAsStateWithLifecycle()
    val wbPicking by vm.wbPicking.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    var compare by remember(photo) { mutableStateOf(false) }
    var split by remember(photo) { mutableFloatStateOf(0.5f) }
    var holding by remember { mutableStateOf(false) }
    var zoom by remember(photo) { mutableFloatStateOf(1f) }
    var pan by remember(photo) { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var panelOpen by rememberSaveable { mutableStateOf(true) }
    var stripOpen by rememberSaveable { mutableStateOf(true) }
    var panelShare by rememberSaveable { mutableFloatStateOf(0.46f) }
    val stroke = remember { mutableStateListOf<Offset>() }
    val removing = tab == MainTab.Remove
    val painting = tab == MainTab.Masking && tool?.kind == ToolKind.Brush && activeMask != null
    val tapping = tab == MainTab.Masking && tool?.kind == ToolKind.Object && ai.installed
    val picking = wbPicking && tab == MainTab.Edit
    val fixed = painting || tapping || picking
    if (fixed && zoom != 1f) {
        zoom = 1f
        pan = Offset.Zero
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Lc.canvas)) {
        val panelHeight = maxHeight * panelShare
        val fullHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
            TopBar(vm, compare, busy, onCompare = { compare = !compare; if (compare) vm.loadBefore() }, onSheet = { sheet = it })
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
                        .pointerInput(photo, removing, fixed) {
                            if (!removing && !fixed) {
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
                        .pointerInput(photo, removing, painting, tapping, picking, fit, tool?.id) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (!removing && !fixed) {
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
                                        picking -> vm.pickWb(u, v)
                                        removing && tool?.id == "eraser" -> vm.eraseAt(u, v)
                                        tapping -> vm.objectTap(u, v, objectExclude)
                                        painting -> vm.brushStroke(listOf(u to v))
                                    }
                                },
                                onPress = {
                                    if (removing || fixed) return@detectTapGestures
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
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = Lc.text, strokeWidth = 2.dp)
                    }
                    if (painting && fit != null) {
                        val radius = brush.size * maxOf(fit.width, fit.height)
                        Canvas(Modifier.fillMaxSize()) {
                            val color = if (brush.erase) Lc.reject.copy(alpha = 0.4f) else Color(0x66E02030)
                            stroke.forEach { drawCircle(color, radius, it) }
                        }
                    }
                    if (holding && !removing) Pill("Before", Modifier.align(Alignment.TopStart).padding(12.dp))
                    if (fixed) {
                        val hint = when {
                            picking -> "Tap a neutral grey or white in the photo"
                            painting && brush.erase -> "Erasing: drag on the photo"
                            painting -> "Painting: drag on the photo"
                            objectExclude -> "Tap the parts to subtract"
                            else -> "Tap an object to select it"
                        }
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(6.dp)).background(Lc.pill).padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(hint, fontFamily = Inter, fontSize = 12.sp, color = Lc.text)
                            Text(
                                "Done",
                                fontFamily = Inter,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Lc.text,
                                modifier = Modifier.clickable { if (picking) vm.armWbPicker(false) else vm.closeTool() }.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            }
            if (stripOpen) Filmstrip(vm)
            // the panel's handle: tap to fold the panel away or bring it back, drag to resize it
            val density = LocalDensity.current
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .background(Lc.chrome)
                    .pointerInput(Unit) { detectTapGestures(onTap = { panelOpen = !panelOpen }) }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { c, dy ->
                            c.consume()
                            val total = with(density) { fullHeight.toPx() }
                            if (!panelOpen && dy < 0) panelOpen = true
                            panelShare = (panelShare - dy / total).coerceIn(0.2f, 0.75f)
                        }
                    }
                    .semantics { contentDescription = if (panelOpen) "Hide panel (drag to resize)" else "Show panel" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Lc.buttonBorder))
            }
            if (panelOpen) {
                Column(Modifier.fillMaxWidth().height(panelHeight).background(Lc.chrome).verticalScroll(rememberScrollState())) {
                    Panel(vm, tab, tool)
                }
            }
            Rail(tab, stripOpen, onStrip = { stripOpen = !stripOpen }) { t ->
                if (t == tab) panelOpen = !panelOpen else { panelOpen = true; vm.selectTab(t) }
            }
        }
    }

    when (sheet) {
        "export" -> ExportSheet(vm) { sheet = null }
        "info" -> InfoSheet(vm) { sheet = null }
    }
}

/**
 * The desktop's filmstrip: the photos of the library view the editor came from, the open one
 * framed in white; tap another to edit it.
 */
@Composable
private fun Filmstrip(vm: LightCraftViewModel) {
    val strip by vm.strip.collectAsStateWithLifecycle()
    val version by vm.thumbVersion.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    if (strip.size < 2) return
    val current = vm.editingImageId
    val state = rememberLazyListState()
    LaunchedEffect(editing) {
        val i = strip.indexOfFirst { it.id == current }
        if (i >= 0) state.animateScrollToItem((i - 2).coerceAtLeast(0))
    }
    LazyRow(
        Modifier.fillMaxWidth().height(64.dp).background(Lc.inset),
        state = state,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(strip, key = { it.id }) { g ->
            val on = g.id == current
            var bmp by remember(g.id) { mutableStateOf(vm.cachedThumbnail(g)) }
            LaunchedEffect(g.id, version) { vm.thumbnail(g, 192)?.let { bmp = it } }
            Box(
                Modifier
                    .fillMaxHeight()
                    .width((52f * g.aspect.coerceIn(0.6f, 1.8f)).dp)
                    .background(if (on) Lc.cellSelected else Lc.skeleton)
                    .then(if (on) Modifier.border(2.dp, Lc.pick) else Modifier)
                    .clickable { vm.switchTo(g) },
            ) {
                bmp?.let { Image(it.asImageBitmap(), g.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (on) 2.dp else 0.dp)) }
            }
        }
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
private fun TopBar(vm: LightCraftViewModel, compare: Boolean, busy: Boolean, onCompare: () -> Unit, onSheet: (String) -> Unit) {
    val copied by vm.copied.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Lc.chrome).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LcIconButton(DeskIcon.Back, "Back to the library", onClick = vm::close)
            Text(
                info?.optString("file_name")?.takeIf { it.isNotEmpty() } ?: "",
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Lc.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (busy) CircularProgressIndicator(Modifier.padding(end = 6.dp).height(16.dp).width(16.dp), color = Lc.text, strokeWidth = 2.dp)
            LcIconButton(DeskIcon.Undo, "Undo", size = 38.dp, onClick = vm::undo)
            LcIconButton(DeskIcon.Redo, "Redo", size = 38.dp, onClick = vm::redo)
            LcIconButton(DeskIcon.BeforeAfter, "Before / After", active = compare, size = 38.dp, onClick = onCompare)
            LcIconButton(DeskIcon.Share, "Export", size = 38.dp) { onSheet("export") }
            Box {
                LcIconButton(DeskIcon.More, "More", size = 38.dp) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Lc.chrome) {
                    MenuItem(DeskIcon.Info, "Info and Rating") { menu = false; onSheet("info") }
                    MenuItem(DeskIcon.Versions, "Copy Edit Settings") { menu = false; vm.copyEdits() }
                    if (copied) MenuItem(DeskIcon.Check, "Paste Edit Settings") { menu = false; vm.pasteEdits() }
                    MenuItem(DeskIcon.Rotate, "Reset All Edits") { menu = false; vm.quickAction("reset") }
                    MenuItem(DeskIcon.Trash, "Delete Photo", Lc.reject) { menu = false; vm.deleteCurrent() }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
    }
}

@Composable
private fun MenuItem(icon: DeskIcon, label: String, tint: Color = Lc.text, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = tint, fontFamily = Inter, fontSize = 14.sp) },
        leadingIcon = { LcIcon(icon, if (tint == Lc.text) Lc.icon else tint, 18.dp) },
        onClick = onClick,
    )
}

private fun railIcon(t: MainTab) = when (t) {
    MainTab.Edit -> DeskIcon.Sliders
    MainTab.Crop -> DeskIcon.Crop
    MainTab.Remove -> DeskIcon.Eraser
    MainTab.Masking -> DeskIcon.Mask
    MainTab.Presets -> DeskIcon.Presets
}

/** The desktop's tool rail, laid along the bottom: the open tool on the tool-active fill. */
@Composable
internal fun Rail(tab: MainTab, strip: Boolean = true, onStrip: () -> Unit = {}, onSelect: (MainTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Lc.chrome).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            MainTab.entries.forEach { t -> LcIconButton(railIcon(t), t.label, active = t == tab, size = 44.dp, iconSize = 22.dp) { onSelect(t) } }
            Box(Modifier.width(1.dp).height(24.dp).background(Lc.buttonBorder))
            LcIconButton(DeskIcon.Filmstrip, if (strip) "Hide filmstrip" else "Show filmstrip", active = strip, size = 44.dp, iconSize = 22.dp, onClick = onStrip)
        }
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

/** The before / after divider: a thin line with a round handle, Before and After labels. */
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
            drawLine(Lc.pick, Offset(x, fit.top), Offset(x, fit.bottom), 1.dp.toPx())
            val c = Offset(x, fit.center.y)
            drawCircle(Lc.chrome, 12.dp.toPx(), c)
            drawCircle(Lc.pick, 12.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        }
        val top = with(density) { fit.top.toDp() }
        Pill("Before", Modifier.padding(start = with(density) { fit.left.toDp() } + 10.dp, top = maxOf(top, 0.dp) + 10.dp))
        Pill("After", Modifier.align(Alignment.TopEnd).padding(end = with(density) { fit.left.toDp() } + 10.dp, top = maxOf(top, 0.dp) + 10.dp))
    }
}

@Composable
internal fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Lc.text,
        fontFamily = Inter,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.background(Lc.pill, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.MaskInfo
import ai.storyteller.lightcraft.android.Tools
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Screen ↔ shown-photo coordinates under the editor's fit, zoom and pan: `uv` is 0..1 across the
 * picture the editor shows (the engine maps it on to the mask's own coordinates).
 */
internal class ViewMap(private val fit: Rect, private val zoom: Float, private val pan: Offset, area: IntSize) {
    private val cx = area.width / 2f
    private val cy = area.height / 2f

    fun toUv(o: Offset): Offset {
        val x = (o.x - cx - pan.x) / zoom + cx
        val y = (o.y - cy - pan.y) / zoom + cy
        return Offset((x - fit.left) / fit.width, (y - fit.top) / fit.height)
    }

    fun toScreen(u: Float, v: Float): Offset {
        val x = fit.left + u * fit.width
        val y = fit.top + v * fit.height
        return Offset((x - cx) * zoom + cx + pan.x, (y - cy) * zoom + cy + pan.y)
    }

    /** Screen pixels per unit of the shown photo's long edge. */
    val longPx: Float get() = maxOf(fit.width, fit.height) * zoom
}

/** Long-edge units → normalized x and y of a space whose width ÷ height is [aspect]. */
private fun longNorm(aspect: Double): Pair<Double, Double> = if (aspect >= 1.0) 1.0 to aspect else (1.0 / aspect) to 1.0

/**
 * What a mask needs on the photo itself: the brush paints, a radial or linear gradient shows its
 * handles and drags them, a colour range takes a tap as a colour sample. Touches that hit none of
 * these go on to the photo (pinch to zoom, press and hold for the original).
 */
@Composable
internal fun MaskCanvas(vm: LightCraftViewModel, map: ViewMap) {
    val masks by vm.masks.collectAsStateWithLifecycle()
    val active by vm.activeMask.collectAsStateWithLifecycle()
    val brush by vm.brush.collectAsStateWithLifecycle()
    val armed by vm.objectArmed.collectAsStateWithLifecycle()
    val mask = masks.firstOrNull { it.id == active }
    if (armed || mask?.kind == "object") {
        ObjectTapLayer(vm, map)
        return
    }
    mask ?: return
    val shape = mask.shape ?: return
    val density = LocalDensity.current
    val grab = with(density) { 30.dp.toPx() }
    val current by rememberUpdatedState(mask)
    val currentMap by rememberUpdatedState(map)
    val stroke = remember { mutableStateListOf<Offset>() }

    // the gradient's handles on screen (mask coordinates → shown photo → screen)
    val handles: List<Offset> = remember(shape.toString(), map, vm.maskAspect) { gradientHandles(vm, mask, map) }
    val outline: List<Offset> = remember(shape.toString(), map, vm.maskAspect) { radialOutline(vm, mask, map) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(mask.id, mask.kind) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val m = current
                    val mp = currentMap
                    when (m.kind) {
                        "brush" -> {
                            // one finger paints; the stroke goes to the engine when the finger lifts
                            down.consume()
                            stroke.clear()
                            stroke.add(down.position)
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.size > 1) {
                                    stroke.clear()
                                    break
                                }
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                if (c.positionChange() != Offset.Zero) {
                                    c.consume()
                                    stroke.add(c.position)
                                }
                            }
                            if (stroke.isNotEmpty()) {
                                val uv = FloatArray(stroke.size * 2)
                                stroke.forEachIndexed { i, o ->
                                    val p = mp.toUv(o)
                                    uv[2 * i] = p.x
                                    uv[2 * i + 1] = p.y
                                }
                                vm.paint(uv)
                            }
                            stroke.clear()
                        }
                        "colorRange" -> {
                            // a tap samples the colour there (each tap adds one, up to five)
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (c.positionChange().getDistance() > grab / 3) moved = true
                                if (!c.pressed) break
                            }
                            if (!moved) {
                                val p = mp.toUv(down.position)
                                val samples = m.shape?.optJSONArray("samples")?.length() ?: 0
                                if (p.x in 0f..1f && p.y in 0f..1f) vm.sampleColor(p.x, p.y, add = samples > 0)
                            }
                        }
                        "radial", "linear" -> {
                            val hs = gradientHandles(vm, m, mp)
                            val hit = hs.indices.minByOrNull { (hs[it] - down.position).getDistance() }
                                ?.takeIf { (hs[it] - down.position).getDistance() < grab } ?: return@awaitEachGesture
                            down.consume()
                            val start = m.shape?.let { JSONObject(it.toString()) } ?: return@awaitEachGesture
                            val at0 = maskPoint(vm, m, mp, down.position) ?: return@awaitEachGesture
                            var last = start
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                if (c.positionChange() == Offset.Zero) continue
                                c.consume()
                                val at = maskPoint(vm, m, mp, c.position) ?: continue
                                last = movedShape(start, hit, at0, at, vm.maskAspect)
                                vm.moveShape(last, done = false)
                            }
                            vm.moveShape(last, done = true)
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val white = Color.White
            if (mask.kind == "radial" && outline.size > 2) {
                val path = Path()
                outline.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                path.close()
                drawPath(path, white.copy(alpha = 0.85f), style = Stroke(1.5.dp.toPx()))
            }
            if (mask.kind == "linear" && handles.size >= 2) {
                drawLine(white.copy(alpha = 0.85f), handles[0], handles[1], strokeWidth = 1.5.dp.toPx())
            }
            if (mask.kind == "radial" || mask.kind == "linear") {
                handles.forEachIndexed { i, h ->
                    val r = if (i == 0) 8.dp.toPx() else 6.dp.toPx()
                    drawCircle(Color(0x66000000), r + 1.dp.toPx(), h)
                    drawCircle(white, r, h, style = Stroke(2.dp.toPx()))
                }
            }
            if (mask.kind == "brush" && stroke.size > 1) {
                // the stroke being painted, at the brush's size
                val path = Path()
                stroke.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                val w = (brush.size * map.longPx).coerceAtLeast(2f)
                drawPath(path, (if (brush.erase) Color(0x660A84FF) else Color(0x66E61E28)), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Object selection: a tap starts one (when armed) or adds to / leaves out of the active Object mask. */
@Composable
private fun ObjectTapLayer(vm: LightCraftViewModel, map: ViewMap) {
    val m by rememberUpdatedState(map)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var moved = false
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.size > 1) moved = true
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if ((c.position - down.position).getDistance() > 24f) moved = true
                    if (!c.pressed) break
                }
                if (!moved) {
                    val p = m.toUv(down.position)
                    if (p.x in 0f..1f && p.y in 0f..1f) vm.objectTap(p.x, p.y)
                }
            }
        },
    )
}

/** A screen point as the mask's coordinates, or null where the engine can't place it. */
private fun maskPoint(vm: LightCraftViewModel, m: MaskInfo, map: ViewMap, o: Offset): Offset? {
    val uv = map.toUv(o)
    val p = vm.viewToMask(m.id, floatArrayOf(uv.x, uv.y))
    return if (p.size >= 2 && p[0].isFinite() && p[1].isFinite()) Offset(p[0], p[1]) else null
}

private fun pointOf(o: JSONObject?, key: String): Offset? =
    o?.optJSONObject(key)?.let { Offset(it.optDouble("x", 0.5).toFloat(), it.optDouble("y", 0.5).toFloat()) }

/**
 * A gradient's handles in mask coordinates: a radial's centre then its four axis ends (right,
 * bottom, left, top in its own frame); a linear's start, end and middle.
 */
private fun handlePoints(m: MaskInfo, aspect: Double): List<Offset> {
    val s = m.shape ?: return emptyList()
    return when (m.kind) {
        "radial" -> {
            val c = pointOf(s, "center") ?: return emptyList()
            val rx = s.optDouble("rx", 0.2)
            val ry = s.optDouble("ry", 0.2)
            val a = Math.toRadians(s.optDouble("angle", 0.0))
            val (lx, ly) = longNorm(aspect)
            listOf(c) + listOf(0.0, 90.0, 180.0, 270.0).map { deg ->
                val t = Math.toRadians(deg)
                val x = rx * cos(t)
                val y = ry * sin(t)
                Offset((c.x + (x * cos(a) - y * sin(a)) * lx).toFloat(), (c.y + (x * sin(a) + y * cos(a)) * ly).toFloat())
            }
        }
        "linear" -> {
            val a = pointOf(s, "start") ?: return emptyList()
            val b = pointOf(s, "end") ?: return emptyList()
            listOf(a, b, Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f))
        }
        else -> emptyList()
    }
}

/** Mask coordinates → screen, through the engine (NaN where a point has no place). */
private fun toScreen(vm: LightCraftViewModel, m: MaskInfo, map: ViewMap, pts: List<Offset>): List<Offset> {
    if (pts.isEmpty()) return emptyList()
    val flat = FloatArray(pts.size * 2)
    pts.forEachIndexed { i, p ->
        flat[2 * i] = p.x
        flat[2 * i + 1] = p.y
    }
    val uv = vm.maskToView(m.id, flat)
    return (0 until uv.size / 2).mapNotNull { i ->
        val u = uv[2 * i]
        val v = uv[2 * i + 1]
        if (u.isFinite() && v.isFinite()) map.toScreen(u, v) else null
    }
}

private fun gradientHandles(vm: LightCraftViewModel, m: MaskInfo, map: ViewMap): List<Offset> =
    toScreen(vm, m, map, handlePoints(m, vm.maskAspect))

private fun radialOutline(vm: LightCraftViewModel, m: MaskInfo, map: ViewMap): List<Offset> {
    if (m.kind != "radial") return emptyList()
    val s = m.shape ?: return emptyList()
    val c = pointOf(s, "center") ?: return emptyList()
    val rx = s.optDouble("rx", 0.2)
    val ry = s.optDouble("ry", 0.2)
    val a = Math.toRadians(s.optDouble("angle", 0.0))
    val (lx, ly) = longNorm(vm.maskAspect)
    val pts = (0 until 48).map { i ->
        val t = i / 48.0 * 2 * Math.PI
        val x = rx * cos(t)
        val y = ry * sin(t)
        Offset((c.x + (x * cos(a) - y * sin(a)) * lx).toFloat(), (c.y + (x * sin(a) + y * cos(a)) * ly).toFloat())
    }
    return toScreen(vm, m, map, pts)
}

/**
 * [start] with handle [handle] dragged from [from] to [to] (mask coordinates), as the desktop's
 * `moved_shape`: a radial's centre moves it, an axis end sets that radius (snapping to a circle
 * near equal); a linear's ends move one end, its middle moves both.
 */
private fun movedShape(start: JSONObject, handle: Int, from: Offset, to: Offset, aspect: Double): JSONObject {
    val s = JSONObject(start.toString())
    fun put(key: String, p: Offset) = s.put(key, JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()))
    when (s.optString("kind")) {
        "radial" -> {
            val c = pointOf(start, "center") ?: return s
            if (handle == 0) {
                put("center", Offset(c.x + to.x - from.x, c.y + to.y - from.y))
                return s
            }
            val (lx, ly) = longNorm(aspect)
            val dx = (to.x - c.x) / lx
            val dy = (to.y - c.y) / ly
            val a = Math.toRadians(s.optDouble("angle", 0.0))
            val rx0 = s.optDouble("rx", 0.2)
            val ry0 = s.optDouble("ry", 0.2)
            if (handle == 1 || handle == 3) {
                var rx = kotlin.math.abs(dx * cos(a) + dy * sin(a)).coerceAtLeast(0.01)
                var ry = ry0
                if (kotlin.math.abs(rx - ry) / maxOf(rx, ry) < 0.05) ry = rx
                s.put("rx", rx).put("ry", ry)
            } else {
                var ry = kotlin.math.abs(-dx * sin(a) + dy * cos(a)).coerceAtLeast(0.01)
                var rx = rx0
                if (kotlin.math.abs(rx - ry) / maxOf(rx, ry) < 0.05) rx = ry
                s.put("rx", rx).put("ry", ry)
            }
        }
        "linear" -> {
            val a = pointOf(start, "start") ?: return s
            val b = pointOf(start, "end") ?: return s
            val d = Offset(to.x - from.x, to.y - from.y)
            when (handle) {
                0 -> put("start", a + d)
                1 -> put("end", b + d)
                else -> {
                    put("start", a + d)
                    put("end", b + d)
                }
            }
        }
    }
    return s
}

/** Mask kinds the Add and Subtract menus offer. */
private val partKinds = listOf("brush" to "Brush", "linear" to "Linear Gradient", "radial" to "Radial Gradient", "color" to "Color Range", "luminance" to "Luminance Range", "subject" to "Subject")

/**
 * The Masking tab's panel: the masks as chips with overlay, menu (rename, duplicate, show/hide,
 * invert, add to / subtract from, delete); then the open mask's own tools (brush settings,
 * luminance range, colour-range hint), Refine Edge, and its local adjustments.
 */
@Composable
internal fun MaskPanel(vm: LightCraftViewModel) {
    val masks by vm.masks.collectAsStateWithLifecycle()
    val active by vm.activeMask.collectAsStateWithLifecycle()
    val overlay by vm.maskOverlay.collectAsStateWithLifecycle()
    val brush by vm.brush.collectAsStateWithLifecycle()
    val mask = masks.firstOrNull { it.id == active }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (masks.isEmpty()) {
            Text(
                "Tap Object and then the thing to select; pick Subject, Background or Sky; paint with Brush; add a Linear or Radial gradient; or select by Color or Luminance.",
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
                        color = if (on) Color.White else if (m.visible) Lc.label else Lc.muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) Lc.accent else Lc.raised)
                            .clickable { vm.selectMask(m.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            IconButton(onClick = { vm.setMaskOverlay(!overlay) }) {
                Icon(
                    if (overlay) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    if (overlay) "Hide mask overlay" else "Show mask overlay",
                    tint = if (overlay) Lc.accent else Lc.label,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Mask options", tint = Lc.label) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicateMask(invert = false) })
                    DropdownMenuItem(text = { Text("Duplicate and invert") }, onClick = { menu = false; vm.duplicateMask(invert = true) })
                    DropdownMenuItem(text = { Text(if (mask?.visible != false) "Hide" else "Show") }, onClick = { menu = false; vm.toggleMaskVisible() })
                    DropdownMenuItem(text = { Text("Invert") }, onClick = { menu = false; vm.maskCommand("mask.invert") })
                    HorizontalDivider()
                    partKinds.forEach { (kind, label) ->
                        DropdownMenuItem(text = { Text("Add $label") }, onClick = { menu = false; vm.addComponent("add", kind) })
                    }
                    HorizontalDivider()
                    partKinds.forEach { (kind, label) ->
                        DropdownMenuItem(text = { Text("Subtract $label") }, onClick = { menu = false; vm.addComponent("subtract", kind) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Delete", color = Color(0xFFFF6B6B)) }, onClick = { menu = false; vm.maskCommand("mask.delete") })
                }
            }
        }
        if (mask == null) return@Column
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 4.dp),
        ) {
            when (mask.kind) {
                "brush" -> {
                    Hint("Paint on the photo with one finger; pinch with two to zoom.")
                    Toggle("Erase", brush.erase, Modifier.fillMaxWidth()) { vm.setBrush(brush.copy(erase = it)) }
                    LrSlider("Size", brush.size * 100f, 0.5f, 30f, 4f, 1, "%", { vm.setBrush(brush.copy(size = it / 100f)) }, {}, { vm.setBrush(brush.copy(size = 0.04f)) })
                    LrSlider("Feather", brush.feather, 0f, 100f, 50f, 0, "", { vm.setBrush(brush.copy(feather = it)) }, {}, { vm.setBrush(brush.copy(feather = 50f)) })
                    LrSlider("Flow", brush.flow, 1f, 100f, 100f, 0, "", { vm.setBrush(brush.copy(flow = it)) }, {}, { vm.setBrush(brush.copy(flow = 100f)) })
                }
                "object" -> {
                    val include by vm.objectInclude.collectAsStateWithLifecycle()
                    Hint("Tap the photo to add more of the object, or switch to Leave out and tap what to take away.")
                    Chips(listOf("add" to "Add", "out" to "Leave out"), if (include) "add" else "out") { vm.setObjectInclude(it == "add") }
                }
                "radial" -> Hint("Drag the centre to move it, the edge handles to resize.")
                "linear" -> Hint("Drag an end to set where the gradient starts and ends, the middle to move it.")
                "colorRange" -> {
                    Hint("Tap the photo to pick a colour; each tap adds one (up to five).")
                    val shape = mask.shape
                    if (shape != null) {
                        LrSlider(
                            "Range", shape.optDouble("refine", 50.0).toFloat(), 0f, 100f, 50f, 0, "",
                            onChange = { vm.moveShape(JSONObject(shape.toString()).put("refine", it.toDouble()), done = false) },
                            onDone = { vm.moveShape(JSONObject(shape.toString()), done = true) },
                            onReset = { vm.moveShape(JSONObject(shape.toString()).put("refine", 50.0), done = true) },
                        )
                    }
                }
                "luminanceRange" -> {
                    val shape = mask.shape
                    if (shape != null) {
                        val lo = (shape.optDouble("lo", 0.6) * 100).toFloat()
                        val hi = (shape.optDouble("hi", 1.0) * 100).toFloat()
                        LrSlider(
                            "Darkest", lo, 0f, 100f, 60f, 0, "",
                            onChange = { vm.moveShape(JSONObject(shape.toString()).put("lo", (minOf(it, hi) / 100.0)), done = false) },
                            onDone = { vm.moveShape(JSONObject(shape.toString()), done = true) },
                            onReset = { vm.moveShape(JSONObject(shape.toString()).put("lo", 0.6), done = true) },
                        )
                        LrSlider(
                            "Brightest", hi, 0f, 100f, 100f, 0, "",
                            onChange = { vm.moveShape(JSONObject(shape.toString()).put("hi", (maxOf(it, lo) / 100.0)), done = false) },
                            onDone = { vm.moveShape(JSONObject(shape.toString()), done = true) },
                            onReset = { vm.moveShape(JSONObject(shape.toString()).put("hi", 1.0), done = true) },
                        )
                    }
                }
            }
            LrSlider("Refine Edge", mask.refine, 0f, 100f, 0f, 0, "", { vm.setRefine(it) }, vm::finishInteraction, { vm.setRefine(0f); vm.finishInteraction() })
            Tools.local.forEach { s ->
                LrSlider(
                    s.label, mask.adjust[s.key] ?: 0f, s.min, s.max, 0f, s.decimals, if (s.key == "exposure") "EV" else "",
                    onChange = { vm.setMaskValue(s.key, it) },
                    onDone = vm::finishInteraction,
                    onReset = { vm.setMaskValue(s.key, 0f); vm.finishInteraction() },
                )
            }
        }
    }
    if (renaming && mask != null) {
        var name by remember(mask.id) { mutableStateOf(mask.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename mask") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = false; vm.renameMask(name) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 13.sp, color = Lc.muted, modifier = Modifier.padding(vertical = 4.dp))
}

package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.MaskInfo
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos

import kotlin.math.sin

/**
 * The selected mask's gradients on the photo ([fit]: where the photo sits on screen), with
 * handles to drag: a radial's centre moves it and its four edge handles resize it (snapping to a
 * circle when nearly round); a linear's two ends move each end and its middle moves both. Touches
 * that miss a handle go on to the photo.
 */
@Composable
internal fun GradientHandles(vm: LightCraftViewModel, mask: MaskInfo, fit: Rect) {
    val aspect = remember(mask.id) { vm.maskAspect() }
    // the gradient parts of the mask, with their shapes
    val parts = mask.components.withIndex().filter { (_, c) -> (c.kind == "radial" || c.kind == "linear") && c.shape != null }
    if (parts.isEmpty()) return
    var drag by remember(mask.id) { mutableStateOf<JSONObject?>(null) }
    var dragPart by remember(mask.id) { mutableStateOf(-1) }
    // what is drawn: each part's handles (screen) and outline (screen)
    val drawn = parts.map { (i, c) ->
        val shape = if (i == dragPart && drag != null) drag!! else c.shape!!
        val handles = toScreen(vm, mask.id, fit, handlePoints(shape, aspect))
        val outline = if (c.kind == "radial") toScreen(vm, mask.id, fit, radialOutline(shape, aspect)) else emptyList()
        Triple(i, shape, handles to outline)
    }
    val current by rememberUpdatedState(drawn)
    val fitNow by rememberUpdatedState(fit)
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(mask.id) {
                val grab = 30.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // the nearest handle within reach, or let the touch through
                    var best: Triple<Int, JSONObject, Int>? = null
                    var bestD = grab
                    current.forEach { (part, shape, ho) ->
                        ho.first.forEachIndexed { h, p ->
                            val d = (p - down.position).getDistance()
                            if (d < bestD) { bestD = d; best = Triple(part, shape, h) }
                        }
                    }
                    val (part, start, handle) = best ?: return@awaitEachGesture
                    down.consume()
                    val from = toMask(vm, mask.id, fitNow, down.position) ?: return@awaitEachGesture
                    dragPart = part
                    var last: JSONObject? = null
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        if (c.positionChange() == Offset.Zero) continue
                        c.consume()
                        val at = toMask(vm, mask.id, fitNow, c.position) ?: continue
                        val moved = movedShape(start, handle, from, at, aspect)
                        drag = moved
                        last = moved
                        vm.moveComponent(part, moved, done = false)
                    }
                    last?.let { vm.moveComponent(part, it, done = true) }
                    drag = null
                    dragPart = -1
                }
            },
    ) {
        drawn.forEach { (_, shape, ho) -> drawGradient(shape.optString("kind"), ho.first, ho.second) }
    }
}

/** One gradient on screen: a radial's outline or a linear's three lines, and its handles. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGradient(kind: String, handles: List<Offset>, outline: List<Offset>) {
    val white = Color.White
    val shadow = Color.Black.copy(alpha = 0.45f)
    if (outline.size > 2) {
        val path = Path().apply {
            moveTo(outline[0].x, outline[0].y)
            outline.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, shadow, style = Stroke(3.dp.toPx()))
        drawPath(path, white, style = Stroke(1.5.dp.toPx()))
    }
    if (kind == "linear" && handles.size >= 2) {
        // three lines across the photo: full effect, middle, none
        val a = handles[0]
        val b = handles[1]
        val dir = b - a
        val len = dir.getDistance().coerceAtLeast(1f)
        val n = Offset(-dir.y / len, dir.x / len) * (size.width + size.height)
        listOf(a, (a + b) / 2f, b).forEachIndexed { k, p ->
            drawLine(shadow, p - n, p + n, strokeWidth = 3.dp.toPx())
            drawLine(white.copy(alpha = if (k == 1) 0.6f else 0.95f), p - n, p + n, strokeWidth = 1.5.dp.toPx())
        }
    }
    handles.forEachIndexed { i, h ->
        val main = i == 0 || (kind == "linear" && i == 2)
        val r = (if (main) 9.dp else 7.dp).toPx()
        drawCircle(shadow, r + 1.5.dp.toPx(), h)
        drawCircle(if (main) Lc.accent else white, r, h)
        drawCircle(white, r, h, style = Stroke(2.dp.toPx()))
    }
}

/** Radii are in long-edge units: normalized x spans `lx`, y spans `ly` per unit. */
private fun longNorm(aspect: Double): Pair<Double, Double> = if (aspect >= 1.0) 1.0 to aspect else (1.0 / aspect) to 1.0

private fun pointOf(o: JSONObject?, key: String): Offset? =
    o?.optJSONObject(key)?.let { Offset(it.optDouble("x", 0.5).toFloat(), it.optDouble("y", 0.5).toFloat()) }

/** A gradient's handles in mask coordinates: a radial's centre then its four edge points; a linear's start, end and middle. */
private fun handlePoints(s: JSONObject, aspect: Double): List<Offset> = when (s.optString("kind")) {
    "radial" -> {
        val c = pointOf(s, "center")
        if (c == null) emptyList() else {
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
    }
    "linear" -> {
        val a = pointOf(s, "start")
        val b = pointOf(s, "end")
        if (a == null || b == null) emptyList() else listOf(a, b, Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f))
    }
    else -> emptyList()
}

private fun radialOutline(s: JSONObject, aspect: Double): List<Offset> {
    val c = pointOf(s, "center") ?: return emptyList()
    val rx = s.optDouble("rx", 0.2)
    val ry = s.optDouble("ry", 0.2)
    val a = Math.toRadians(s.optDouble("angle", 0.0))
    val (lx, ly) = longNorm(aspect)
    return (0 until 64).map { i ->
        val t = i / 64.0 * 2 * Math.PI
        val x = rx * cos(t)
        val y = ry * sin(t)
        Offset((c.x + (x * cos(a) - y * sin(a)) * lx).toFloat(), (c.y + (x * sin(a) + y * cos(a)) * ly).toFloat())
    }
}

/**
 * [start] with handle [handle] dragged from [from] to [to] (mask coordinates): a radial's centre
 * moves it, an edge sets that radius (a circle when nearly equal); a linear's end moves that end,
 * its middle moves both.
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
            var rx = s.optDouble("rx", 0.2)
            var ry = s.optDouble("ry", 0.2)
            if (handle == 1 || handle == 3) rx = abs(dx * cos(a) + dy * sin(a)).coerceAtLeast(0.01)
            else ry = abs(-dx * sin(a) + dy * cos(a)).coerceAtLeast(0.01)
            if (abs(rx - ry) / maxOf(rx, ry) < 0.05) { if (handle == 1 || handle == 3) ry = rx else rx = ry }
            s.put("rx", rx).put("ry", ry)
        }
        "linear" -> {
            val a = pointOf(start, "start") ?: return s
            val b = pointOf(start, "end") ?: return s
            val d = Offset(to.x - from.x, to.y - from.y)
            when (handle) {
                0 -> put("start", a + d)
                1 -> put("end", b + d)
                else -> { put("start", a + d); put("end", b + d) }
            }
        }
    }
    return s
}

private fun toScreen(vm: LightCraftViewModel, mask: Int, fit: Rect, pts: List<Offset>): List<Offset> {
    if (pts.isEmpty()) return emptyList()
    val flat = FloatArray(pts.size * 2) { i -> if (i % 2 == 0) pts[i / 2].x else pts[i / 2].y }
    val uv = vm.maskToView(mask, flat)
    return (0 until uv.size / 2).mapNotNull { i ->
        val u = uv[2 * i]
        val v = uv[2 * i + 1]
        if (u.isFinite() && v.isFinite()) Offset(fit.left + u * fit.width, fit.top + v * fit.height) else null
    }
}

private fun toMask(vm: LightCraftViewModel, mask: Int, fit: Rect, p: Offset): Offset? {
    val u = (p.x - fit.left) / fit.width.coerceAtLeast(1f)
    val v = (p.y - fit.top) / fit.height.coerceAtLeast(1f)
    val m = vm.viewToMask(mask, floatArrayOf(u, v))
    return if (m.size >= 2 && m[0].isFinite() && m[1].isFinite()) Offset(m[0], m[1]) else null
}


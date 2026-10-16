package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.storyteller.lightcraft.android.CurvePoint
import kotlin.math.abs
import kotlin.math.hypot

private data class Channel(val id: String, val label: String, val color: Color)

private val channels = listOf(
    Channel("master", "RGB", Color(0xFFF2F2F2)),
    Channel("red", "Red", Color(0xFFFF5A5A)),
    Channel("green", "Green", Color(0xFF4CD964)),
    Channel("blue", "Blue", Color(0xFF4A8CFF)),
)

/**
 * The point tone curve: a diagonal on a 4×4 grid. Tap to add a point, drag a point up or down (and
 * along, between its neighbours), drag an inner point off the grid to remove it. RGB or one channel.
 * [onChange] gets every move (live preview), [onDone] the end of the gesture (one undo step).
 */
@Composable
fun CurveEditor(
    curves: Map<String, List<CurvePoint>>,
    onChange: (String, List<CurvePoint>) -> Unit,
    onDone: () -> Unit,
    onReset: (String) -> Unit,
) {
    var channel by remember { mutableStateOf(channels.first()) }
    val points = curves[channel.id] ?: listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    val latest by rememberUpdatedState(points)
    val change by rememberUpdatedState(onChange)
    val ch by rememberUpdatedState(channel)

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            channels.forEach { c ->
                val on = c == channel
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (on) c.color.copy(alpha = 0.25f) else Color.Transparent)
                        .border(1.5.dp, if (on) c.color else c.color.copy(alpha = 0.45f), CircleShape)
                        .clickable { channel = c },
                    contentAlignment = Alignment.Center,
                ) { Text(if (c.id == "master") "RGB" else c.label.take(1), fontSize = if (c.id == "master") 9.sp else 12.sp, color = c.color) }
            }
            Box(Modifier.weight(1f))
            Text(
                "Reset",
                fontSize = 14.sp,
                color = Lc.accent,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onReset(channel.id) }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Canvas(
            Modifier
                .padding(top = 10.dp)
                .widthIn(max = 260.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        fun norm(o: Offset) = CurvePoint((o.x / w).coerceIn(0f, 1f), 1f - o.y / h)
                        var pts = latest.sortedBy { it.x }.toMutableList()
                        val touch = 28.dp.toPx()
                        var index = pts.indices.minByOrNull { i -> hypot(pts[i].x * w - down.position.x, (1f - pts[i].y) * h - down.position.y) }
                            ?.takeIf { i -> hypot(pts[i].x * w - down.position.x, (1f - pts[i].y) * h - down.position.y) < touch }
                        if (index == null) {
                            val p = norm(down.position).let { it.copy(y = it.y.coerceIn(0f, 1f)) }
                            pts.add(p)
                            pts.sortBy { it.x }
                            index = pts.indexOf(p)
                            change(ch.id, pts.toList())
                        }
                        var i: Int = index ?: 0
                        var removed = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            if (c.positionChange() == Offset.Zero) continue
                            c.consume()
                            val n = norm(c.position)
                            val inner = i > 0 && i < pts.size - 1
                            if (inner && (n.y < -0.12f || n.y > 1.12f)) {
                                if (!removed) {
                                    pts.removeAt(i)
                                    removed = true
                                    change(ch.id, pts.toList())
                                }
                                continue
                            }
                            if (removed) continue
                            val lo = if (i == 0) 0f else pts[i - 1].x + 0.01f
                            val hi = if (i == pts.size - 1) 1f else pts[i + 1].x - 0.01f
                            val x = when (i) {
                                0 -> if (pts.size > 1) n.x.coerceIn(0f, minOf(hi, 0.9f)) else 0f
                                pts.size - 1 -> n.x.coerceIn(maxOf(lo, 0.1f), 1f)
                                else -> n.x.coerceIn(lo, hi)
                            }
                            pts[i] = CurvePoint(x, n.y.coerceIn(0f, 1f))
                            change(ch.id, pts.toList())
                        }
                        onDone()
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            drawRect(Color(0xFF151515))
            val grid = Color(0xFF3C3C3C)
            for (k in 1..3) {
                drawLine(grid, Offset(w * k / 4f, 0f), Offset(w * k / 4f, h), 1f)
                drawLine(grid, Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1f)
            }
            drawRect(grid, style = Stroke(1f))
            drawLine(Color(0xFF555555), Offset(0f, h), Offset(w, 0f), 1f)
            val sorted = points.sortedBy { it.x }
            val path = Path()
            val steps = 96
            for (s in 0..steps) {
                val x = s / steps.toFloat()
                val y = evaluate(sorted, x)
                val o = Offset(x * w, (1f - y) * h)
                if (s == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, channel.color, style = Stroke(2.dp.toPx()))
            sorted.forEach { p ->
                val o = Offset(p.x * w, (1f - p.y) * h)
                drawCircle(Color(0xFF151515), 7.dp.toPx(), o)
                drawCircle(channel.color, 7.dp.toPx(), o, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** Monotone cubic (Fritsch–Carlson) through the points, flat outside them: the drawn curve. */
private fun evaluate(p: List<CurvePoint>, x: Float): Float {
    if (p.isEmpty()) return x
    if (p.size == 1 || x <= p.first().x) return p.first().y.coerceIn(0f, 1f)
    if (x >= p.last().x) return p.last().y.coerceIn(0f, 1f)
    val n = p.size
    val d = FloatArray(n - 1) { i -> (p[i + 1].y - p[i].y) / (p[i + 1].x - p[i].x).coerceAtLeast(1e-4f) }
    val m = FloatArray(n) { i ->
        when (i) {
            0 -> d[0]
            n - 1 -> d[n - 2]
            else -> if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
    }
    for (i in 0 until n - 1) {
        if (abs(d[i]) < 1e-6f) {
            m[i] = 0f
            m[i + 1] = 0f
        }
    }
    val k = (0 until n - 1).first { x <= p[it + 1].x }
    val hh = (p[k + 1].x - p[k].x).coerceAtLeast(1e-4f)
    val t = (x - p[k].x) / hh
    val t2 = t * t
    val t3 = t2 * t
    val y = (2 * t3 - 3 * t2 + 1) * p[k].y + (t3 - 2 * t2 + t) * hh * m[k] + (-2 * t3 + 3 * t2) * p[k + 1].y + (t3 - t2) * hh * m[k + 1]
    return y.coerceIn(0f, 1f)
}

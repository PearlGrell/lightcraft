package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A colour-grading wheel, as the desktop's: hue around the rim, saturation from the grey centre
 * out, a ring puck where the grade sits. Drag the puck anywhere on the wheel (relative, so it
 * never jumps); double-tap clears it to the centre. [hue] is 0–360°, [sat] 0..[satMax].
 */
@Composable
fun ColorWheel(
    label: String,
    hue: Float,
    sat: Float,
    satMax: Float,
    size: Dp,
    onChange: (hue: Float, sat: Float) -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val h by rememberUpdatedState(hue)
    val s by rememberUpdatedState(sat)
    val change by rememberUpdatedState(onChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(size)
                .semantics {
                    contentDescription = "$label wheel"
                    stateDescription = "Hue ${hue.toInt()}°, saturation ${sat.toInt()}"
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
                .pointerInput(Unit) {
                    var p = Offset.Zero
                    detectDragGestures(
                        onDragStart = {
                            val r = this.size.width / 2f
                            val a = Math.toRadians(-h.toDouble())
                            val d = (s / satMax).coerceIn(0f, 1f) * r
                            p = Offset(r + d * cos(a).toFloat(), r + d * sin(a).toFloat())
                        },
                        onDragEnd = onDone,
                        onDragCancel = onDone,
                    ) { c, delta ->
                        c.consume()
                        val r = this.size.width / 2f
                        // fine near the centre: half speed, so small grades are easy to set
                        p += delta * 0.6f
                        var dx = p.x - r
                        var dy = p.y - r
                        val dist = hypot(dx, dy)
                        if (dist > r) { dx = dx / dist * r; dy = dy / dist * r; p = Offset(r + dx, r + dy) }
                        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        val newHue = ((360f - angle) % 360f + 360f) % 360f
                        val newSat = (hypot(dx, dy) / r * satMax).coerceIn(0f, satMax)
                        change(newHue, newSat)
                    }
                },
        ) {
            val r = this.size.width / 2f
            val c = Offset(r, r)
            // hue around the rim (counter-clockwise from the right, as the angle the puck reports)
            val hues = List(13) { i -> Color.hsv(((360 - i * 30) % 360).toFloat(), 0.75f, 0.85f) }
            drawCircle(Brush.sweepGradient(hues, c), r, c)
            // fading to the neutral centre
            drawCircle(Brush.radialGradient(listOf(Color(0xFF8A8A8A), Color(0x008A8A8A)), c, r), r, c)
            drawCircle(Lc.chrome.copy(alpha = 0.18f), r, c)
            drawCircle(Lc.buttonBorder, r, c, style = Stroke(1.dp.toPx()))
            // crosshair at the centre
            val t = 4.dp.toPx()
            drawLine(Lc.dim, Offset(r - t, r), Offset(r + t, r), 1.dp.toPx())
            drawLine(Lc.dim, Offset(r, r - t), Offset(r, r + t), 1.dp.toPx())
            val a = Math.toRadians(-hue.toDouble())
            val d = (sat / satMax).coerceIn(0f, 1f) * (r - 2.dp.toPx())
            val p = Offset(r + d * cos(a).toFloat(), r + d * sin(a).toFloat())
            drawCircle(Lc.chrome, 6.dp.toPx(), p, style = Stroke(4.dp.toPx()))
            drawCircle(Lc.pick, 6.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
        Text(label, fontFamily = Inter, fontSize = 12.sp, color = Lc.label, modifier = Modifier.padding(top = 6.dp))
    }
}

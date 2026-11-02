package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

/**
 * A develop slider: label left and value right on one line, a thin grey track under them, a white
 * fill from the default (the centre for ±ranges) to the value, and a round thumb with a white ring.
 * Dragging anywhere on the row scrubs relative to where the finger started, so the value never jumps
 * under the finger; it snaps to the default with a haptic tick; double-tap resets.
 */
@Composable
fun LrSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    default: Float,
    decimals: Int,
    unit: String = "",
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    track: List<Color>? = null,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val range = (max - min).coerceAtLeast(1e-6f)
    Column(
        modifier
            .fillMaxWidth()
            .pointerInput(label) { detectTapGestures(onDoubleTap = { onReset() }) }
            .pointerInput(label, min, max, default) {
                var v = 0f
                detectHorizontalDragGestures(
                    onDragStart = { v = current },
                    onDragEnd = onDone,
                    onDragCancel = onDone,
                ) { c, dx ->
                    c.consume()
                    v = (v + dx / size.width * range).coerceIn(min, max)
                    val snapped = if (abs(v - default) < range * 0.012f) default else v
                    if (snapped == default && current != default) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (snapped != current) change(snapped)
                }
            }
            .padding(vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, color = Lc.text, modifier = Modifier.weight(1f))
            Text(format(value, min, decimals, unit), fontSize = 15.sp, color = Lc.text)
        }
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val y = size.height / 2f
            val x = { t: Float -> (t.coerceIn(min, max) - min) / range * size.width }
            val from = x(default)
            val to = x(value)
            if (track != null && track.size >= 2) {
                // a colour track (Temp, Tint, Saturation, the HSL mixer): the colours are the guide
                drawLine(Brush.horizontalGradient(track), Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            } else {
                drawLine(Lc.track, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
                drawLine(Lc.text, Offset(from, y), Offset(to, y), strokeWidth = 2.5.dp.toPx())
            }
            // a solid white thumb with a soft shadow ring, like Lightroom's
            drawCircle(Color(0x55000000), 9.5.dp.toPx(), Offset(to, y + 0.8.dp.toPx()))
            drawCircle(Color.White, 8.dp.toPx(), Offset(to, y))
        }
    }
}

private fun format(v: Float, min: Float, decimals: Int, unit: String): String {
    val text = String.format(Locale.ROOT, "%.${decimals}f", v)
    return (if (min < 0 && v > 0 && unit != "EV") "+$text" else text) + unit
}

/** Hue (degrees) of each colour band of the HSL mixer and colour grading, by the name in the control id. */
private val BAND_HUES = listOf("red" to 0f, "orange" to 30f, "yellow" to 55f, "green" to 120f, "aqua" to 180f, "blue" to 220f, "purple" to 275f, "magenta" to 310f)

private fun hsv(h: Float, s: Float = 0.85f, v: Float = 0.9f): Color = Color.hsv(((h % 360f) + 360f) % 360f, s, v)

/** The colour track of a develop control, or null for a plain grey slider. */
fun trackFor(id: String): List<Color>? {
    val k = id.lowercase()
    val band = BAND_HUES.firstOrNull { (name, _) -> k.contains(".$name") || k.contains("${name}.") || k.endsWith(name) }?.second
    return when {
        k == "wb.temp" || k.endsWith(".temp") || k.endsWith("temperature") -> listOf(Color(0xFF2F6FE0), Color(0xFFD9D9D9), Color(0xFFE6C23A))
        k == "wb.tint" || k.endsWith(".tint") -> listOf(Color(0xFF3FBF4F), Color(0xFFD9D9D9), Color(0xFFD445C6))
        band != null && k.contains("hue") -> listOf(hsv(band - 35f), hsv(band), hsv(band + 35f))
        band != null && (k.contains("sat") || k.contains("chroma")) -> listOf(Color(0xFF8A8A8A), hsv(band))
        band != null && (k.contains("lum") || k.contains("light")) -> listOf(Color(0xFF1A1A1A), hsv(band, 0.7f, 0.75f), Color(0xFFF5F5F5))
        k.endsWith("saturation") || k.endsWith("vibrance") -> listOf(Color(0xFF8A8A8A), Color(0xFFE5484D), Color(0xFFE6C23A), Color(0xFF3FBF4F), Color(0xFF2F6FE0))
        k.endsWith(".hue") -> listOf(hsv(0f), hsv(60f), hsv(120f), hsv(180f), hsv(240f), hsv(300f), hsv(360f))
        else -> null
    }
}

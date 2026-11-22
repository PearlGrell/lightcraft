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
 * under the finger; within 2 dp of the default it snaps there with a haptic tick; double-tap resets. Values are
 * rounded to [decimals], so a drag lands on 0.05 rather than 0.0473. With [track] colours (hue,
 * saturation, luminance of colour grading) the track is drawn as that gradient instead.
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
                    val width = size.width.coerceAtLeast(1)
                    v = (v + dx / width * range).coerceIn(min, max)
                    // the default catches the thumb within 2 dp of finger travel: a share of the range
                    // (as before) swallowed small values, e.g. ±0.12 EV of exposure
                    val snap = SNAP.toPx() / width * range
                    val snapped = if (abs(v - default) < snap) default else roundTo(v, decimals).coerceIn(min, max)
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
            val to = x(value)
            if (track != null && track.size >= 2) {
                drawLine(Brush.horizontalGradient(track), Offset(0f, y), Offset(size.width, y), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            } else {
                drawLine(Lc.track, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
                drawLine(Lc.text, Offset(x(default), y), Offset(to, y), strokeWidth = 2.5.dp.toPx())
            }
            drawCircle(Lc.thumb, 10.dp.toPx(), Offset(to, y))
            drawCircle(Lc.text, 10.dp.toPx(), Offset(to, y), style = Stroke(1.8.dp.toPx()))
        }
    }
}

/** How close (finger travel) the thumb must come to the default to snap onto it. */
private val SNAP = 2.dp

/** [v] rounded to [decimals] places (the precision the value is shown and stored at). */
internal fun roundTo(v: Float, decimals: Int): Float {
    var scale = 1.0
    repeat(decimals.coerceIn(0, 6)) { scale *= 10.0 }
    return (kotlin.math.round(v * scale) / scale).toFloat()
}

/** Track colours for colour-grading sliders: a hue wheel, grey → [hue] for saturation, dark → light for luminance. */
fun gradingTrack(id: String, hue: Float?): List<Color>? = when {
    id.endsWith(".hue") -> (0..6).map { Color.hsv(it * 60f % 360f, 0.8f, 0.95f) }
    id.endsWith(".sat") -> listOf(Color.hsv(0f, 0f, 0.6f), Color.hsv(((hue ?: 0f) % 360f + 360f) % 360f, 0.9f, 0.95f))
    id.endsWith(".lum") -> listOf(Color(0xFF1E1E1E), Color(0xFFF2F2F2))
    else -> null
}

private fun format(v: Float, min: Float, decimals: Int, unit: String): String {
    val text = String.format(Locale.ROOT, "%.${decimals}f", v)
    return (if (min < 0 && v > 0 && unit != "EV") "+$text" else text) + unit
}

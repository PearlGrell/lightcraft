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
            drawLine(Lc.track, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
            val from = x(default)
            val to = x(value)
            drawLine(Lc.text, Offset(from, y), Offset(to, y), strokeWidth = 2.5.dp.toPx())
            drawCircle(Lc.thumb, 10.dp.toPx(), Offset(to, y))
            drawCircle(Lc.text, 10.dp.toPx(), Offset(to, y), style = Stroke(1.8.dp.toPx()))
        }
    }
}

private fun format(v: Float, min: Float, decimals: Int, unit: String): String {
    val text = String.format(Locale.ROOT, "%.${decimals}f", v)
    return (if (min < 0 && v > 0 && unit != "EV") "+$text" else text) + unit
}

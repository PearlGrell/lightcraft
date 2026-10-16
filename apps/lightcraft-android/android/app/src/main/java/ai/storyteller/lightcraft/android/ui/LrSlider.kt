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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.storyteller.lightcraft.android.Control
import java.util.Locale
import kotlin.math.abs

/**
 * A develop slider in the phone editor's style: label and value on one line, a thin track under
 * them filled from the default (the centre for ±ranges) to the value, and a small round thumb.
 * Dragging anywhere on the row scrubs relative to where the finger started, so the value never jumps
 * under the finger; the value snaps to the default with a haptic tick; double-tap resets.
 */
@Composable
fun LrSlider(c: Control, onChange: (String, Float) -> Unit, onDone: () -> Unit, onReset: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(c)
    val range = (c.max - c.min).coerceAtLeast(1e-6f)
    val changed = abs(c.value - c.default) > range * 1e-4f
    Column(
        modifier
            .fillMaxWidth()
            .pointerInput(c.id) { detectTapGestures(onDoubleTap = { onReset(current.id) }) }
            .pointerInput(c.id) {
                var value = 0f
                detectHorizontalDragGestures(
                    onDragStart = { value = current.value },
                    onDragEnd = onDone,
                    onDragCancel = onDone,
                ) { change, dx ->
                    change.consume()
                    val c0 = current
                    val r = (c0.max - c0.min).coerceAtLeast(1e-6f)
                    value = (value + dx / size.width * r).coerceIn(c0.min, c0.max)
                    val snapped = if (abs(value - c0.default) < r * 0.012f) c0.default else value
                    if (snapped == c0.default && c0.value != c0.default) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (snapped != c0.value) onChange(c0.id, snapped)
                }
            }
            .padding(vertical = 7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(c.label, fontSize = 13.sp, color = if (changed) Lc.text else Lc.label, modifier = Modifier.weight(1f))
            Text(format(c), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (changed) Lc.text else Lc.label)
        }
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val y = size.height / 2f
            val x = { v: Float -> (v - c.min) / range * size.width }
            drawLine(Lc.track, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            val from = x(c.default.coerceIn(c.min, c.max))
            val to = x(c.value.coerceIn(c.min, c.max))
            if (changed) drawLine(Lc.text, Offset(from, y), Offset(to, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            if (c.min < c.default && c.default < c.max) drawCircle(Lc.muted, 2.dp.toPx(), Offset(from, y))
            drawCircle(Lc.text, 7.dp.toPx(), Offset(to, y))
        }
    }
}

private fun format(c: Control): String {
    val text = String.format(Locale.ROOT, "%.${c.decimals}f", c.value)
    return if (c.min < 0 && c.value > 0) "+$text" else text
}

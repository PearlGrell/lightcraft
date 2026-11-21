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
import ai.storyteller.lightcraft.android.Bands
import ai.storyteller.lightcraft.android.Track
import java.util.Locale
import kotlin.math.abs

/**
 * A develop slider: label left and signed value right on one line, a thin track under them (grey,
 * or a colour gradient for [track]: Temp blue → yellow, Tint green → magenta, Hue a rainbow…) and a
 * hollow ring thumb.
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
    track: Track = Track.Plain,
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
            .padding(vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = Lc.label, modifier = Modifier.weight(1f))
            Text(format(value, min, decimals, unit), fontSize = 14.sp, color = Lc.label)
        }
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val knob = 8.dp.toPx()
            val y = size.height / 2f
            // the track runs between the thumb's centres at min and max, so the ring never clips
            val x0 = knob + 1.dp.toPx()
            val x1 = size.width - x0
            val at = (value.coerceIn(min, max) - min) / range * (x1 - x0) + x0
            val colours = trackColours(track)
            if (colours == null) {
                drawLine(Lc.track, Offset(x0, y), Offset(x1, y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            } else {
                drawLine(Brush.horizontalGradient(colours, startX = x0, endX = x1), Offset(x0, y), Offset(x1, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(Lc.knobFill, knob, Offset(at, y))
            drawCircle(Lc.text, knob, Offset(at, y), style = Stroke(1.6.dp.toPx()))
        }
    }
}

/** The colours along a gradient track, left to right; null for a plain grey one. */
internal fun trackColours(track: Track): List<Color>? = when (track) {
    Track.Plain, Track.Centered -> null
    Track.Temp -> listOf(Color(0xFF3F6FD8), Color(0xFF8C9AA6), Color(0xFFE2C63C))
    Track.Tint -> listOf(Color(0xFF3FB34F), Color(0xFF8E8E8E), Color(0xFFC94FC1))
    Track.Rainbow -> rainbow
    is Track.Gradient -> {
        // the engine's grey → colour tracks start grey at the centre, like the video's Vibrance
        val from = Color(track.from)
        listOf(from.copy(alpha = 0.55f), from, Color(track.to))
    }
    // a band's hue shift: from the neighbour below, through the band, to the neighbour above
    is Track.Hue -> listOf(bandColour(track.band - 1), bandColour(track.band), bandColour(track.band + 1))
    // saturation and luminance stay plain grey in the mixer, as in the reference
    is Track.Sat, is Track.Lum -> null
}

/** A mixer band's swatch colour (wrapping around the eight bands). */
internal fun bandColour(i: Int): Color {
    val n = Bands.all.size
    return Color(Bands.all[((i % n) + n) % n].argb)
}

/** Hue 0° → 360°, as the grading rainbow and wheel show it. */
internal val rainbow = listOf(
    Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF4CD964), Color(0xFF34C8E0), Color(0xFF3A66F0), Color(0xFFC04CF0), Color(0xFFFF3B30),
)

private fun format(v: Float, min: Float, decimals: Int, unit: String): String {
    val text = String.format(Locale.ROOT, "%.${decimals}f", v)
    return (if (min < 0 && v > 0 && unit != "EV") "+$text" else text) + unit
}

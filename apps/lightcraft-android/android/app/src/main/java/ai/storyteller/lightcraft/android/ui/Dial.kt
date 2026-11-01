package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** One adjustable value: a develop control, a mask's local adjustment or a tool setting. */
data class DialSpec(
    val key: String,
    val label: String,
    val value: Float,
    val min: Float,
    val max: Float,
    val default: Float,
    val decimals: Int = 0,
    val unit: String = "",
) {
    val range: Float get() = (max - min).coerceAtLeast(1e-6f)
    val changed: Boolean get() = abs(value - default) > range * 1e-4f
    fun text(): String = formatValue(value, min, decimals, unit)
}

/** What a dial or ruler reports back; [key] is the [DialSpec.key]. */
class DialActions(
    val change: (key: String, value: Float) -> Unit,
    val done: () -> Unit,
    val reset: (key: String) -> Unit,
)

private const val START = 135f
private const val SWEEP = 270f

/**
 * A rotary control: a 270° arc with the change from the default drawn in amber, the value in the
 * middle and the label underneath. Drag left or right anywhere on it to turn it (full range over
 * ~280 dp, relative to where the finger starts, so it never jumps); it clicks into the default
 * with a haptic tick; double-tap resets; a tap selects it (the panel then shows a fine ruler).
 * Vertical swipes are left to the panel, so a grid of dials still scrolls.
 */
@Composable
fun Dial(
    spec: DialSpec,
    selected: Boolean,
    actions: DialActions,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    knob: Dp = 50.dp,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(spec)
    val select by rememberUpdatedState(onSelect)
    Column(
        modifier
            .pointerInput(spec.key) { detectTapGestures(onTap = { select() }, onDoubleTap = { actions.reset(current.key) }) }
            .pointerInput(spec.key) {
                var v = 0f
                val travel = 280.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { v = current.value; select() },
                    onDragEnd = actions.done,
                    onDragCancel = actions.done,
                ) { c, dx ->
                    c.consume()
                    val s = current
                    v = (v + dx / travel * s.range).coerceIn(s.min, s.max)
                    val snapped = if (abs(v - s.default) < s.range * 0.012f) s.default else v
                    if (snapped == s.default && s.value != s.default) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (snapped != s.value) actions.change(s.key, snapped)
                }
            }
            .semantics {
                contentDescription = spec.label
                stateDescription = spec.text()
                progressBarRangeInfo = ProgressBarRangeInfo(spec.value, spec.min..spec.max)
                setProgress { target -> actions.change(spec.key, target.coerceIn(spec.min, spec.max)); actions.done(); true }
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(knob), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(knob)) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2f + 1.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                drawArc(Lc.track, START, SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val a0 = angleOf(spec.default, spec)
                val a1 = angleOf(spec.value, spec)
                if (spec.changed) {
                    drawArc(
                        if (selected) Lc.pick else Lc.label,
                        minOf(a0, a1), abs(a1 - a0), false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                val r = arcSize.width / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                // a hairline tick where the default sits (the top for ± controls)
                if (spec.min < spec.default && spec.default < spec.max) {
                    val t = Math.toRadians(a0.toDouble())
                    val inner = r - 5.dp.toPx()
                    val outer = r - 2.5.dp.toPx()
                    drawLine(
                        Lc.dim,
                        Offset(c.x + inner * cos(t).toFloat(), c.y + inner * sin(t).toFloat()),
                        Offset(c.x + outer * cos(t).toFloat(), c.y + outer * sin(t).toFloat()),
                        1.dp.toPx(),
                    )
                }
                val t = Math.toRadians(a1.toDouble())
                val p = Offset(c.x + r * cos(t).toFloat(), c.y + r * sin(t).toFloat())
                drawCircle(Lc.canvas, 4.5.dp.toPx(), p)
                drawCircle(if (selected) Lc.pick else Lc.text, 3.dp.toPx(), p)
            }
            Text(
                compactValue(spec),
                style = Numbers,
                fontFamily = Inter,
                fontSize = if (compactValue(spec).length > 5) 10.sp else 11.5.sp,
                color = if (spec.changed) Lc.text else Lc.dim,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            spec.label,
            fontFamily = Inter,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Lc.text else Lc.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
    }
}

/**
 * The dials of one panel in rows of [columns], scrolling vertically. The selected dial gets a fine
 * ruler above the grid for exact values.
 */
@Composable
fun DialGrid(
    specs: List<DialSpec>,
    selected: String?,
    onSelect: (String?) -> Unit,
    actions: DialActions,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    maxHeight: Dp = 196.dp,
) {
    Column(modifier.fillMaxWidth()) {
        specs.firstOrNull { it.key == selected }?.let { s ->
            ScrubRuler(s, actions, Modifier.padding(horizontal = 18.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp),
        ) {
            specs.chunked(columns.coerceAtLeast(1)).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { s ->
                        Dial(
                            s, s.key == selected, actions,
                            onSelect = { onSelect(s.key) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * A fine ruler for the selected value: ticks slide under a fixed amber index as you drag (the
 * whole range over four widths of travel), the label and exact value above. Double-tap resets.
 */
@Composable
fun ScrubRuler(spec: DialSpec, actions: DialActions, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(spec)
    Column(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label.uppercase(Locale.ROOT), style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = Lc.label, modifier = Modifier.weight(1f))
            Text(spec.text(), style = Numbers, fontSize = 13.sp, color = if (spec.changed) Lc.pick else Lc.text)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(spec.key) { detectTapGestures(onDoubleTap = { actions.reset(current.key) }) }
                .pointerInput(spec.key) {
                    var v = 0f
                    detectHorizontalDragGestures(onDragStart = { v = current.value }, onDragEnd = actions.done, onDragCancel = actions.done) { c, dx ->
                        c.consume()
                        val s = current
                        v = (v - dx / (size.width * 4f) * s.range).coerceIn(s.min, s.max)
                        val snapped = if (abs(v - s.default) < s.range * 0.003f) s.default else v
                        if (snapped == s.default && s.value != s.default) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (snapped != s.value) actions.change(s.key, snapped)
                    }
                },
        ) {
            val cx = size.width / 2f
            val pxPerUnit = size.width * 4f / spec.range
            val step = spec.range / 100f
            // ticks every 1 % of the range, a longer one every 10 %, only those on screen
            val first = floor((spec.value - cx / pxPerUnit - spec.min) / step).toInt().coerceAtLeast(0)
            val last = minOf(100, first + (size.width / (step * pxPerUnit)).toInt() + 2)
            for (i in first..last) {
                val u = spec.min + i * step
                val x = cx + (u - spec.value) * pxPerUnit
                if (x < 0f || x > size.width) continue
                val major = i % 10 == 0
                val h = if (major) size.height * 0.55f else size.height * 0.3f
                drawLine(if (major) Lc.label else Lc.track, Offset(x, size.height - h), Offset(x, size.height), 1.dp.toPx())
            }
            if (spec.min < spec.default && spec.default < spec.max) {
                val x = cx + (spec.default - spec.value) * pxPerUnit
                if (x in 0f..size.width) drawCircle(Lc.dim, 2.dp.toPx(), Offset(x, 3.dp.toPx()))
            }
            drawLine(Lc.pick, Offset(cx, 0f), Offset(cx, size.height), 2.dp.toPx())
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
        Spacer(Modifier.width(1.dp))
    }
}

private fun angleOf(v: Float, s: DialSpec): Float = START + (v.coerceIn(s.min, s.max) - s.min) / s.range * SWEEP

/** The value as the dial's centre shows it: short, signed for ± controls. */
private fun compactValue(s: DialSpec): String {
    val d = if (abs(s.value) >= 1000f) 0 else s.decimals.coerceAtMost(2)
    val text = String.format(Locale.ROOT, "%.${d}f", s.value)
    return if (s.min < 0f && s.value > 0f && text.any { it in '1'..'9' }) "+$text" else text
}

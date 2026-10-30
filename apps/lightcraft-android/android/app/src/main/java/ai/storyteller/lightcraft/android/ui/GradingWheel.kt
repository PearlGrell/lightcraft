package ai.storyteller.lightcraft.android.ui

import ai.storyteller.lightcraft.android.Control
import ai.storyteller.lightcraft.android.LightCraftViewModel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One tonal range of Color Grading, and the control ids behind its wheel. */
internal data class GradeRange(val title: String, val key: String) {
    val hue get() = "grading.$key.hue"
    val sat get() = "grading.$key.sat"
    val lum get() = "grading.$key.lum"
}

/** Colour stops around the hue ring: enough that the banding does not show at this size. */
private const val STOPS = 36

internal val GRADE_RANGES = listOf(
    GradeRange("Shadows", "shadows"),
    GradeRange("Midtones", "midtones"),
    GradeRange("Highlights", "highlights"),
    GradeRange("Global", "global"),
)

/**
 * Color Grading as Lightroom shows it: a hue/saturation wheel per tonal range, where the angle is
 * the hue and the distance from the middle the saturation.
 *
 * The wheel exists because the two are useless apart. `grading.<range>.sat` starts at 0, and the
 * engine drops grading entirely while every range is neutral, so dragging a *Hue* slider on its own
 * cannot change a single pixel — which is what made the panel feel dead. One drag here sets hue and
 * saturation together, in one undo step.
 */
@Composable
internal fun GradingPanel(vm: LightCraftViewModel, all: List<Control>) {
    var selected by remember { mutableIntStateOf(0) }
    val range = GRADE_RANGES.getOrNull(selected) ?: GRADE_RANGES[0]
    val by = { id: String -> all.firstOrNull { it.id == id } }
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(GRADE_RANGES) { r ->
                val on = r == range
                val i = GRADE_RANGES.indexOf(r)
                Text(
                    r.title,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Lc.text else Lc.muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) Lc.raised else Color.Transparent)
                        .clickable { selected = i }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        val hue = by(range.hue)
        val sat = by(range.sat)
        if (hue == null || sat == null) return@Column
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Wheel(
                hue = hue.value,
                sat = sat.value,
                maxSat = sat.max,
                onChange = { h, s -> vm.setControls(mapOf(range.hue to h, range.sat to s), "Color Grading") },
                onDone = vm::finishInteraction,
                onReset = { vm.setControls(mapOf(range.hue to hue.default, range.sat to sat.default), "Color Grading") },
            )
            Column(Modifier.padding(start = 14.dp)) {
                by(range.lum)?.let { l ->
                    LrSlider(
                        l.label, l.value, l.min, l.max, l.default, l.decimals, l.unit,
                        onChange = { vm.setControl(l.id, it) },
                        onDone = vm::finishInteraction,
                        onReset = { vm.resetControl(l.id) },
                        track = trackFor(l.id),
                    )
                }
                Text(
                    "Hue ${hue.value.toInt()}°   Saturation ${sat.value.toInt()}",
                    fontSize = 12.sp,
                    color = Lc.muted,
                )
            }
        }
        // Blending and Balance act across the ranges, so they stay plain sliders below the wheel
        listOf("grading.blending", "grading.balance").mapNotNull(by).forEach { c ->
            Box(Modifier.padding(horizontal = 22.dp)) {
                LrSlider(
                    c.label, c.value, c.min, c.max, c.default, c.decimals, c.unit,
                    onChange = { vm.setControl(c.id, it) },
                    onDone = vm::finishInteraction,
                    onReset = { vm.resetControl(c.id) },
                    track = trackFor(c.id),
                )
            }
        }
    }
}

/**
 * The wheel itself: a hue ring around a saturation radius, with a dot at the current colour.
 * A drag anywhere inside sets both; a double-tap puts them back to neutral.
 */
@Composable
private fun Wheel(
    hue: Float,
    sat: Float,
    maxSat: Float,
    onChange: (Float, Float) -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
) {
    val cb = rememberUpdatedState(onChange)
    val top = maxSat.coerceAtLeast(1f)
    Box(
        Modifier
            .size(118.dp)
            .pointerInput(Unit) {
                // the wheel's own centre and radius, in this node's pixels
                fun report(p: Offset) {
                    val r = size.width / 2f
                    if (r <= 0f) return
                    val dx = p.x - r
                    val dy = p.y - r
                    // screen y grows downwards and 0° points up, so this is measured from -y
                    val deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
                    val h = ((deg % 360f) + 360f) % 360f
                    val s = (hypot(dx.toDouble(), dy.toDouble()).toFloat() / r * top).coerceIn(0f, top)
                    cb.value(h, s)
                }
                detectDragGestures(onDragEnd = onDone, onDragCancel = onDone) { c, _ ->
                    c.consume()
                    report(c.position)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onReset() }, onTap = { onDone() })
            },
    ) {
        Canvas(Modifier.size(118.dp)) {
            val r = size.minDimension / 2f
            val c = Offset(r, r)
            // the hue ring. A sweep gradient starts at 3 o'clock, and the wheel reads 0° at the
            // top, so every stop is turned back a quarter turn to line the colours up with the dot.
            val ring = (0..STOPS).map { i -> Color.hsv((i.toFloat() / STOPS * 360f + 90f) % 360f, 1f, 1f) }
            drawCircle(brush = Brush.sweepGradient(ring, center = c), radius = r, center = c)
            // washed out towards the middle, where saturation is 0
            drawCircle(
                brush = Brush.radialGradient(listOf(Lc.raised, Lc.raised.copy(alpha = 0f)), center = c, radius = r),
                radius = r,
                center = c,
            )
            // the current colour's dot
            val a = Math.toRadians((hue - 90f).toDouble())
            val d = (sat / top).coerceIn(0f, 1f) * r
            val at = Offset(c.x + (cos(a) * d).toFloat(), c.y + (sin(a) * d).toFloat())
            drawCircle(Color.White, radius = 7.dp.toPx(), center = at)
            drawCircle(Color.hsv(hue, (sat / top).coerceIn(0f, 1f), 1f), radius = 5.dp.toPx(), center = at)
        }
    }
}

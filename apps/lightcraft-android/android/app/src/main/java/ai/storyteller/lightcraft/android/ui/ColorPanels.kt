package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.storyteller.lightcraft.android.Bands
import ai.storyteller.lightcraft.android.Control
import ai.storyteller.lightcraft.android.Ranges
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The small grey title at the top of a tool card ("Light", "Mixer - Red", "Color Grading"). */
@Composable
internal fun CardHeader(title: String, modifier: Modifier = Modifier) {
    Text(title, fontSize = 13.sp, color = Lc.cardHeader, modifier = modifier.padding(top = 8.dp, bottom = 2.dp))
}

/**
 * The colour mixer: eight colour rings (the open band has a filled dot), then that band's Hue,
 * Saturation and Luminance. [controls] are the engine's `mixer.<band>.hue|sat|lum` controls.
 */
@Composable
internal fun MixerPanel(
    controls: List<Control>,
    band: Int,
    onBand: (Int) -> Unit,
    onChange: (String, Float) -> Unit,
    onDone: () -> Unit,
    onReset: (String) -> Unit,
) {
    val b = Bands.all.getOrElse(band) { Bands.all.first() }
    Column(Modifier.fillMaxWidth()) {
        CardHeader("Mixer - ${b.label}")
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Bands.all.forEachIndexed { i, x -> BandRing(Color(x.argb), on = i == band, label = x.label) { onBand(i) } }
        }
        listOf("hue" to "Hue", "sat" to "Saturation", "lum" to "Luminance").forEach { (key, label) ->
            val c = controls.firstOrNull { it.id == "mixer.${b.id}.$key" } ?: return@forEach
            LrSlider(
                label, c.value, c.min, c.max, c.default, c.decimals, c.unit,
                onChange = { onChange(c.id, it) },
                onDone = onDone,
                onReset = { onReset(c.id) },
                track = c.track,
            )
        }
    }
}

/** A band's swatch: a coloured ring, with a dot inside when it is the open band. */
@Composable
private fun BandRing(colour: Color, on: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(18.dp).border(1.8.dp, colour, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(9.dp).clip(CircleShape).background(colour))
        }
    }
}

/**
 * Colour grading: Shadows / Midtones / Highlights / Global, a hue × saturation wheel for the open
 * range with a ring puck, its "Hue 205°  Saturation 18" readout and Luminance, then Blending and
 * Balance. [controls] are the engine's `grading.*` controls.
 */
@Composable
internal fun GradingPanel(
    controls: List<Control>,
    range: Int,
    onRange: (Int) -> Unit,
    onWheel: (hueId: String, hue: Float, satId: String, sat: Float) -> Unit,
    onChange: (String, Float) -> Unit,
    onDone: () -> Unit,
    onReset: (String) -> Unit,
) {
    val r = Ranges.all.getOrElse(range) { Ranges.all.first() }
    val hue = controls.firstOrNull { it.id == "grading.${r.id}.hue" }
    val sat = controls.firstOrNull { it.id == "grading.${r.id}.sat" }
    val lum = controls.firstOrNull { it.id == "grading.${r.id}.lum" }
    Column(Modifier.fillMaxWidth()) {
        CardHeader("Color Grading")
        Pills(Ranges.all.map { it.label }, range, onRange, onReset = null, padded = false)
        if (hue != null && sat != null) {
            Text(r.label, fontSize = 15.sp, color = Lc.text, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp, bottom = 6.dp))
            ColorWheel(
                hue = hue.value,
                sat = sat.value / sat.max.coerceAtLeast(1f),
                size = 168.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                onChange = { h, s -> onWheel(hue.id, h, sat.id, (s * sat.max).coerceIn(sat.min, sat.max)) },
                onDone = onDone,
                onReset = { onReset(hue.id); onReset(sat.id) },
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)) {
                Text("Hue  ${hue.value.roundToInt()}°", fontSize = 14.sp, color = Lc.label)
                Text("Saturation  ${sat.value.roundToInt()}", fontSize = 14.sp, color = Lc.label, modifier = Modifier.padding(start = 28.dp))
            }
        }
        val rest = listOfNotNull(lum?.let { it to "Luminance" }) +
            controls.filter { it.id == "grading.blending" || it.id == "grading.balance" }.map { it to it.label }
        rest.forEach { (c, label) ->
            LrSlider(
                label, c.value, c.min, c.max, c.default, c.decimals, c.unit,
                onChange = { onChange(c.id, it) },
                onDone = onDone,
                onReset = { onReset(c.id) },
                track = c.track,
            )
        }
    }
}

/**
 * A hue × saturation wheel: hue is the angle (0° = red on the right, counter-clockwise, as in the
 * reference), saturation the distance from the white centre. Drag the ring puck anywhere; a drag
 * that starts outside the wheel still grabs it. Double-tap the centre resets.
 */
@Composable
internal fun ColorWheel(
    hue: Float,
    sat: Float,
    size: Dp,
    onChange: (hue: Float, sat: Float) -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val reset by rememberUpdatedState(onReset)
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = "Colour wheel" }
            .pointerInput(Unit) {
                var lastTap = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val radius = minOf(c.x, c.y)
                    fun pick(p: Offset) {
                        val dx = p.x - c.x
                        val dy = c.y - p.y
                        var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                        if (deg < 0f) deg += 360f
                        val s = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
                        change(deg.roundToInt().toFloat() % 360f, s)
                    }
                    val now = down.uptimeMillis
                    if (now - lastTap < 300) {
                        reset()
                        lastTap = 0L
                        return@awaitEachGesture
                    }
                    lastTap = now
                    var moved = false
                    pick(down.position)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val p = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) break
                        if (p.positionChange() != Offset.Zero) {
                            moved = true
                            pick(p.position)
                            p.consume()
                        }
                    }
                    if (moved) lastTap = 0L
                    done()
                }
            },
    ) {
        val c = center
        val radius = minOf(c.x, c.y)
        // Compose sweeps clockwise from 3 o'clock on screen: list the hues backwards for a
        // counter-clockwise wheel
        drawCircle(Brush.sweepGradient(rainbow.reversed(), c), radius, c)
        // white only near the centre (saturation 0), the rim fully saturated, as in the reference
        drawCircle(
            Brush.radialGradient(0f to Color.White, 0.3f to Color.White.copy(alpha = 0.45f), 0.75f to Color.White.copy(alpha = 0f), center = c, radius = radius),
            radius,
            c,
        )
        val a = Math.toRadians(hue.toDouble())
        val d = sat.coerceIn(0f, 1f) * radius
        val puck = Offset(c.x + (cos(a) * d).toFloat(), c.y - (sin(a) * d).toFloat())
        drawCircle(Color(0x33000000), 7.dp.toPx(), puck)
        drawCircle(Color(0xFF1A1A1A), 6.dp.toPx(), puck, style = Stroke(2.dp.toPx()))
    }
}

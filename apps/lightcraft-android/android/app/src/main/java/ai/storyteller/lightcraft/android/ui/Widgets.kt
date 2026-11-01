package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.storyteller.lightcraft.android.Track
import kotlin.math.abs

// The desktop's widgets (`crates/ui-egui/src/widgets.rs`) for touch: the same geometry, colours
// and behaviour, sized for a finger.

/** The desktop's eight colour bands (`BAND_COLORS`): red, orange, yellow, green, aqua, blue, purple, magenta. */
val BandColors = listOf("#aa0000", "#bb6600", "#aaaa00", "#009900", "#00aaaa", "#0044cc", "#7722aa", "#aa0077").map(::hex)

fun hex(s: String): Color {
    val v = s.removePrefix("#").toLongOrNull(16) ?: return Lc.track
    return Color(0xFF000000 or (v and 0xFFFFFF))
}

private fun mul(c: Color, f: Float) = Color(c.red * f, c.green * f, c.blue * f, c.alpha)

/** The gradient a track is painted with (`track_stops`), or null for a plain grey one. */
fun trackStops(t: Track): List<Color>? = when (t.kind) {
    "temp" -> listOf("#193961", "#47566a", "#60666d", "#70705b", "#737339", "#75742a").map(::hex)
    "tint" -> listOf("#246123", "#2c552c", "#394739", "#4a3d4c", "#6b336b", "#872c87").map(::hex)
    "gradient" -> listOf(hex(t.from ?: "#5a5a5a"), hex(t.to ?: "#5a5a5a"))
    "hue" -> {
        val i = t.band
        val prev = BandColors[(i + 7) % 8]
        val next = BandColors[(i + 1) % 8]
        val me = BandColors[i]
        listOf(mul(prev, 0.7f), mul(lerp(prev, me, 0.5f), 0.7f), mul(me, 0.7f), mul(lerp(me, next, 0.5f), 0.7f), mul(next, 0.7f))
    }
    "sat" -> {
        val g = hex("#454343")
        listOf(g, lerp(g, BandColors[t.band], 0.6f), BandColors[t.band])
    }
    "lum" -> {
        val c = BandColors[t.band]
        listOf(mul(c, 0.2f), mul(c, 0.55f), lerp(c, Color.White, 0.35f), lerp(c, Color.White, 0.6f))
    }
    "rainbow" -> listOf("#aa0000", "#aaaa00", "#009900", "#00aaaa", "#0044cc", "#aa0077", "#aa0000").map(::hex)
    else -> null
}

/** One value as the slider's spec describes it (an engine control, a mask adjustment, a tool setting). */
data class SliderSpec(
    val key: String,
    val label: String,
    val value: Float,
    val min: Float,
    val max: Float,
    val default: Float,
    val decimals: Int = 0,
    val unit: String = "",
    val track: Track = Track(),
) {
    val range: Float get() = (max - min).coerceAtLeast(1e-6f)
    val changed: Boolean get() = abs(value - default) > range * 1e-4f
    fun text(): String = formatValue(value, min, decimals, unit)
}

/** What a slider reports: a new value while dragging, the end of a drag (one undo step), a reset. */
class SliderActions(
    val change: (key: String, value: Float) -> Unit,
    val done: () -> Unit,
    val reset: (key: String) -> Unit,
)

internal fun formatValue(v: Float, min: Float, decimals: Int, unit: String): String {
    val text = String.format(java.util.Locale.ROOT, "%.${decimals.coerceIn(0, 3)}f", v)
    val signed = if (min < 0 && v > 0 && text.any { it in '1'..'9' }) "+$text" else text
    return when (unit) {
        "", "EV" -> signed
        "°", "%" -> "$signed$unit"
        else -> "$signed $unit"
    }
}

/**
 * The desktop's develop slider: name left and value right on one line, a 2-point track under them
 * (grey, or the control's gradient), a hollow ring thumb with a gap in the track around it. Drag
 * anywhere on the row: it moves relative to where the finger started (so it never jumps), at
 * full range over the track's width; it clicks into the default with a haptic tick; double-tap
 * resets, as double-clicking the name does on the desktop.
 */
@Composable
fun LcSlider(spec: SliderSpec, actions: SliderActions, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(spec)
    var active by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(spec.key, enabled) { if (enabled) detectTapGestures(onDoubleTap = { actions.reset(current.key) }) }
            .pointerInput(spec.key, enabled) {
                if (!enabled) return@pointerInput
                var v = 0f
                val pad = 18.dp.toPx() * 2
                detectHorizontalDragGestures(
                    onDragStart = { v = current.value; active = true },
                    onDragEnd = { active = false; actions.done() },
                    onDragCancel = { active = false; actions.done() },
                ) { c, dx ->
                    c.consume()
                    val s = current
                    v = (v + dx / (size.width - pad).coerceAtLeast(1f) * s.range).coerceIn(s.min, s.max)
                    val snapped = if (abs(v - s.default) < s.range * 0.01f) s.default else v
                    if (snapped == s.default && s.value != s.default) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (snapped != s.value) actions.change(s.key, snapped)
                }
            }
            .semantics {
                contentDescription = spec.label
                stateDescription = spec.text()
                progressBarRangeInfo = ProgressBarRangeInfo(spec.value, spec.min..spec.max)
                setProgress { t -> actions.change(spec.key, t.coerceIn(spec.min, spec.max)); actions.done(); true }
            }
            .padding(horizontal = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, fontFamily = Inter, fontSize = 13.sp, color = if (enabled) Lc.label else Lc.disabled, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(spec.text(), style = Numbers, fontSize = 13.sp, color = if (enabled) Lc.text else Lc.disabled)
        }
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val y = size.height / 2f
            val h = 1.dp.toPx()
            val x = (spec.value.coerceIn(spec.min, spec.max) - spec.min) / spec.range * size.width
            val stops = trackStops(spec.track)
            if (stops == null) {
                drawRect(Lc.track, Offset(0f, y - h), Size(size.width, h * 2))
            } else {
                val steps = 48
                val n = (stops.size - 1).coerceAtLeast(1)
                for (i in 0 until steps) {
                    val a = i / steps.toFloat()
                    val seg = (a * n).toInt().coerceAtMost(n - 1)
                    val local = a * n - seg
                    val c = lerp(stops[seg], stops[(seg + 1).coerceAtMost(stops.size - 1)], local)
                    drawRect(c, Offset(size.width * a, y - h), Size(size.width / steps + 0.5f, h * 2))
                }
            }
            val ring = 7.dp.toPx()
            // the gap around the hollow thumb, then the thumb
            drawRect(Lc.chrome, Offset(x - ring - 2.dp.toPx(), y - 3.dp.toPx()), Size(ring * 2 + 4.dp.toPx(), 6.dp.toPx()))
            drawCircle(Lc.chrome, ring, Offset(x, y))
            drawCircle(if (!enabled) Lc.disabled else if (active) Lc.thumbActive else Lc.thumb, ring, Offset(x, y), style = Stroke(2.dp.toPx()))
        }
    }
}

/** A list of sliders (one per spec), as a panel section shows them. */
@Composable
fun SliderList(specs: List<SliderSpec>, actions: SliderActions) {
    Column(Modifier.fillMaxWidth()) { specs.forEach { LcSlider(it, actions) } }
}

/**
 * A panel section's header (`section_header`): a chevron, the title in semibold 14, 52 points
 * tall; tap to open or close. [trailing] sits at its right end.
 */
@Composable
fun SectionHeader(title: String, open: Boolean, onToggle: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onToggle).padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LcIcon(if (open) DeskIcon.ChevronDown else DeskIcon.ChevronRight, Lc.label, 16.dp)
        Spacer(Modifier.width(10.dp))
        Text(title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** The divider between panel sections (the desktop's 1-point line in the divider token). */
@Composable
fun SectionDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
}

/**
 * A flyout row (the desktop's Curve / Color Mixer rows): an inset bar with an icon, a semibold
 * name and a disclosure arrow; it opens its content below it.
 */
@Composable
fun FlyoutRow(icon: DeskIcon, title: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Lc.inset)
            .clickable(onClick = onToggle)
            .height(40.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).border(1.dp, Lc.buttonBorder, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
            LcIcon(icon, Lc.label, 16.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f))
        LcIcon(if (open) DeskIcon.ChevronDown else DeskIcon.ChevronLeft, Lc.label, 14.dp)
    }
}

/** The desktop's bordered button (Auto, B&W, Reset…): semibold 12 on the button token, 1-point border. */
@Composable
fun LcButton(text: String, modifier: Modifier = Modifier, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        color = when {
            !enabled -> Lc.disabled
            on -> Color.White
            else -> Lc.text
        },
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) Lc.accent else Lc.button)
            .border(1.dp, if (on) Lc.accent else Lc.buttonBorder, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** An icon button as on the desktop's bars and tool rail: the active one on the tool-active fill. */
@Composable
fun LcIconButton(
    icon: DeskIcon,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tint: Color = if (active) Lc.text else Lc.icon,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Lc.toolActive else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        LcIcon(icon, if (enabled) tint else Lc.disabled, iconSize)
    }
}

/** A dropdown-looking value ("Profile  Color ⌄"): dim name, value in text colour, a chevron. */
@Composable
fun ValueRow(name: String, value: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontFamily = Inter, fontSize = 13.sp, color = Lc.dim)
            Spacer(Modifier.width(8.dp))
            Text(value, fontFamily = Inter, fontSize = 14.sp, color = Lc.text, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            LcIcon(DeskIcon.ChevronDown, Lc.label, 12.dp)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Five stars as the grid and the toolbar draw them (filled up to [rating]). */
@Composable
fun Stars(rating: Int, size: Dp = 11.dp, tint: Color = Lc.star, empty: Color = tint.copy(alpha = 0.45f), onRate: ((Int) -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..5) {
            val m = if (onRate != null) Modifier.clickable { onRate(if (rating == i) 0 else i) }.padding(3.dp) else Modifier.padding(end = 1.dp)
            Box(m) { LcIcon(if (i <= rating) DeskIcon.StarFilled else DeskIcon.Star, if (i <= rating) tint else empty, size, contentDescription = if (onRate != null) "$i star${if (i > 1) "s" else ""}" else null) }
        }
    }
}

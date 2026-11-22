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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.storyteller.lightcraft.android.Track
import ai.storyteller.lightcraft.android.dragValue
import kotlin.math.abs

// The desktop's widgets (`crates/ui-egui/src/widgets.rs`) for touch: the same geometry, colours
// and behaviour, sized for a finger.

/** The desktop's eight colour bands (`BAND_COLORS`): red, orange, yellow, green, aqua, blue, purple, magenta. */
val BandColors = listOf("#aa0000", "#bb6600", "#aaaa00", "#009900", "#00aaaa", "#0044cc", "#7722aa", "#aa0077").map(::hex)

fun hex(s: String): Color {
    val v = s.removePrefix("#").toLongOrNull(16) ?: return Lc.track
    return Color(0xFF000000 or (v and 0xFFFFFF))
}

/** The gradient a track is painted with (`track_stops`), or null for a plain grey one. */
fun trackStops(t: Track): List<Color>? = when (t.kind) {
    "temp" -> listOf(hex("#3f6fd8"), hex("#8c9aa6"), hex("#e2c63c"))
    "tint" -> listOf(hex("#3fb34f"), hex("#8e8e8e"), hex("#c94fc1"))
    // grey at the start, then the colour (Vibrance, Saturation…)
    "gradient" -> hex(t.from ?: "#5a5a5a").let { from -> listOf(from.copy(alpha = 0.55f), from, hex(t.to ?: "#5a5a5a")) }
    // a band's hue shift: from the neighbour below, through the band, to the neighbour above
    "hue" -> listOf(MixerColors[(t.band + 7) % 8], MixerColors[t.band], MixerColors[(t.band + 1) % 8])
    // a band's saturation and luminance stay plain, as the mixer's rings already name the colour
    "sat", "lum" -> null
    "rainbow" -> listOf("#ff3b30", "#ffcc00", "#4cd964", "#34c8e0", "#3a66f0", "#c04cf0", "#ff3b30").map(::hex)
    else -> null
}

/** The colour mixer's band colours as its rings and hue tracks show them (brighter than [BandColors]). */
val MixerColors = listOf("#f0463c", "#f39a2b", "#e8d33a", "#45c35a", "#3cc6c6", "#4677f0", "#9757ec", "#de4cc4").map(::hex)

/** How close (in finger travel) a slider, dial or ruler must come to its default to click into it. */
internal val SNAP = 2.dp

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
 * full range over the track's width; within 2 dp of the default it clicks into it with a haptic
 * tick, elsewhere it lands on the value's shown precision (0.05, not 0.0473); double-tap
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
                    val travel = (size.width - pad).coerceAtLeast(1f)
                    v = (v + dx / travel * s.range).coerceIn(s.min, s.max)
                    val snapped = dragValue(v, s.default, SNAP.toPx() / travel * s.range, s.decimals, s.min, s.max)
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
            Text(spec.label, fontFamily = Inter, fontSize = 14.sp, color = if (enabled) Lc.label else Lc.disabled, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(spec.text(), style = Numbers, fontSize = 14.sp, color = if (enabled) Lc.text else Lc.disabled)
        }
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val ring = 8.dp.toPx()
            val y = size.height / 2f
            // the track runs between the thumb's centres at min and max, so the ring never clips
            val x0 = ring + 1.dp.toPx()
            val x1 = size.width - x0
            val x = (spec.value.coerceIn(spec.min, spec.max) - spec.min) / spec.range * (x1 - x0) + x0
            val stops = trackStops(spec.track)
            if (stops == null || !enabled) {
                drawLine(Lc.track, Offset(x0, y), Offset(x1, y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            } else {
                drawLine(Brush.horizontalGradient(stops, startX = x0, endX = x1), Offset(x0, y), Offset(x1, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(Lc.chrome, ring, Offset(x, y))
            drawCircle(if (!enabled) Lc.disabled else if (active) Lc.thumbActive else Lc.thumb, ring, Offset(x, y), style = Stroke(1.6.dp.toPx()))
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

/** Messages as the desktop's toasts: dark, Inter, with a hairline border, not Material's light snackbar. */
@Composable
fun LcSnackbarHost(state: androidx.compose.material3.SnackbarHostState, modifier: Modifier = Modifier) {
    androidx.compose.material3.SnackbarHost(state, modifier) { data ->
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Lc.hover)
                .border(1.dp, Lc.buttonBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(data.visuals.message, fontFamily = Inter, fontSize = 13.sp, color = Lc.text)
        }
    }
}

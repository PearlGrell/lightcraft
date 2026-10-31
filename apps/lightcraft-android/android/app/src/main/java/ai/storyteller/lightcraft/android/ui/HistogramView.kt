package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.storyteller.lightcraft.android.Histogram
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max

/** The five tonal zones, left to right, and the Light control each one drives. */
internal val ZONES = listOf("Blacks", "Shadows", "Exposure", "Highlights", "Whites")
private val ZONE_EDGES = floatArrayOf(0f, 0.1f, 0.35f, 0.65f, 0.9f, 1f)

internal fun zoneAt(fraction: Float): Int = (1 until ZONE_EDGES.size).firstOrNull { fraction <= ZONE_EDGES[it] }?.minus(1) ?: (ZONES.size - 1)

/**
 * The photo's histogram (luminance filled, R/G/B as thin lines, log-scaled so small peaks show)
 * with the share of clipped shadows and highlights in its corners. Drag sideways on it to move
 * the zone under the finger: Blacks, Shadows, Exposure, Highlights or Whites.
 */
@Composable
fun HistogramView(h: Histogram?, onZoneDrag: (zone: Int, fraction: Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var zone by remember { mutableIntStateOf(-1) }
    val drag by rememberUpdatedState(onZoneDrag)
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Lc.pill).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Box(
            Modifier
                .width(176.dp)
                .height(64.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> zone = zoneAt(o.x / size.width) },
                        onDragEnd = { zone = -1; onDone() },
                        onDragCancel = { zone = -1; onDone() },
                    ) { c, dx ->
                        c.consume()
                        if (zone >= 0) drag(zone, dx / size.width * 0.5f)
                    }
                },
        ) {
            Canvas(Modifier.width(176.dp).height(64.dp)) {
                if (zone >= 0) {
                    val x0 = ZONE_EDGES[zone] * size.width
                    val x1 = ZONE_EDGES[zone + 1] * size.width
                    drawRect(Color(0x22F3EFE8), Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, size.height))
                }
                if (h != null) {
                    val peak = maxOf(h.luma.maxOrNull() ?: 0, h.r.maxOrNull() ?: 0, h.g.maxOrNull() ?: 0, h.b.maxOrNull() ?: 0)
                    area(h.luma, peak, Color(0x66F3EFE8))
                    line(h.r, peak, Color(0xCCE5484D))
                    line(h.g, peak, Color(0xCC46A758))
                    line(h.b, peak, Color(0xCC3E83F8))
                }
                for (i in 1 until ZONE_EDGES.size - 1) {
                    val x = ZONE_EDGES[i] * size.width
                    drawLine(Color(0x33F3EFE8), Offset(x, 0f), Offset(x, size.height), 1f)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.width(176.dp)) {
            Text(clip(h?.shadowsClipped), fontSize = 10.sp, style = Numbers, color = if ((h?.shadowsClipped ?: 0f) > 0.005f) Lc.accent else Lc.muted)
            Text(
                if (zone >= 0) ZONES[zone] else "",
                fontSize = 10.sp,
                color = Lc.text,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(clip(h?.highlightsClipped), fontSize = 10.sp, style = Numbers, color = if ((h?.highlightsClipped ?: 0f) > 0.005f) Lc.accent else Lc.muted)
        }
    }
}

private fun clip(v: Float?): String = if (v == null) "–" else String.format(Locale.ROOT, "%.1f%%", v * 100f)

private fun scaled(v: Int, peak: Int): Float = if (peak <= 0) 0f else (ln(1f + v) / ln(1f + peak)).coerceIn(0f, 1f)

private fun DrawScope.area(bins: IntArray, peak: Int, color: Color) {
    val p = Path().apply {
        moveTo(0f, size.height)
        for (i in bins.indices) lineTo(i / 255f * size.width, size.height * (1f - scaled(bins[i], peak)))
        lineTo(size.width, size.height)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.line(bins: IntArray, peak: Int, color: Color) {
    val p = Path()
    for (i in bins.indices) {
        val x = i / 255f * size.width
        val y = size.height * (1f - scaled(bins[i], peak))
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(max(1f, 1.dp.toPx())))
}

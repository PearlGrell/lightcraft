package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.storyteller.lightcraft.android.Histogram
import kotlin.math.ln

/** The five tonal zones, left to right, and the Light control each one drives. */
internal val ZONES = listOf("Blacks", "Shadows", "Exposure", "Highlights", "Whites")
private val ZONE_EDGES = floatArrayOf(0f, 0.1f, 0.35f, 0.65f, 0.9f, 1f)

internal fun zoneAt(fraction: Float): Int = (1 until ZONE_EDGES.size).firstOrNull { fraction <= ZONE_EDGES[it] }?.minus(1) ?: (ZONES.size - 1)

/**
 * The desktop's histogram: R, G and B as lines over the grey luminance, log-scaled so small peaks
 * show, with the shadow and highlight clipping indicators in its top corners (lit when more than
 * 0.5 % of the photo clips). Drag sideways on a zone to move Blacks, Shadows, Exposure,
 * Highlights or Whites, as on the desktop.
 */
@Composable
fun HistogramView(h: Histogram?, onZoneDrag: (zone: Int, fraction: Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var zone by remember { mutableIntStateOf(-1) }
    val drag by rememberUpdatedState(onZoneDrag)
    Box(
        modifier
            .fillMaxWidth()
            .height(84.dp)
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
        Canvas(Modifier.fillMaxWidth().height(84.dp)) {
            if (zone >= 0) {
                val x0 = ZONE_EDGES[zone] * size.width
                val x1 = ZONE_EDGES[zone + 1] * size.width
                drawRect(Color(0x1FFFFFFF), Offset(x0, 0f), Size(x1 - x0, size.height))
            }
            if (h != null) {
                val peak = maxOf(h.luma.maxOrNull() ?: 0, h.r.maxOrNull() ?: 0, h.g.maxOrNull() ?: 0, h.b.maxOrNull() ?: 0)
                area(h.luma, peak, Color(0x59BCBCBC))
                line(h.r, peak, Color(0xFFE0453C))
                line(h.g, peak, Color(0xFF3FB84F))
                line(h.b, peak, Color(0xFF3D7BF0))
            }
            // the clipping indicators, as the desktop's corner triangles
            val t = 7.dp.toPx()
            val low = (h?.shadowsClipped ?: 0f) > 0.005f
            val high = (h?.highlightsClipped ?: 0f) > 0.005f
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(t * 1.6f, 0f); lineTo(0f, t * 1.6f); close() }, if (low) Color(0xFF3D7BF0) else Lc.track)
            drawPath(Path().apply { moveTo(size.width, 0f); lineTo(size.width - t * 1.6f, 0f); lineTo(size.width, t * 1.6f); close() }, if (high) Color(0xFFE0453C) else Lc.track)
        }
        if (zone >= 0) {
            Text(ZONES[zone], fontFamily = Inter, fontSize = 12.sp, color = Lc.text, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
        }
    }
}

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
    drawPath(p, color, style = Stroke(1.2.dp.toPx()))
}

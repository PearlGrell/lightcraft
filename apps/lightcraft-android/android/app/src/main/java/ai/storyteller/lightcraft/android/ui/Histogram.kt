package ai.storyteller.lightcraft.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val BINS = 128

/** Red, green and blue counts of a picture in [BINS] bins each (about 40 000 pixels sampled). */
class Levels(val r: IntArray, val g: IntArray, val b: IntArray) {
    val peak: Int = maxOf(r.max(), g.max(), b.max()).coerceAtLeast(1)

    companion object {
        fun of(bitmap: Bitmap): Levels? {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0 || bitmap.isRecycled) return null
            val step = maxOf(1, kotlin.math.sqrt((w.toLong() * h / 40_000.0)).toInt())
            val r = IntArray(BINS)
            val g = IntArray(BINS)
            val b = IntArray(BINS)
            val row = IntArray(w)
            var y = 0
            while (y < h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                var x = 0
                while (x < w) {
                    val c = row[x]
                    if ((c ushr 24) > 0) {
                        r[((c shr 16) and 0xFF) * BINS / 256]++
                        g[((c shr 8) and 0xFF) * BINS / 256]++
                        b[(c and 0xFF) * BINS / 256]++
                    }
                    x += step
                }
                y += step
            }
            return Levels(r, g, b)
        }
    }
}

/** A Lightroom-style RGB histogram (the channels overlap in their mixed colours) on a dark card. */
@Composable
fun Histogram(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val levels by produceState<Levels?>(null, bitmap) { value = withContext(Dispatchers.Default) { runCatching { Levels.of(bitmap) }.getOrNull() } }
    Box(modifier.size(150.dp, 76.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xB3141414)).padding(6.dp)) {
        val l = levels ?: return@Box
        Canvas(Modifier.size(138.dp, 64.dp)) {
            // a square root keeps the shadows and highlights readable next to a tall peak
            val norm = kotlin.math.sqrt(l.peak.toFloat())
            fun channel(counts: IntArray, color: Color) {
                val path = Path().apply {
                    moveTo(0f, size.height)
                    counts.forEachIndexed { i, n ->
                        lineTo(i * size.width / (BINS - 1), size.height * (1f - kotlin.math.sqrt(n.toFloat()) / norm))
                    }
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(path, color, blendMode = BlendMode.Plus)
            }
            channel(l.r, Color(0x99E04848))
            channel(l.g, Color(0x9948C060))
            channel(l.b, Color(0x994878E0))
            drawLine(Color(0x33FFFFFF), Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), 1f)
            drawLine(Color(0x33FFFFFF), Offset(2 * size.width / 3f, 0f), Offset(2 * size.width / 3f, size.height), 1f)
        }
    }
}

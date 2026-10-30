package ai.storyteller.lightcraft.android

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.TimeUnit

/**
 * On-device subject segmentation (Google ML Kit, through Play services: the model is downloaded
 * once by the system, the photo never leaves the phone). Returns the foreground probability of
 * every cell of a [side]×[side] grid over the bitmap, the format the engine's masks take.
 */
object Segmenter {
    const val SIDE = 256

    private val segmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    }

    /** Blocks (call off the main thread); throws with the reason when the model isn't available. */
    fun subject(bitmap: Bitmap, side: Int = SIDE): FloatArray {
        val result = Tasks.await(segmenter.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
        val mask = result.foregroundConfidenceMask ?: throw IllegalStateException("no subject found")
        val w = bitmap.width
        val h = bitmap.height
        mask.rewind()
        val full = FloatArray(w * h)
        mask.get(full, 0, minOf(full.size, mask.remaining()))
        val out = FloatArray(side * side)
        for (gy in 0 until side) {
            for (gx in 0 until side) {
                // average the pixels of the cell
                val x0 = gx * w / side
                val x1 = maxOf(x0 + 1, (gx + 1) * w / side)
                val y0 = gy * h / side
                val y1 = maxOf(y0 + 1, (gy + 1) * h / side)
                var sum = 0f
                var n = 0
                var y = y0
                while (y < y1 && y < h) {
                    var x = x0
                    while (x < x1 && x < w) {
                        sum += full[y * w + x]
                        n++
                        x += 1
                    }
                    y += 1
                }
                out[gy * side + gx] = if (n > 0) sum / n else 0f
            }
        }
        return out
    }
}

/**
 * The one object under a point: ML Kit's multi-subject segmentation, the subject whose mask covers
 * ([x], [y]) (0..1 of the bitmap), as a [side]×[side] grid of booleans over the bitmap; null when no
 * subject is there. Blocks (call off the main thread).
 */
object ObjectPicker {
    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build())
                .build(),
        )
    }

    fun objectAt(bitmap: Bitmap, x: Float, y: Float, side: Int): BooleanArray? {
        val result = Tasks.await(segmenter.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
        val w = bitmap.width
        val h = bitmap.height
        val px = (x * w).toInt().coerceIn(0, w - 1)
        val py = (y * h).toInt().coerceIn(0, h - 1)
        // the subject that covers the point best (its mask is relative to its own box)
        var best: com.google.mlkit.vision.segmentation.subject.Subject? = null
        var bestScore = 0.35f
        for (s in result.subjects) {
            val mask = s.confidenceMask ?: continue
            val lx = px - s.startX
            val ly = py - s.startY
            if (lx < 0 || ly < 0 || lx >= s.width || ly >= s.height) continue
            val score = mask.get(ly * s.width + lx)
            if (score > bestScore) {
                bestScore = score
                best = s
            }
        }
        val s = best ?: return null
        val mask = s.confidenceMask ?: return null
        val out = BooleanArray(side * side)
        for (gy in 0 until side) {
            val iy = ((gy + 0.5f) * h / side).toInt() - s.startY
            if (iy < 0 || iy >= s.height) continue
            for (gx in 0 until side) {
                val ix = ((gx + 0.5f) * w / side).toInt() - s.startX
                if (ix < 0 || ix >= s.width) continue
                if (mask.get(iy * s.width + ix) > 0.5f) out[gy * side + gx] = true
            }
        }
        return out
    }
}

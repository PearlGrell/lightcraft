package ai.storyteller.lightcraft.android

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import androidx.annotation.Keep
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** ONNX Runtime sessions use this many threads (big cores; the UI keeps the rest). */
private const val THREADS = 4

private fun options(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
    setIntraOpNumThreads(THREADS)
    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
}

/**
 * Tap to select with SAM 2.1 tiny: the image encoder runs once per picture (about a second), each
 * tap only runs the small prompt decoder. Of the three masks SAM proposes for a point (a part, a
 * larger part, the whole object), the one it is most confident in is taken, and the largest when
 * they are close; that is the whole object for a tap on it.
 */
class SamSelector(dir: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val encoder = env.createSession(File(dir, "vision_encoder_fp16.onnx").absolutePath, options())
    private val decoder = env.createSession(File(dir, "prompt_encoder_mask_decoder.onnx").absolutePath, options())
    private var embedded: Bitmap? = null
    private var embeddings: OrtSession.Result? = null

    private fun embed(bitmap: Bitmap): OrtSession.Result {
        embeddings?.let { if (embedded === bitmap) return it }
        embeddings?.close()
        val side = 1024
        val scaled = Bitmap.createScaledBitmap(bitmap, side, side, true)
        val px = IntArray(side * side)
        scaled.getPixels(px, 0, side, 0, 0, side, side)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val data = FloatBuffer.allocate(3 * side * side)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (i in px.indices) data.put(((px[i] shr shift and 0xFF) / 255f - mean[c]) / std[c])
        }
        data.rewind()
        val result = OnnxTensor.createTensor(env, data, longArrayOf(1, 3, side.toLong(), side.toLong())).use { input ->
            encoder.run(mapOf("pixel_values" to input))
        }
        embedded = bitmap
        embeddings = result
        return result
    }

    /** The object under ([x], [y]) (0..1 of [bitmap]) as a [side]² grid over the bitmap, or null. */
    @Synchronized
    fun select(bitmap: Bitmap, x: Float, y: Float, side: Int): BooleanArray? {
        val e = embed(bitmap)
        val point = FloatBuffer.wrap(floatArrayOf(x.coerceIn(0f, 1f) * 1024f, y.coerceIn(0f, 1f) * 1024f))
        val inputs = HashMap<String, OnnxTensor>()
        try {
            inputs["input_points"] = OnnxTensor.createTensor(env, point, longArrayOf(1, 1, 1, 2))
            inputs["input_labels"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(1)), longArrayOf(1, 1, 1))
            inputs["input_boxes"] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, 0, 4))
            for (k in 0 until 3) {
                val name = "image_embeddings.$k"
                inputs[name] = e.get(name).orElse(null) as? OnnxTensor ?: return null
            }
            decoder.run(inputs).use { out ->
                val scores = (out.get("iou_scores").orElse(null) as? OnnxTensor)?.floatBuffer ?: return null
                val masks = (out.get("pred_masks").orElse(null) as? OnnxTensor) ?: return null
                val shape = masks.info.shape // [1, 1, 3, h, w]
                if (shape.size != 5) return null
                val n = shape[2].toInt()
                val mh = shape[3].toInt()
                val mw = shape[4].toInt()
                val buf = masks.floatBuffer
                val iou = FloatArray(n) { scores.get(it) }
                val area = IntArray(n) { k -> (0 until mh * mw).count { buf.get(k * mh * mw + it) > 0f } }
                val best = iou.maxOrNull() ?: return null
                val k = (0 until n).filter { iou[it] >= best - 0.05f }.maxByOrNull { area[it] } ?: return null
                val grid = BooleanArray(side * side)
                var any = false
                for (gy in 0 until side) {
                    val my = ((gy + 0.5f) * mh / side).toInt().coerceIn(0, mh - 1)
                    for (gx in 0 until side) {
                        val mx = ((gx + 0.5f) * mw / side).toInt().coerceIn(0, mw - 1)
                        if (buf.get(k * mh * mw + my * mw + mx) > 0f) {
                            grid[gy * side + gx] = true
                            any = true
                        }
                    }
                }
                return if (any) dilate(grid, side, 2) else null
            }
        } finally {
            for ((name, t) in inputs) if (!name.startsWith("image_embeddings")) t.close()
        }
    }

    override fun close() {
        embeddings?.close()
        encoder.close()
        decoder.close()
    }
}

/**
 * Generative fill with big-LaMa. The engine renders Generative spots by calling [paint] (through
 * JNI) with the box already squared, at 512 px, in sRGB, with the hole blanked; LaMa paints it.
 */
@Keep
class LamaPainter(file: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(file.absolutePath, options())

    /** [rgb] is 512² interleaved 8-bit RGB, [hole] 512² bytes (1 = paint in); null when it fails. */
    @Keep
    @Synchronized
    fun paint(rgb: ByteArray, hole: ByteArray): ByteArray? {
        val side = 512
        val n = side * side
        if (rgb.size != n * 3 || hole.size != n) return null
        return runCatching {
            val image = FloatBuffer.allocate(3 * n)
            for (c in 0 until 3) for (i in 0 until n) image.put((rgb[i * 3 + c].toInt() and 0xFF) / 255f)
            image.rewind()
            val mask = FloatBuffer.allocate(n)
            for (i in 0 until n) mask.put(if (hole[i].toInt() != 0) 1f else 0f)
            mask.rewind()
            OnnxTensor.createTensor(env, image, longArrayOf(1, 3, side.toLong(), side.toLong())).use { img ->
                OnnxTensor.createTensor(env, mask, longArrayOf(1, 1, side.toLong(), side.toLong())).use { m ->
                    session.run(mapOf("image" to img, "mask" to m)).use { out ->
                        val painted = (out.get(0) as OnnxTensor).floatBuffer // [1, 3, 512, 512], 0..255
                        val bytes = ByteArray(n * 3)
                        for (c in 0 until 3) {
                            for (i in 0 until n) {
                                val v = painted.get(c * n + i)
                                bytes[i * 3 + c] = (if (v.isFinite()) v.coerceIn(0f, 255f) else 0f).toInt().toByte()
                            }
                        }
                        bytes
                    }
                }
            }
        }.onFailure { android.util.Log.w("LightCraft", "LaMa", it) }.getOrNull()
    }

    override fun close() = session.close()
}

/** Grows every selected cell of a [side]² grid by [r] cells (a square neighbourhood). */
fun dilate(grid: BooleanArray, side: Int, r: Int): BooleanArray {
    if (r <= 0) return grid
    val out = BooleanArray(grid.size)
    for (y in 0 until side) {
        for (x in 0 until side) {
            if (!grid[y * side + x]) continue
            for (yy in maxOf(0, y - r)..minOf(side - 1, y + r)) {
                for (xx in maxOf(0, x - r)..minOf(side - 1, x + r)) out[yy * side + xx] = true
            }
        }
    }
    return out
}

package ai.storyteller.lightcraft.android

import android.graphics.Bitmap
import org.json.JSONObject

/** Magic eraser settings (see `lightcraft_bgremove::EraseOptions`). */
data class EraseSettings(
    val tolerance: Float = 32f,
    val contiguous: Boolean = true,
    val sample: Int = 1,
    val antiAlias: Boolean = true,
    val feather: Float = 0f,
    val opacity: Float = 100f,
)

/** An engine command failed; [kind] is the engine's error kind (`BadParams`, `UnknownCommand`…). */
class EngineException(val kind: String, message: String) : Exception(message)

/**
 * One LightCraft engine session (the Rust `lightcraft_mobile::Mobile` behind JNI).
 *
 * Every edit is an engine command by id with JSON parameters, the same commands the desktop app, CLI
 * and MCP use. Calls block: run them off the main thread. The session is thread-safe, and a render
 * never holds it while it runs, so a slider can keep sending edits while the previous frame renders.
 */
class Engine private constructor(private var handle: Long) : AutoCloseable {

    /** Open (or create) the library stored in [dir]; a new one is seeded with the demo photos. */
    fun openLibrary(dir: String, seedDemo: Boolean) {
        nativeOpenLibrary(live(), dir, seedDemo)?.let { throw EngineException("Other", it) }
    }

    /** Run command [id]; returns its `result`, or throws [EngineException]. */
    fun execute(id: String, params: JSONObject = JSONObject()): Any? {
        val answer = JSONObject(nativeExecute(live(), id, params.toString()))
        if (!answer.optBoolean("ok")) {
            throw EngineException(answer.optString("kind", "Other"), answer.optString("error", "$id failed"))
        }
        return answer.opt("result")
    }

    /** The library photo for the gallery file at [path], imported in place the first time. */
    fun photoForPath(path: String): Long = nativePhotoForPath(live(), path)

    /** The photo ids the library shows, in its order. */
    fun photos(): LongArray = nativePhotos(live())

    /** [photo] with its edits, fitted into [maxW]×[maxH]; the unedited look when [before]. */
    fun render(photo: Long, maxW: Int, maxH: Int, before: Boolean = false, draft: Boolean = false): Bitmap =
        bitmap(nativeRender(live(), photo, maxW, maxH, before, draft))

    /** A grid thumbnail whose long edge is at least [longEdge]. */
    fun thumbnail(photo: Long, longEdge: Int): Bitmap = bitmap(nativeThumbnail(live(), photo, longEdge))

    /** Export [photo] into [dir] with the export dialog's [options]; returns the file written. */
    fun export(photo: Long, options: JSONObject, dir: String): String = nativeExport(live(), photo, options.toString(), dir)

    /** A mask on the active photo from on-device segmentation: [side]×[side] probabilities over the uncropped photo. */
    fun addSegMask(name: String, side: Int, probs: FloatArray, invert: Boolean) {
        val answer = JSONObject(nativeAddSegMask(live(), name, side, probs, invert))
        if (!answer.optBoolean("ok")) throw EngineException(answer.optString("kind", "Other"), answer.optString("error", "mask failed"))
    }

    /** [photo] as preset [preset] would leave it, for the presets strip (nothing is applied). */
    fun presetThumbnail(photo: Long, preset: String, longEdge: Int): Bitmap = bitmap(nativePresetThumbnail(live(), photo, preset, longEdge))

    /** Background removal: render [photo] at [longEdge]; returns its size. */
    fun cutoutBegin(photo: Long, longEdge: Int): Pair<Int, Int> = nativeCutoutBegin(live(), photo, longEdge).let { it[0] to it[1] }
    fun cutoutMatte(side: Int, probs: FloatArray, invert: Boolean) = nativeCutoutMatte(live(), side, probs, invert)
    fun cutoutErase(x: Float, y: Float, o: EraseSettings): Int =
        nativeCutoutErase(live(), x, y, o.tolerance, o.contiguous, o.sample, o.antiAlias, o.feather, o.opacity)
    fun cutoutUndo(): Boolean = nativeCutoutUndo(live())
    fun cutoutReset() = nativeCutoutReset(live())
    fun cutoutFrame(): Bitmap = bitmap(nativeCutoutFrame(live()))
    fun cutoutExport(dir: String, name: String, refine: Boolean): String = nativeCutoutExport(live(), dir, name, refine)
    fun cutoutEnd() = nativeCutoutEnd(live())

    override fun close() {
        if (handle != 0L) nativeRelease(handle)
        handle = 0L
    }

    private fun live(): Long = handle.takeIf { it != 0L } ?: throw IllegalStateException("engine closed")

    companion object {
        init {
            System.loadLibrary("lightcraft_android")
        }

        /** A new session: empty, or holding the procedural demo library when [demo]. */
        fun create(demo: Boolean = false): Engine {
            val h = nativeCreate(demo)
            check(h != 0L) { "the LightCraft engine could not start" }
            return Engine(h)
        }

        /** `[width, height, argb…]` from Rust → a bitmap. */
        internal fun bitmap(frame: IntArray): Bitmap {
            val w = frame.getOrElse(0) { 0 }
            val h = frame.getOrElse(1) { 0 }
            require(w > 0 && h > 0 && frame.size == 2 + w * h) { "bad frame ${w}x$h (${frame.size} values)" }
            return Bitmap.createBitmap(frame, 2, w, w, h, Bitmap.Config.ARGB_8888)
        }

        @JvmStatic private external fun nativeCreate(demo: Boolean): Long
        @JvmStatic private external fun nativeRelease(handle: Long)
        @JvmStatic private external fun nativeOpenLibrary(handle: Long, dir: String, seedDemo: Boolean): String?
        @JvmStatic private external fun nativeExecute(handle: Long, id: String, params: String): String
        @JvmStatic private external fun nativePhotoForPath(handle: Long, path: String): Long
        @JvmStatic private external fun nativePhotos(handle: Long): LongArray
        @JvmStatic private external fun nativeRender(handle: Long, photo: Long, maxW: Int, maxH: Int, before: Boolean, draft: Boolean): IntArray
        @JvmStatic private external fun nativeThumbnail(handle: Long, photo: Long, longEdge: Int): IntArray
        @JvmStatic private external fun nativePresetThumbnail(handle: Long, photo: Long, preset: String, longEdge: Int): IntArray
        @JvmStatic private external fun nativeAddSegMask(handle: Long, name: String, side: Int, probs: FloatArray, invert: Boolean): String
        @JvmStatic private external fun nativeCutoutBegin(handle: Long, photo: Long, longEdge: Int): IntArray
        @JvmStatic private external fun nativeCutoutMatte(handle: Long, side: Int, probs: FloatArray, invert: Boolean)
        @JvmStatic private external fun nativeCutoutErase(handle: Long, x: Float, y: Float, tolerance: Float, contiguous: Boolean, sample: Int, antiAlias: Boolean, feather: Float, opacity: Float): Int
        @JvmStatic private external fun nativeCutoutUndo(handle: Long): Boolean
        @JvmStatic private external fun nativeCutoutReset(handle: Long)
        @JvmStatic private external fun nativeCutoutFrame(handle: Long): IntArray
        @JvmStatic private external fun nativeCutoutExport(handle: Long, dir: String, name: String, refine: Boolean): String
        @JvmStatic private external fun nativeCutoutEnd(handle: Long)
        @JvmStatic private external fun nativeExport(handle: Long, photo: Long, options: String, dir: String): String
    }
}

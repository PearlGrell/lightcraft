package ai.storyteller.lightcraft.android

import android.graphics.Bitmap
import org.json.JSONObject

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

    /** The photo ids the library shows, in its order. */
    fun photos(): LongArray = nativePhotos(live())

    /** [photo] with its edits, fitted into [maxW]×[maxH]; the unedited look when [before]. */
    fun render(photo: Long, maxW: Int, maxH: Int, before: Boolean = false, draft: Boolean = false): Bitmap =
        bitmap(nativeRender(live(), photo, maxW, maxH, before, draft))

    /** A grid thumbnail whose long edge is at least [longEdge]. */
    fun thumbnail(photo: Long, longEdge: Int): Bitmap = bitmap(nativeThumbnail(live(), photo, longEdge))

    /** Export [photo] into [dir] with the export dialog's [options]; returns the file written. */
    fun export(photo: Long, options: JSONObject, dir: String): String = nativeExport(live(), photo, options.toString(), dir)

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
        @JvmStatic private external fun nativePhotos(handle: Long): LongArray
        @JvmStatic private external fun nativeRender(handle: Long, photo: Long, maxW: Int, maxH: Int, before: Boolean, draft: Boolean): IntArray
        @JvmStatic private external fun nativeThumbnail(handle: Long, photo: Long, longEdge: Int): IntArray
        @JvmStatic private external fun nativeExport(handle: Long, photo: Long, options: String, dir: String): String
    }
}

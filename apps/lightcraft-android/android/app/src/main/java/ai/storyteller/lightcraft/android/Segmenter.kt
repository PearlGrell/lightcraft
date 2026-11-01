package ai.storyteller.lightcraft.android

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
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

    /** Whether Google Play services has the segmentation model on the phone (blocks; off the main thread). */
    fun installed(context: Context): Boolean = runCatching {
        Tasks.await(ModuleInstall.getClient(context).areModulesAvailable(segmenter), 20, TimeUnit.SECONDS).areModulesAvailable()
    }.getOrDefault(false)

    /**
     * Ask Play services to download the model now (it otherwise comes with the first use).
     * [progress] gets 0..1 while it downloads; [done] the outcome (null = installed, else why not).
     */
    fun install(context: Context, progress: (Float) -> Unit, done: (String?) -> Unit) {
        val client = ModuleInstall.getClient(context)
        val listener = object : InstallStatusListener {
            override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                update.progressInfo?.let { p -> if (p.totalBytesToDownload > 0) progress(p.bytesDownloaded.toFloat() / p.totalBytesToDownload) }
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> { client.unregisterListener(this); done(null) }
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED, ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                        client.unregisterListener(this); done("the download stopped (error ${update.errorCode})")
                    }
                    else -> {}
                }
            }
        }
        val request = ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build()
        client.installModules(request)
            .addOnSuccessListener { r -> if (r.areModulesAlreadyInstalled()) { client.unregisterListener(listener); done(null) } }
            .addOnFailureListener { e -> client.unregisterListener(listener); done(e.message ?: "Google Play services could not download it") }
    }

    /** Tell Play services LightCraft no longer needs the model; the system may then remove it. */
    fun release(context: Context) {
        runCatching { ModuleInstall.getClient(context).releaseModules(segmenter) }
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

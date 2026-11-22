package ai.storyteller.lightcraft.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Model downloads in the phone's notification shade: a progress bar while one downloads, then
 * "downloaded" or "failed". Without the notification permission it stays silent (the app's own
 * messages and the Downloads page still show it).
 */
class DownloadNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Progress of the AI models LightCraft downloads"
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun base(name: String) = NotificationCompat.Builder(context, CHANNEL)
        .setContentTitle(name)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    fun progress(name: String, done: Long, total: Long) {
        if (!allowed()) return
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val n = base(name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText(if (total > 0) "Downloading… $pct %" else "Downloading…")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .build()
        runCatching { manager.notify(ID, n) }
    }

    fun finished(name: String, ok: Boolean, detail: String = "") {
        if (!allowed()) return
        val n = base(name)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentText(if (ok) "Downloaded. Ready to use." else "Download failed${if (detail.isNotBlank()) ": $detail" else ""}")
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ID, n) }
    }

    fun clear() {
        runCatching { manager.cancel(ID) }
    }

    private companion object {
        const val CHANNEL = "model-downloads"
        const val ID = 4201
    }
}

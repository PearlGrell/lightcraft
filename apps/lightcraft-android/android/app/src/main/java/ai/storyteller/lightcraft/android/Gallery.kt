package ai.storyteller.lightcraft.android

import android.Manifest
import android.content.ContentUris
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Environment
import android.util.Size
import java.io.File
import androidx.core.content.ContextCompat

/** One image of the phone's gallery (MediaStore). [path] is its file, when the system gives one. */
data class GalleryImage(val id: Long, val uri: Uri, val path: String?, val name: String, val mime: String, val taken: Long = 0L)

/** The phone's gallery: every image MediaStore knows, newest first. */
object Gallery {
    /** The permissions to ask for: the images permission on Android 13+, storage before. */
    val permissions: Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Full access, or (Android 14+) access to the photos the person picked. */
    fun hasAccess(context: Context): Boolean = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun images(context: Context): List<GalleryImage> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION") // DATA is still the file path apps with media access may read
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
        val order = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        val out = ArrayList<GalleryImage>()
        context.contentResolver.query(collection, columns, null, null, order)?.use { c ->
            val id = c.getColumnIndexOrThrow(columns[0])
            val data = c.getColumnIndexOrThrow(columns[1])
            val name = c.getColumnIndexOrThrow(columns[2])
            val mime = c.getColumnIndexOrThrow(columns[3])
            val taken = c.getColumnIndexOrThrow(columns[4])
            val added = c.getColumnIndexOrThrow(columns[5])
            while (c.moveToNext()) {
                val mediaId = c.getLong(id)
                out += GalleryImage(
                    id = mediaId,
                    uri = ContentUris.withAppendedId(collection, mediaId),
                    path = c.getString(data)?.takeIf { it.isNotEmpty() },
                    name = c.getString(name) ?: "photo",
                    mime = c.getString(mime) ?: "image/*",
                    // DATE_TAKEN is milliseconds and may be missing; DATE_ADDED is seconds
                    taken = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(added) * 1000),
                )
            }
        }
        return out
    }

    /** The system's own thumbnail (fast; the grid uses it until a photo has edits). */
    fun thumbnail(context: Context, image: GalleryImage, edge: Int): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(image.uri, Size(edge, edge), null) }.getOrNull()

    /**
     * Ask Android to delete [images]: on Android 11+ the system's own confirmation (one dialog for
     * all of them), returned as the [IntentSender] to launch. On Android 10 the files the app may
     * delete go at once; for one it may not, the system's permission prompt comes back instead.
     * `null` = nothing left to confirm (all deleted).
     */
    fun deleteRequest(context: Context, images: List<GalleryImage>): IntentSender? {
        if (images.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= 30) {
            return MediaStore.createDeleteRequest(context.contentResolver, images.map { it.uri }).intentSender
        }
        for (image in images) {
            try {
                context.contentResolver.delete(image.uri, null, null)
            } catch (e: RecoverableSecurityException) {
                return e.userAction.actionIntent.intentSender
            }
        }
        return null
    }

    /** Whether [image] is gone from the gallery (deleted, or no longer visible to the app). */
    fun isGone(context: Context, image: GalleryImage): Boolean =
        runCatching {
            context.contentResolver.query(image.uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.count == 0 } ?: true
        }.getOrDefault(true)

    /** Save an exported [file] into Pictures/LightCraft so it shows in the phone's gallery; returns its name. */
    fun saveToGallery(context: Context, file: File, mime: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LightCraft")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("the gallery refused the new photo")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("could not write to the gallery")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return file.name
    }
}

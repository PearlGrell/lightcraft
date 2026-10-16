package ai.storyteller.lightcraft.android

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat

/** One image of the phone's gallery (MediaStore). [path] is its file, when the system gives one. */
data class GalleryImage(val id: Long, val uri: Uri, val path: String?, val name: String, val mime: String)

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
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE)
        val order = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        val out = ArrayList<GalleryImage>()
        context.contentResolver.query(collection, columns, null, null, order)?.use { c ->
            val id = c.getColumnIndexOrThrow(columns[0])
            val data = c.getColumnIndexOrThrow(columns[1])
            val name = c.getColumnIndexOrThrow(columns[2])
            val mime = c.getColumnIndexOrThrow(columns[3])
            while (c.moveToNext()) {
                val mediaId = c.getLong(id)
                out += GalleryImage(
                    id = mediaId,
                    uri = ContentUris.withAppendedId(collection, mediaId),
                    path = c.getString(data)?.takeIf { it.isNotEmpty() },
                    name = c.getString(name) ?: "photo",
                    mime = c.getString(mime) ?: "image/*",
                )
            }
        }
        return out
    }

    /** The system's own thumbnail (fast; the grid uses it until a photo has edits). */
    fun thumbnail(context: Context, image: GalleryImage, edge: Int): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(image.uri, Size(edge, edge), null) }.getOrNull()
}

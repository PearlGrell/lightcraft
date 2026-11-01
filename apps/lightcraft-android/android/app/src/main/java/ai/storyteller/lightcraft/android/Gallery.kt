package ai.storyteller.lightcraft.android

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File

/**
 * One image of the phone's gallery (MediaStore). [path] is its file, when the system gives one;
 * [width] × [height] is its upright size (0 when unknown); [album] is the folder it sits in
 * (Camera, Screenshots…); [added] when it arrived, in milliseconds.
 */
data class GalleryImage(
    val id: Long,
    val uri: Uri,
    val path: String?,
    val name: String,
    val mime: String,
    val taken: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val album: String = "",
    val added: Long = 0L,
) {
    /** Width / height for the justified grid (square when unknown). */
    val aspect: Float get() = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.25f, 4f) else 1f
}

/** What a delete request asks of Android: move to the trash, restore from it, or delete for good. */
enum class DeleteMode { Trash, Restore, Delete }

/** The phone's gallery: every image MediaStore knows, newest first. */
object Gallery {
    /** The permissions to ask for: the images permission on Android 13+, storage before. */
    val permissions: Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Android 11+ keeps deleted photos in a trash for 30 days; before, a delete is final. */
    val hasTrash: Boolean get() = Build.VERSION.SDK_INT >= 30

    /** Full access, or (Android 14+) access to the photos the person picked. */
    fun hasAccess(context: Context): Boolean = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION") // DATA is still the file path apps with media access may read
    private val columns = arrayOf(
        MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.ORIENTATION,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )

    private const val ORDER = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"

    /** Every image the gallery shows. */
    fun images(context: Context): List<GalleryImage> =
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, null, null, ORDER)?.use(::read) ?: emptyList()

    /** The images in the trash (Android 11+), newest first. */
    fun trashed(context: Context): List<GalleryImage> {
        if (!hasTrash) return emptyList()
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, ORDER)
        }
        return runCatching {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, args, null)?.use(::read)
        }.getOrNull() ?: emptyList()
    }

    private fun read(c: Cursor): List<GalleryImage> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<GalleryImage>(c.count.coerceAtLeast(0))
        val ix = columns.map { c.getColumnIndex(it) }
        fun str(i: Int) = ix[i].takeIf { it >= 0 }?.let { c.getString(it) }
        fun long(i: Int) = ix[i].takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
        fun int(i: Int) = ix[i].takeIf { it >= 0 }?.let { c.getInt(it) } ?: 0
        while (c.moveToNext()) {
            val mediaId = long(0)
            val rotated = int(8) % 180 != 0
            val w = int(6)
            val h = int(7)
            out += GalleryImage(
                id = mediaId,
                uri = ContentUris.withAppendedId(collection, mediaId),
                path = str(1)?.takeIf { it.isNotEmpty() },
                name = str(2) ?: "photo",
                mime = str(3) ?: "image/*",
                // DATE_TAKEN is milliseconds and may be missing; DATE_ADDED is seconds
                taken = long(4).takeIf { it > 0 } ?: (long(5) * 1000),
                width = if (rotated) h else w,
                height = if (rotated) w else h,
                album = str(9) ?: "",
                added = long(5) * 1000,
            )
        }
        return out
    }

    /** The system's own thumbnail (fast; the grid uses it until a photo has edits). */
    fun thumbnail(context: Context, image: GalleryImage, edge: Int): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(image.uri, Size(edge, edge), null) }.getOrNull()

    /**
     * Ask Android to trash, restore or delete [images]: on Android 11+ the system's own confirmation
     * (one dialog for all of them), returned as the [IntentSender] to launch. On Android 10 (no
     * trash) the files the app may delete go at once; for one it may not, the system's permission
     * prompt comes back instead. `null` = nothing left to confirm.
     */
    fun request(context: Context, images: List<GalleryImage>, mode: DeleteMode): IntentSender? {
        if (images.isEmpty()) return null
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 30) {
            val uris = images.map { it.uri }
            return when (mode) {
                DeleteMode.Trash -> MediaStore.createTrashRequest(resolver, uris, true)
                DeleteMode.Restore -> MediaStore.createTrashRequest(resolver, uris, false)
                DeleteMode.Delete -> MediaStore.createDeleteRequest(resolver, uris)
            }.intentSender
        }
        if (mode == DeleteMode.Restore) return null
        for (image in images) {
            try {
                resolver.delete(image.uri, null, null)
            } catch (e: RecoverableSecurityException) {
                return e.userAction.actionIntent.intentSender
            }
        }
        return null
    }

    /** Whether [image] no longer exists at all (deleted for good, not just in the trash). */
    fun isGone(context: Context, image: GalleryImage): Boolean {
        val args = Bundle().apply { if (hasTrash) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        return runCatching {
            context.contentResolver.query(image.uri, arrayOf(MediaStore.Images.Media._ID), args, null)?.use { it.count == 0 } ?: true
        }.getOrDefault(true)
    }

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

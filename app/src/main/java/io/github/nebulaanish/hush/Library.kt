package io.github.nebulaanish.hush

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File

data class LibraryItem(val uri: Uri, val title: String, val sizeBytes: Long)

/**
 * Downloads live in the phone's own Music/ and Movies/ folders, not in the app's private
 * storage. Android blocks file managers from browsing Android/data, so anything saved
 * there is invisible to the user and to every other music player on the device.
 */
object Library {

    private const val TAG = "HushDL"
    const val MUSIC_PATH = "Music/Hush"
    const val VIDEO_PATH = "Movies/Hush"

    /** Copies a finished download into the public library, then removes the scratch copy. */
    fun publish(ctx: Context, file: File, audio: Boolean): Uri? {
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (audio) "audio/mp4" else "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (audio) MUSIC_PATH else VIDEO_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return runCatching {
            val uri = ctx.contentResolver.insert(collection, values)
                ?: error("MediaStore refused the insert")
            ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
            file.delete()
            Log.d(TAG, "published ${file.name} -> $uri")
            uri
        }.onFailure { Log.e(TAG, "publish failed for ${file.name}", it) }.getOrNull()
    }

    /** MediaStore renders these itself, which covers files with no embedded cover art. */
    fun thumbnail(ctx: Context, uri: Uri, px: Int): android.graphics.Bitmap? = runCatching {
        ctx.contentResolver.loadThumbnail(uri, android.util.Size(px, px), null)
    }.getOrNull()

    fun list(ctx: Context, audio: Boolean): List<LibraryItem> {
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )
        val out = ArrayList<LibraryItem>()
        runCatching {
            ctx.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("${if (audio) MUSIC_PATH else VIDEO_PATH}%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    out += LibraryItem(
                        ContentUris.withAppendedId(collection, c.getLong(idCol)),
                        c.getString(nameCol).substringBeforeLast('.'),
                        c.getLong(sizeCol)
                    )
                }
            }
        }.onFailure { Log.e(TAG, "library query failed", it) }
        return out
    }
}

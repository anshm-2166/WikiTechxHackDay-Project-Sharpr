package com.vytros.projects.sharpr.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Sharing and saving results outside the app. */
object Export {
    fun mimeOf(f: File): String = when (f.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun share(context: Context, file: File, title: String = "Share") {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(file)
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Where a record lands when saved: Pictures/Sharpr, Movies/Sharpr or Music/Sharpr. */
    fun folderLabel(type: MediaType) = when (type) {
        MediaType.Image -> "Pictures/Sharpr"
        MediaType.Video -> "Movies/Sharpr"
        MediaType.Audio -> "Music/Sharpr"
    }

    /** Copies into shared storage. Returns false on Android 8/9 (that needs the legacy storage permission). */
    fun save(context: Context, record: MediaRecord): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val file = File(record.resultFile)
        val suffix = when (record.type) {
            MediaType.Image -> "_sharpr_${record.scale}x"
            MediaType.Video -> "_sharpr_${record.scale}x"
            MediaType.Audio -> "_sharpr_clean"
        }
        val (collection, dir) = when (record.type) {
            MediaType.Image -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            MediaType.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            MediaType.Audio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${record.name}$suffix.${file.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(file))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Sharpr")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}

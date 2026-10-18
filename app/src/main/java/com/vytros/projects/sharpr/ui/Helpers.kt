package com.vytros.projects.sharpr.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.vytros.projects.sharpr.engine.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Tiny memory-cached async loader; plenty for a grid of thumbnails and two detail images. */
object ImageLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(96 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(view: ImageView, file: File, maxSide: Int) {
        val key = "${file.absolutePath}@$maxSide"
        view.tag = key
        cache[key]?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageIO.decodeSampled(file, maxSide) } ?: return@launch
            cache.put(key, bmp)
            if (view.tag == key) {
                view.setImageBitmap(bmp)
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(180).start()
            }
        }
    }

    /** Decode off the main thread (no caching; used for the big compare images). */
    suspend fun decode(file: File, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) { ImageIO.decodeSampled(file, maxSide) }

    fun evict(file: File) {
        cache.snapshot().keys.filter { it.startsWith(file.absolutePath) }.forEach { cache.remove(it) }
    }
}

object Share {
    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun share(context: Context, file: File) {
        val mime = mimeOf(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share upscaled image"))
    }

    /** Saves into Pictures/Sharpr. Returns false on Android 8 (needs the legacy storage permission). */
    fun saveToGallery(context: Context, file: File, displayName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(context, "Use Share to export on this Android version", Toast.LENGTH_LONG).show()
            return false
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.${file.extension}")
            put(MediaStore.Images.Media.MIME_TYPE, mimeOf(file))
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Sharpr")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun mimeOf(f: File) = when (f.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }
}

object Fmt {
    fun dims(w: Int, h: Int) = "$w×$h"
    fun mp(w: Int, h: Int) = String.format(Locale.US, "%.1f MP", w.toLong() * h / 1_000_000.0)
    fun duration(ms: Long): String =
        if (ms < 1000) "${ms}ms" else if (ms < 60_000) String.format(Locale.US, "%.1fs", ms / 1000.0)
        else "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

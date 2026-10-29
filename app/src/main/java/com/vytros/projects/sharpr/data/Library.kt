package com.vytros.projects.sharpr.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.vytros.projects.sharpr.engine.AudioOptions
import com.vytros.projects.sharpr.engine.UpscaleOptions
import com.vytros.projects.sharpr.engine.VideoOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class MediaType(val label: String) { Image("Image"), Video("Video"), Audio("Audio") }

data class MediaRecord(
    val id: String,
    val type: MediaType,
    val name: String,
    val createdAt: Long,
    /** Pixels for images and video; for audio srcW = sample rate, srcH = channels. */
    val srcW: Int,
    val srcH: Int,
    val outW: Int,
    val outH: Int,
    val backend: String,
    /** How long processing took. */
    val durationMs: Long,
    val originalFile: String,
    val resultFile: String,
    val thumbFile: String,
    /** Length of a video or audio clip. */
    val mediaMs: Long = 0,
    val image: UpscaleOptions? = null,
    val video: VideoOptions? = null,
    val audio: AudioOptions? = null,
    /** One-line extra facts, e.g. "60 Hz hum removed". */
    val note: String = "",
) {
    /** Binary waveform peaks for audio (see engine/audio/Waveform.kt). */
    val peaksIn: String get() = "$thumbFile.in.peaks"
    val peaksOut: String get() = "$thumbFile.out.peaks"

    val scale: Int
        get() = image?.scale ?: video?.scale ?: 1

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("name", name).put("createdAt", createdAt)
        .put("srcW", srcW).put("srcH", srcH).put("outW", outW).put("outH", outH)
        .put("backend", backend).put("durationMs", durationMs).put("mediaMs", mediaMs)
        .put("originalFile", originalFile).put("resultFile", resultFile).put("thumbFile", thumbFile)
        .put("note", note)
        .apply {
            image?.let { put("options", it.toJson()) }
            video?.let { put("video", it.toJson()) }
            audio?.let { put("audio", it.toJson()) }
        }

    companion object {
        fun fromJson(o: JSONObject): MediaRecord {
            // v1 records have no "type" and keep image options under "options".
            val type = runCatching { MediaType.valueOf(o.optString("type")) }.getOrDefault(MediaType.Image)
            return MediaRecord(
                id = o.getString("id"),
                type = type,
                name = o.optString("name", "Untitled"),
                createdAt = o.optLong("createdAt"),
                srcW = o.optInt("srcW"), srcH = o.optInt("srcH"),
                outW = o.optInt("outW"), outH = o.optInt("outH"),
                backend = o.optString("backend"),
                durationMs = o.optLong("durationMs"),
                originalFile = o.getString("originalFile"),
                resultFile = o.getString("resultFile"),
                thumbFile = o.getString("thumbFile"),
                mediaMs = o.optLong("mediaMs"),
                image = if (type == MediaType.Image) UpscaleOptions.fromJson(o.optJSONObject("options")) else null,
                video = if (type == MediaType.Video) VideoOptions.fromJson(o.optJSONObject("video")) else null,
                audio = if (type == MediaType.Audio) AudioOptions.fromJson(o.optJSONObject("audio")) else null,
                note = o.optString("note"),
            )
        }
    }
}

/** A picked file copied into app storage, ready to process (and to re-process later from the original). */
data class ImportedMedia(val file: File, val name: String, val type: MediaType)

/**
 * File-backed history. A small JSON index keeps this free of annotation processing; media live in
 * originals/, results/ and thumbs/ under filesDir.
 */
class LibraryStore(private val context: Context) {
    private val root = File(context.filesDir, "library").apply { mkdirs() }
    val originals = File(root, "originals").apply { mkdirs() }
    val results = File(root, "results").apply { mkdirs() }
    val thumbs = File(root, "thumbs").apply { mkdirs() }
    val scratch: File get() = File(context.cacheDir, "work").apply { mkdirs() }
    private val index = File(root, "index.json")

    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<MediaRecord>> = _records

    fun find(id: String?): MediaRecord? = id?.let { i -> _records.value.firstOrNull { it.id == i } }

    fun newId(): String = UUID.randomUUID().toString()

    /** Copies a picked Uri into originals/ so it can be regenerated later. */
    fun import(uri: Uri, type: MediaType): ImportedMedia {
        val resolver = context.contentResolver
        val display = displayName(uri)
        val mimeExt = resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val ext = (display?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: mimeExt ?: when (type) {
                MediaType.Image -> "jpg"
                MediaType.Video -> "mp4"
                MediaType.Audio -> "m4a"
            })
        val dest = File(originals, "${newId()}.$ext")
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: error("Couldn't open that file")
        val name = display?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: type.label
        return ImportedMedia(dest, name, type)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** Removes an imported original nothing refers to (a draft that was abandoned). */
    fun discardIfUnused(file: File) {
        if (_records.value.none { it.originalFile == file.absolutePath }) file.delete()
    }

    @Synchronized
    fun add(record: MediaRecord) {
        _records.value = listOf(record) + _records.value
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        val rec = find(id) ?: return
        _records.value = _records.value.filter { it.id != id }
        persist()
        File(rec.resultFile).delete()
        File(rec.thumbFile).delete()
        File(rec.peaksIn).delete()
        File(rec.peaksOut).delete()
        // Several versions can share one original.
        if (_records.value.none { it.originalFile == rec.originalFile }) File(rec.originalFile).delete()
    }

    @Synchronized
    fun clear() {
        _records.value = emptyList()
        persist()
        listOf(originals, results, thumbs, scratch).forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    fun bytesUsed(): Long = listOf(originals, results, thumbs).sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }

    private fun load(): List<MediaRecord> = runCatching {
        if (!index.exists()) return emptyList()
        val arr = JSONArray(index.readText())
        (0 until arr.length()).mapNotNull { runCatching { MediaRecord.fromJson(arr.getJSONObject(it)) }.getOrNull() }
            .filter { File(it.resultFile).exists() }
    }.getOrDefault(emptyList())

    private fun persist() {
        val arr = JSONArray()
        _records.value.forEach { arr.put(it.toJson()) }
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(index)) {
            index.delete()
            tmp.renameTo(index)
        }
    }
}

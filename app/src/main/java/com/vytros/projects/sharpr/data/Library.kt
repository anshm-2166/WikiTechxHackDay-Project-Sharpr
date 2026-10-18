package com.vytros.projects.sharpr.data

import android.content.Context
import android.net.Uri
import com.vytros.projects.sharpr.engine.UpscaleOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class UpscaleRecord(
    val id: String,
    val name: String,
    val createdAt: Long,
    val srcW: Int,
    val srcH: Int,
    val outW: Int,
    val outH: Int,
    val options: UpscaleOptions,
    val backend: String,
    val durationMs: Long,
    val originalFile: String,
    val resultFile: String,
    val thumbFile: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("createdAt", createdAt)
        .put("srcW", srcW).put("srcH", srcH).put("outW", outW).put("outH", outH)
        .put("options", options.toJson()).put("backend", backend).put("durationMs", durationMs)
        .put("originalFile", originalFile).put("resultFile", resultFile).put("thumbFile", thumbFile)

    companion object {
        fun fromJson(o: JSONObject) = UpscaleRecord(
            id = o.getString("id"),
            name = o.optString("name", "Image"),
            createdAt = o.optLong("createdAt"),
            srcW = o.optInt("srcW"), srcH = o.optInt("srcH"),
            outW = o.optInt("outW"), outH = o.optInt("outH"),
            options = UpscaleOptions.fromJson(o.getJSONObject("options")),
            backend = o.optString("backend"),
            durationMs = o.optLong("durationMs"),
            originalFile = o.getString("originalFile"),
            resultFile = o.getString("resultFile"),
            thumbFile = o.getString("thumbFile"),
        )
    }
}

/** A picked image that was copied into app storage and is ready to be upscaled. */
data class ImportedImage(val file: File, val name: String)

/**
 * File-backed history of upscales. A small JSON index keeps this free of any annotation
 * processing; images live in originals/, results/ and thumbs/ under filesDir.
 */
class LibraryStore(private val context: Context) {
    private val root = File(context.filesDir, "library").apply { mkdirs() }
    val originals = File(root, "originals").apply { mkdirs() }
    val results = File(root, "results").apply { mkdirs() }
    val thumbs = File(root, "thumbs").apply { mkdirs() }
    private val index = File(root, "index.json")

    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<UpscaleRecord>> = _records

    fun find(id: String?): UpscaleRecord? = id?.let { i -> _records.value.firstOrNull { it.id == i } }

    fun newId(): String = UUID.randomUUID().toString()

    /** Copies a picked Uri into originals/ so it can be regenerated later. */
    fun importUri(uri: Uri): ImportedImage {
        val name = displayName(uri) ?: "Image"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "webp", "bmp", "heic") } ?: "jpg"
        val dest = File(originals, "${newId()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: error("Couldn't open that image")
        return ImportedImage(dest, name.substringBeforeLast('.'))
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    fun add(record: UpscaleRecord) {
        _records.value = listOf(record) + _records.value
        persist()
    }

    fun remove(id: String) {
        val rec = find(id) ?: return
        _records.value = _records.value.filter { it.id != id }
        persist()
        File(rec.resultFile).delete()
        File(rec.thumbFile).delete()
        // Several versions can share one original.
        if (_records.value.none { it.originalFile == rec.originalFile }) File(rec.originalFile).delete()
    }

    fun clear() {
        _records.value = emptyList()
        persist()
        listOf(originals, results, thumbs).forEach { dir -> dir.listFiles()?.forEach { it.delete() } }
    }

    private fun load(): List<UpscaleRecord> = runCatching {
        if (!index.exists()) return emptyList()
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { UpscaleRecord.fromJson(arr.getJSONObject(it)) }
            .filter { File(it.resultFile).exists() }
    }.getOrDefault(emptyList())

    private fun persist() {
        val arr = JSONArray()
        _records.value.forEach { arr.put(it.toJson()) }
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }
}

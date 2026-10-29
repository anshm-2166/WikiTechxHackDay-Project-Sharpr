package com.vytros.projects.sharpr.engine

import com.vytros.projects.sharpr.data.LibraryStore
import com.vytros.projects.sharpr.data.MediaRecord
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.engine.media.AudioCodecs
import com.vytros.projects.sharpr.engine.media.PcmInfo
import com.vytros.projects.sharpr.engine.media.Waveform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Locale

/** What the native clean-up reported. */
data class AudioResult(val info: PcmInfo, val humHz: Int, val inputLufs: Double, val outputLufs: Double) {
    fun describe(o: AudioOptions): String = buildList {
        if (humHz > 0) add("$humHz Hz hum removed")
        if (o.normalize && outputLufs > -69) add(String.format(Locale.US, "%.0f → %.0f LUFS", inputLufs, outputLufs))
    }.joinToString(" · ")
}

/**
 * decode (MediaCodec) -> 48 kHz clean-up (native: RNNoise / spectral / de-hum / EQ / loudness) -> encode.
 */
class AudioPipeline(private val library: LibraryStore) {

    /**
     * Raw PCM in, raw 48 kHz PCM out. Shared with the video pipeline for "clean audio".
     * [onProgress] receives 0..1.
     */
    suspend fun clean(inPcm: File, inInfo: PcmInfo, outPcm: File, o: AudioOptions, onProgress: (Float) -> Unit): AudioResult {
        val listener = object : NativeListener {
            override fun onProgress(done: Int, total: Int) = onProgress(done.toFloat() / total.coerceAtLeast(1))
            override fun onBackend(name: String, fallback: Boolean) = Unit
        }
        NativeBridge.resetCancel()
        val r = NativeBridge.processAudio(
            inPcm.absolutePath, outPcm.absolutePath, inInfo.sampleRate, inInfo.channels, o.mode.id,
            o.strength / 100f, o.hum.id, o.highpass, o.clarity / 100f, o.normalize, TARGET_LUFS, listener,
        )
        currentCoroutineContext().ensureActive()
        val rc = r[0].toInt()
        if (rc == NativeBridge.RC_CANCELLED) throw CancellationException("Cancelled")
        if (rc != NativeBridge.RC_OK) throw UpscaleException(NativeBridge.describe(rc))
        return AudioResult(PcmInfo(r[1].toInt(), r[2].toInt(), r[3].toLong()), r[4].toInt(), r[5], r[6])
    }

    suspend fun run(source: File, name: String, o: AudioOptions, progress: JobProgress): MediaRecord {
        val started = System.currentTimeMillis()
        val id = library.newId()
        val work = File(library.scratch, id).apply { mkdirs() }
        try {
            val ctx = currentCoroutineContext()
            val keepGoing = { ctx.isActive }

            progress.stage("Decoding", 0f)
            val inPcm = File(work, "in.pcm")
            val inInfo = AudioCodecs.decodeToPcm(source, inPcm, onProgress = { progress.stage("Decoding", 0.15f * it) }, keepGoing = keepGoing)
            if (inInfo.frames <= 0) throw UpscaleException("That file has no audio samples.")
            currentCoroutineContext().ensureActive()

            val thumb = File(library.thumbs, "$id.audio")
            thumb.writeText(name)
            Waveform.fromPcm(inPcm, inInfo.channels, File("${thumb.absolutePath}.in.peaks"))

            progress.backend("CPU · 48 kHz DSP", false)
            val outPcm = File(work, "out.pcm")
            val result = clean(inPcm, inInfo, outPcm, o) { f ->
                progress.stage(if (f < 0.3f) "Listening" else "Cleaning", 0.15f + 0.7f * f)
            }
            inPcm.delete()
            Waveform.fromPcm(outPcm, result.info.channels, File("${thumb.absolutePath}.out.peaks"))

            progress.stage("Encoding", 0.86f)
            val ext = if (o.format == "Wav") "wav" else "m4a"
            val out = File(library.results, "$id.$ext")
            if (ext == "wav") {
                AudioCodecs.writeWav(outPcm, result.info, out)
            } else {
                AudioCodecs.encodeM4a(outPcm, result.info, out, onProgress = { progress.stage("Encoding", 0.86f + 0.13f * it) }, keepGoing = keepGoing)
            }
            currentCoroutineContext().ensureActive()

            return MediaRecord(
                id = id,
                type = MediaType.Audio,
                name = name,
                createdAt = System.currentTimeMillis(),
                srcW = inInfo.sampleRate, srcH = inInfo.channels,
                outW = result.info.sampleRate, outH = result.info.channels,
                backend = "CPU · 48 kHz DSP",
                durationMs = System.currentTimeMillis() - started,
                originalFile = source.absolutePath,
                resultFile = out.absolutePath,
                thumbFile = thumb.absolutePath,
                mediaMs = inInfo.durationUs / 1000,
                audio = o,
                note = result.describe(o),
            )
        } catch (e: InterruptedException) {
            throw CancellationException("Cancelled")
        } finally {
            work.deleteRecursively()
        }
    }

    companion object {
        const val TARGET_LUFS = -16f
    }
}

package com.vytros.projects.sharpr.engine

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGLSurface
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.LibraryStore
import com.vytros.projects.sharpr.data.MediaRecord
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.data.VideoCodec
import com.vytros.projects.sharpr.data.VideoQuality
import com.vytros.projects.sharpr.engine.media.AudioCodecs
import com.vytros.projects.sharpr.engine.media.EglCore
import com.vytros.projects.sharpr.engine.media.FrameReader
import com.vytros.projects.sharpr.engine.media.FrameWriter
import com.vytros.projects.sharpr.engine.media.MediaProbe
import com.vytros.projects.sharpr.engine.media.VideoCaps
import com.vytros.projects.sharpr.engine.media.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Frame-by-frame video super-resolution.
 *
 *   MediaExtractor -> hardware decoder -> SurfaceTexture -> GL readback (RGBA)
 *     -> ncnn (GPU/CPU) -> GL upload -> encoder input surface -> hardware encoder -> MediaMuxer
 *
 * The soundtrack is copied untouched (or cleaned by the audio engine) and interleaved by timestamp.
 * Frames that barely changed since the last processed one reuse its result.
 */
class VideoPipeline(
    private val library: LibraryStore,
    private val sessions: SessionCache,
    private val audio: AudioPipeline,
) {
    /** EGL contexts belong to one thread, so all of a job's GL work happens here. */
    private val glThread = Executors.newSingleThreadExecutor { r -> Thread(r, "sharpr-video").apply { priority = Thread.MAX_PRIORITY - 1 } }
        .asCoroutineDispatcher()

    data class Plan(val scale: Int, val mime: String, val outW: Int, val outH: Int, val note: String?)

    companion object {
        /** Picks the largest scale <= requested that the device can encode, and the codec to use. */
        fun plan(info: VideoInfo, o: VideoOptions, codec: VideoCodec): Plan? {
            var mime = codec.mime
            var note: String? = null
            if (!VideoCaps.hasEncoder(mime)) {
                mime = MediaFormat.MIMETYPE_VIDEO_AVC
                note = "This device has no ${codec.label} encoder, so H.264 is used."
            }
            val scales = o.model.scales.filter { it <= o.scale }.sortedDescending()
            for (s in scales) {
                val w = VideoCaps.even(info.width * s)
                val h = VideoCaps.even(info.height * s)
                if (VideoCaps.supports(mime, w, h, info.fps)) {
                    if (s < o.scale) note = listOfNotNull(note, "Scale lowered to ${s}× — the largest this device's encoder accepts.").joinToString(" ")
                    return Plan(s, mime, w, h, note)
                }
            }
            return null
        }

        private const val FORCE_REFRESH_EVERY = 24
        private const val TIMEOUT_US = 5_000L
    }

    suspend fun run(source: File, name: String, o: VideoOptions, settings: AppSettings, progress: JobProgress): MediaRecord {
        val started = System.currentTimeMillis()
        progress.stage("Reading video", 0f)
        val info = MediaProbe.video(source) ?: throw UpscaleException("That file doesn't contain a video track Sharpr can read.")
        val codec = runCatching { VideoCodec.valueOf(o.codec) }.getOrDefault(settings.videoCodec)
        val plan = plan(info, o, codec)
            ?: throw UpscaleException("This device can't encode a video that large. Try a smaller scale.")
        val endUs = if (o.previewSeconds > 0) o.previewSeconds * 1_000_000L else Long.MAX_VALUE
        val clipUs = minOf(info.durationUs, endUs).coerceAtLeast(1)
        val totalFrames = max(1L, (clipUs / 1_000_000.0 * info.fps).toLong())

        val id = library.newId()
        val work = File(library.scratch, id).apply { mkdirs() }
        try {
            // ---- soundtrack ---------------------------------------------------------------------------
            var audioNote = ""
            val audioFile: File? = if (!info.hasAudio) null else {
                val passthrough = !o.cleanAudio && info.audioMime == MediaFormat.MIMETYPE_AUDIO_AAC
                if (passthrough) source else {
                    progress.stage(if (o.cleanAudio) "Cleaning audio" else "Converting audio", 0.01f)
                    val ctx = currentCoroutineContext()
                    val pcm = File(work, "a.pcm")
                    val pcmInfo = AudioCodecs.decodeToPcm(source, pcm, endUs, keepGoing = { ctx.isActive })
                    val clean = File(work, "a.clean.pcm")
                    val ao = if (o.cleanAudio) {
                        AudioOptions(mode = AudioMode.Universal, strength = 55, clarity = 10, normalize = false)
                    } else {
                        AudioOptions(mode = AudioMode.Off, hum = HumFilter.Off, highpass = false, clarity = 0, normalize = false)
                    }
                    val r = audio.clean(pcm, pcmInfo, clean, ao) { f -> progress.stage("Cleaning audio", 0.01f + 0.04f * f) }
                    if (o.cleanAudio) audioNote = listOf("audio cleaned", r.describe(ao)).filter { it.isNotEmpty() }.joinToString(" · ")
                    pcm.delete()
                    val m4a = File(work, "a.m4a")
                    AudioCodecs.encodeM4a(clean, r.info, m4a, keepGoing = { ctx.isActive })
                    clean.delete()
                    m4a
                }
            }
            currentCoroutineContext().ensureActive()

            // ---- network ------------------------------------------------------------------------------
            progress.stage("Loading ${o.model.label}", 0.05f)
            val session = sessions.acquire(o.model.net(plan.scale, o.aiDenoise), settings, tta = false)

            val out = File(library.results, "$id.mp4")
            val thumb = File(library.thumbs, "$id.jpg")
            val stats = withContext(glThread) {
                transcode(source, info, plan, endUs, totalFrames, session, o, settings.videoQuality, audioFile, out, thumb, progress)
            }

            val note = buildList {
                if (stats.reused > 0) add("${stats.reused} repeated frames reused")
                if (audioNote.isNotEmpty()) add(audioNote)
                if (o.previewSeconds > 0) add("${o.previewSeconds} s preview")
                plan.note?.let { add(it) }
            }.joinToString(" · ")

            return MediaRecord(
                id = id,
                type = MediaType.Video,
                name = name,
                createdAt = System.currentTimeMillis(),
                srcW = info.width, srcH = info.height,
                outW = plan.outW, outH = plan.outH,
                backend = stats.backend,
                durationMs = System.currentTimeMillis() - started,
                originalFile = source.absolutePath,
                resultFile = out.absolutePath,
                thumbFile = thumb.absolutePath,
                mediaMs = stats.lastPtsUs / 1000,
                video = o.copy(scale = plan.scale, codec = if (plan.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "Hevc" else "H264"),
                note = note,
            )
        } catch (e: InterruptedException) {
            throw CancellationException("Cancelled")
        } finally {
            work.deleteRecursively()
        }
    }

    private class Stats(var frames: Long = 0, var reused: Long = 0, var lastPtsUs: Long = 0, var backend: String = "")

    /** Runs on [glThread]. */
    private suspend fun transcode(
        source: File, info: VideoInfo, plan: Plan, endUs: Long, totalFrames: Long, session: SrSession, o: VideoOptions,
        quality: VideoQuality, audioFile: File?, out: File, thumb: File, progress: JobProgress,
    ): Stats {
        val stats = Stats(backend = session.device)
        val w = info.width
        val h = info.height
        val fw = w * plan.scale
        val fh = h * plan.scale
        val inBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(fw * fh * 4).order(ByteOrder.nativeOrder())

        val extractor = MediaExtractor().apply { setDataSource(source.absolutePath) }
        val videoTrack = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        extractor.selectTrack(videoTrack)
        val inFormat = extractor.getTrackFormat(videoTrack).apply {
            // Decode unrotated; the muxer carries the rotation as metadata instead.
            if (containsKey(MediaFormat.KEY_ROTATION)) setInteger(MediaFormat.KEY_ROTATION, 0)
        }

        val audioEx = audioFile?.let { f ->
            MediaExtractor().apply {
                setDataSource(f.absolutePath)
                val t = AudioCodecs.audioTrack(this)
                if (t < 0) {
                    release()
                    return@let null
                }
                selectTrack(t)
            }
        }
        val audioFormat = audioEx?.let { it.getTrackFormat(AudioCodecs.audioTrack(it)) }

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(((info.rotation % 360) + 360) % 360)
        }
        var muxing = false
        var videoTrackOut = -1
        var audioTrackOut = -1
        val audioBuf = ByteBuffer.allocateDirect(
            audioFormat?.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)?.coerceAtLeast(1 shl 16) ?: (1 shl 18),
        )
        val audioInfo = MediaCodec.BufferInfo()
        var audioDone = audioEx == null

        fun writeAudioUpTo(ptsUs: Long) {
            val ex = audioEx ?: return
            while (!audioDone && muxing) {
                val t = ex.sampleTime
                if (t < 0 || t > endUs) {
                    audioDone = true
                    break
                }
                if (t > ptsUs) break
                audioBuf.clear()
                val n = ex.readSampleData(audioBuf, 0)
                if (n < 0) {
                    audioDone = true
                    break
                }
                audioInfo.set(0, n, t, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(audioTrackOut, audioBuf, audioInfo)
                ex.advance()
            }
        }

        val encoder = createEncoder(plan, info, quality)
        val egl = EglCore()
        var encSurface: EGLSurface? = null
        var reader: FrameReader? = null
        var writer: FrameWriter? = null
        var decoder: MediaCodec? = null
        val inputSurface = encoder.createInputSurface()
        try {
            encSurface = egl.windowSurface(inputSurface)
            egl.makeCurrent(encSurface)
            reader = FrameReader(w, h)
            writer = FrameWriter(plan.outW, plan.outH, fw, fh)
            encoder.start()

            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw UpscaleException("This device can't decode ${info.codecLabel} video.")
            }
            decoder.configure(inFormat, reader.surface, null, 0)
            decoder.start()

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var prevSig: FloatArray? = null
            var sinceFresh = 0
            var haveResult = false
            val inferStart = System.nanoTime()
            NativeBridge.resetCancel()

            val listener = object : NativeListener {
                override fun onProgress(done: Int, total: Int) {
                    val f = (stats.frames + done.toFloat() / max(1, total)) / totalFrames
                    progress.stage("Sharpening", 0.06f + 0.92f * f.coerceIn(0f, 1f), frameDetail(stats, totalFrames, inferStart))
                }

                override fun onBackend(name: String, fallback: Boolean) {
                    stats.backend = name
                    progress.backend(name, fallback)
                }
            }

            fun drainEncoder(endOfStream: Boolean) {
                var idle = 0
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(encInfo, if (endOfStream) 10_000L else 0L)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (++idle > 500) throw UpscaleException("The video encoder stopped responding.")
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxing) { "Encoder format changed twice" }
                            videoTrackOut = muxer.addTrack(encoder.outputFormat)
                            if (audioFormat != null) audioTrackOut = muxer.addTrack(audioFormat)
                            muxer.start()
                            muxing = true
                        }
                        idx >= 0 -> {
                            idle = 0
                            val buf = encoder.getOutputBuffer(idx)!!
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                            if (encInfo.size > 0 && muxing) {
                                buf.position(encInfo.offset)
                                buf.limit(encInfo.offset + encInfo.size)
                                writeAudioUpTo(encInfo.presentationTimeUs)
                                muxer.writeSampleData(videoTrackOut, buf, encInfo)
                                stats.lastPtsUs = max(stats.lastPtsUs, encInfo.presentationTimeUs)
                            }
                            encoder.releaseOutputBuffer(idx, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderDone = true
                                return
                            }
                        }
                    }
                }
            }

            while (!encoderDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = decoder.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                if (!decoderDone) {
                    val o2 = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (o2 >= 0) {
                        val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val pts = decInfo.presentationTimeUs
                        val render = decInfo.size > 0 && pts <= endUs
                        decoder.releaseOutputBuffer(o2, render)
                        if (render) {
                            if (!reader.await()) throw UpscaleException("The video decoder stopped delivering frames.")
                            reader.read(inBuf)

                            val sig = signature(inBuf, w, h)
                            val reuse = o.skipDuplicates && haveResult && sinceFresh < FORCE_REFRESH_EVERY &&
                                prevSig != null && similar(sig, prevSig)
                            if (reuse) {
                                stats.reused++
                                sinceFresh++
                            } else {
                                val rc = NativeBridge.processBuffer(session.handle, inBuf, w, h, outBuf, plan.scale, listener)
                                when (rc) {
                                    NativeBridge.RC_OK -> Unit
                                    NativeBridge.RC_CANCELLED -> throw CancellationException("Cancelled")
                                    else -> throw UpscaleException(NativeBridge.describe(rc))
                                }
                                prevSig = sig
                                sinceFresh = 0
                                if (!haveResult) saveThumb(outBuf, fw, fh, thumb)
                                haveResult = true
                            }
                            writer.draw(outBuf)
                            egl.swap(encSurface, pts * 1000L)
                            stats.frames++
                            progress.stage(
                                "Sharpening", 0.06f + 0.92f * (stats.frames.toFloat() / totalFrames).coerceIn(0f, 1f),
                                frameDetail(stats, totalFrames, inferStart),
                            )
                            drainEncoder(false)
                        }
                        if (eos || pts > endUs) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                } else {
                    drainEncoder(true)
                }
            }
            if (!muxing) throw UpscaleException("No frames could be decoded from this video.")
            writeAudioUpTo(Long.MAX_VALUE)
            progress.stage("Finishing", 0.99f)
            return stats
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            runCatching { encoder.stop() }
            encoder.release()
            reader?.release()
            writer?.release()
            egl.release(encSurface)
            inputSurface.release()
            extractor.release()
            audioEx?.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun createEncoder(plan: Plan, info: VideoInfo, quality: VideoQuality): MediaCodec {
        val fps = info.fps.coerceIn(1f, 120f)
        val bitsPerPixel = quality.bitsPerPixel * if (plan.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.7f else 1f
        val bitrate = (plan.outW.toLong() * plan.outH * fps * bitsPerPixel).toLong()
            .coerceIn(1_500_000L, VideoCaps.maxBitrate(plan.mime).toLong()).toInt()
        fun format(highProfile: Boolean) = MediaFormat.createVideoFormat(plan.mime, plan.outW, plan.outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setFloat(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (highProfile) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        }
        val wantHigh = VideoCaps.supportsHighProfile(plan.mime)
        val codec = MediaCodec.createEncoderByType(plan.mime)
        try {
            codec.configure(format(wantHigh), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return codec
        } catch (e: Exception) {
            if (!wantHigh) {
                codec.release()
                throw UpscaleException("The video encoder refused ${plan.outW}×${plan.outH}. Try a smaller scale.")
            }
        }
        codec.reset()
        try {
            codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            codec.release()
            throw UpscaleException("The video encoder refused ${plan.outW}×${plan.outH}. Try a smaller scale.")
        }
        return codec
    }

    private fun frameDetail(stats: Stats, total: Long, startNs: Long): String {
        val done = stats.frames
        val base = String.format(Locale.US, "Frame %,d of %,d", done, total)
        if (done < 2) return base
        val perFrame = (System.nanoTime() - startNs) / 1e9 / done
        val left = ((total - done).coerceAtLeast(0) * perFrame).toLong()
        val eta = when {
            left >= 3600 -> String.format(Locale.US, "%dh %02dm left", left / 3600, (left % 3600) / 60)
            left >= 60 -> String.format(Locale.US, "%dm %02ds left", left / 60, left % 60)
            else -> "${left}s left"
        }
        val speed = if (perFrame >= 1) String.format(Locale.US, "%.1f s/frame", perFrame) else String.format(Locale.US, "%.1f fps", 1 / perFrame)
        return "$base · $speed · $eta"
    }

    /** 64x36 luma thumbnail of a frame. */
    private fun signature(buf: ByteBuffer, w: Int, h: Int): FloatArray {
        val gw = 64
        val gh = 36
        val out = FloatArray(gw * gh)
        for (gy in 0 until gh) {
            val y = (gy * 2 + 1) * h / (gh * 2)
            for (gx in 0 until gw) {
                val x = (gx * 2 + 1) * w / (gw * 2)
                val i = (y * w + x) * 4
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                out[gy * gw + gx] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
        return out
    }

    /** True when two frames differ by less than compression noise (no motion, no fade). */
    private fun similar(a: FloatArray, b: FloatArray): Boolean {
        var sum = 0f
        var worst = 0f
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            sum += d
            if (d > worst) worst = d
        }
        return sum / a.size < 0.8f && worst < 14f
    }

    private fun saveThumb(buf: ByteBuffer, w: Int, h: Int, file: File) {
        runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.position(0)
            bmp.copyPixelsFromBuffer(buf)
            buf.position(0)
            Thumbs.fromBitmap(bmp, file)
            bmp.recycle()
        }
    }
}

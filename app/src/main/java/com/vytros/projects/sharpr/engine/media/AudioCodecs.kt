package com.vytros.projects.sharpr.engine.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Raw interleaved s16le PCM on disk. */
data class PcmInfo(val sampleRate: Int, val channels: Int, val frames: Long) {
    val durationUs: Long get() = if (sampleRate > 0) frames * 1_000_000L / sampleRate else 0
}

class MediaFormatException(message: String) : Exception(message)

object AudioCodecs {
    private const val TIMEOUT_US = 10_000L

    fun audioTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: -1

    /**
     * Decodes one audio track to raw PCM. Stops after [endUs] (for previews). [onProgress] gets 0..1;
     * return false from [keepGoing] to abort.
     */
    fun decodeToPcm(
        source: File, out: File, endUs: Long = Long.MAX_VALUE,
        onProgress: (Float) -> Unit = {}, keepGoing: () -> Boolean = { true },
    ): PcmInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track = audioTrack(extractor)
            if (track < 0) throw MediaFormatException("There's no audio track in this file.")
            return decodeTrack(extractor, track, out, endUs, onProgress, keepGoing)
        } finally {
            extractor.release()
        }
    }

    fun decodeTrack(
        extractor: MediaExtractor, track: Int, out: File, endUs: Long,
        onProgress: (Float) -> Unit, keepGoing: () -> Boolean,
    ): PcmInfo {
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw MediaFormatException("Unknown audio format.")
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val total = min(duration, endUs).takeIf { it > 0 } ?: 1L
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw MediaFormatException("This device can't decode $mime audio.")
        }
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var bytes = 0L
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var scratch = ByteArray(0)
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { sink ->
                while (!outputDone) {
                    if (!keepGoing()) throw InterruptedException()
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0 || extractor.sampleTime > endUs) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        if (bytes == 0L) {
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val floats = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                val n = floats.remaining()
                                if (scratch.size < n * 2) scratch = ByteArray(n * 2)
                                for (k in 0 until n) {
                                    val s = (floats.get().coerceIn(-1f, 1f) * 32767f).roundToInt()
                                    scratch[k * 2] = (s and 0xFF).toByte()
                                    scratch[k * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                }
                                sink.write(scratch, 0, n * 2)
                                bytes += n * 2
                            } else {
                                if (scratch.size < info.size) scratch = ByteArray(info.size)
                                buf.get(scratch, 0, info.size)
                                sink.write(scratch, 0, info.size)
                                bytes += info.size
                            }
                            onProgress((info.presentationTimeUs.toFloat() / total).coerceIn(0f, 1f))
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.unselectTrack(track)
        }
        return PcmInfo(rate, channels, bytes / (2L * channels))
    }

    /** AAC-LC in an MP4 container. */
    fun encodeM4a(
        pcm: File, info: PcmInfo, out: File, bitrate: Int = 192_000,
        onProgress: (Float) -> Unit = {}, keepGoing: () -> Boolean = { true },
    ) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, info.sampleRate, info.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate * info.channels / 2)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val frameBytes = 2 * info.channels
            val totalBytes = info.frames * frameBytes
            var readBytes = 0L
            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val chunk = ByteArray(8192 - 8192 % frameBytes)
            BufferedInputStream(FileInputStream(pcm), 1 shl 16).use { src ->
                while (!outputDone) {
                    if (!keepGoing()) throw InterruptedException()
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            buf.clear()
                            val want = min(chunk.size, buf.capacity() - buf.capacity() % frameBytes)
                            val n = readFully(src, chunk, want)
                            val pts = readBytes / frameBytes * 1_000_000L / info.sampleRate
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.put(chunk, 0, n)
                                codec.queueInputBuffer(i, 0, n, pts, 0)
                                readBytes += n
                                onProgress((readBytes.toFloat() / max(1L, totalBytes)).coerceIn(0f, 1f))
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufInfo.size > 0 && muxing) {
                            buf.position(bufInfo.offset)
                            buf.limit(bufInfo.offset + bufInfo.size)
                            muxer.writeSampleData(track, buf, bufInfo)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /** 16-bit PCM WAV. */
    fun writeWav(pcm: File, info: PcmInfo, out: File) {
        val dataBytes = pcm.length()
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt())
            h.put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1)
            h.putShort(info.channels.toShort()).putInt(info.sampleRate).putInt(info.sampleRate * info.channels * 2)
            h.putShort((info.channels * 2).toShort()).putShort(16)
            h.put("data".toByteArray()).putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
            raf.write(h.array())
            FileInputStream(pcm).use { src ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                }
            }
        }
    }

    private fun readFully(src: BufferedInputStream, buf: ByteArray, want: Int): Int {
        var got = 0
        while (got < want) {
            val n = src.read(buf, got, want - got)
            if (n <= 0) break
            got += n
        }
        return got
    }
}

/** Compact waveform: one byte (0..255 of full scale) per bucket. */
object Waveform {
    const val BUCKETS = 480

    fun fromPcm(pcm: File, channels: Int, out: File, buckets: Int = BUCKETS) {
        val frames = pcm.length() / (2L * channels)
        val peaks = ByteArray(buckets)
        if (frames > 0) {
            val perBucket = max(1L, (frames + buckets - 1) / buckets)
            java.io.DataInputStream(BufferedInputStream(FileInputStream(pcm), 1 shl 16)).use { src ->
                val buf = ByteArray(2 * channels * 2048)
                var sample = 0L
                var peak = 0
                while (true) {
                    val n = readFully(src, buf, buf.size)
                    if (n < 2) break
                    var i = 0
                    while (i + 1 < n) {
                        val s = abs((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8))
                        if (s > peak) peak = s
                        sample++
                        if (sample % (perBucket * channels) == 0L) {
                            val b = (sample / (perBucket * channels) - 1).toInt()
                            if (b in 0 until buckets) peaks[b] = min(255, peak * 255 / 32767).toByte()
                            peak = 0
                        }
                        i += 2
                    }
                }
                val last = (sample / (perBucket * channels)).toInt()
                if (peak > 0 && last in 0 until buckets) peaks[last] = min(255, peak * 255 / 32767).toByte()
            }
        }
        out.writeBytes(peaks)
    }

    private fun readFully(src: java.io.InputStream, buf: ByteArray, want: Int): Int {
        var got = 0
        while (got < want) {
            val n = src.read(buf, got, want - got)
            if (n <= 0) break
            got += n
        }
        return got
    }

    fun read(file: File): FloatArray? = runCatching {
        val b = file.readBytes()
        FloatArray(b.size) { (b[it].toInt() and 0xFF) / 255f }
    }.getOrNull()
}

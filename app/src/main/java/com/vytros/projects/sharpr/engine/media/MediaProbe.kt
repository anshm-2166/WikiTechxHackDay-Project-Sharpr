package com.vytros.projects.sharpr.engine.media

import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File

data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationUs: Long,
    val fps: Float,
    val mime: String,
    val hasAudio: Boolean,
    val audioMime: String?,
    val hdr: Boolean,
) {
    val frames: Long get() = (durationUs / 1_000_000.0 * fps).toLong().coerceAtLeast(1)
    val codecLabel: String
        get() = when {
            mime.contains("avc") -> "H.264"
            mime.contains("hevc") -> "HEVC"
            mime.contains("av01") -> "AV1"
            mime.contains("vp9") -> "VP9"
            mime.contains("vp8") -> "VP8"
            else -> mime.substringAfter('/')
        }
}

data class AudioInfo(val sampleRate: Int, val channels: Int, val durationUs: Long, val mime: String)

object MediaProbe {
    fun video(file: File): VideoInfo? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (video == null && mime.startsWith("video/")) video = f
                if (audio == null && mime.startsWith("audio/")) audio = f
            }
            val v = video ?: return null
            val fps = when {
                v.containsKey(MediaFormat.KEY_FRAME_RATE) -> runCatching { v.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                    .getOrElse { v.getFloat(MediaFormat.KEY_FRAME_RATE) }
                else -> 30f
            }.takeIf { it in 1f..240f } ?: 30f
            val transfer = if (v.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) v.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
            VideoInfo(
                width = v.getInteger(MediaFormat.KEY_WIDTH),
                height = v.getInteger(MediaFormat.KEY_HEIGHT),
                rotation = if (v.containsKey(MediaFormat.KEY_ROTATION)) v.getInteger(MediaFormat.KEY_ROTATION) else 0,
                durationUs = if (v.containsKey(MediaFormat.KEY_DURATION)) v.getLong(MediaFormat.KEY_DURATION) else 0L,
                fps = fps,
                mime = v.getString(MediaFormat.KEY_MIME)!!,
                hasAudio = audio != null,
                audioMime = audio?.getString(MediaFormat.KEY_MIME),
                hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG,
            )
        } finally {
            ex.release()
        }
    }.getOrNull()

    fun audio(file: File): AudioInfo? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val t = AudioCodecs.audioTrack(ex)
            if (t < 0) return null
            val f = ex.getTrackFormat(t)
            AudioInfo(
                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L,
                mime = f.getString(MediaFormat.KEY_MIME) ?: "audio/*",
            )
        } finally {
            ex.release()
        }
    }.getOrNull()

    /** A frame for previews; [atUs] < 0 picks one a little into the clip. */
    fun frame(file: File, atUs: Long = -1, maxSide: Int = 1280): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val t = if (atUs >= 0) atUs else (dur * 1000 / 10).coerceAtMost(2_000_000)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val k = maxSide.toFloat() / maxOf(w, h, 1)
            if (k < 1f && Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (w * k).toInt(), (h * k).toInt())
            } else {
                val full = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
                if (k >= 1f) full else Bitmap.createScaledBitmap(full, (full.width * k).toInt().coerceAtLeast(1), (full.height * k).toInt().coerceAtLeast(1), true)
                    .also { if (it !== full) full.recycle() }
            }
        } finally {
            r.release()
        }
    }.getOrNull()
}

object VideoCaps {
    private val codecs by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder } }

    private fun encoders(mime: String): List<MediaCodecInfo> =
        codecs.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

    fun hasEncoder(mime: String): Boolean = encoders(mime).isNotEmpty()

    /** Some encoder for [mime] takes this size at this frame rate. */
    fun supports(mime: String, width: Int, height: Int, fps: Float): Boolean = encoders(mime).any { info ->
        runCatching {
            val v = info.getCapabilitiesForType(mime).videoCapabilities
            v.areSizeAndRateSupported(width, height, fps.toDouble()) ||
                (v.isSizeSupported(width, height) && fps <= 60f)
        }.getOrDefault(false)
    }

    fun maxBitrate(mime: String): Int = encoders(mime).maxOfOrNull {
        runCatching { it.getCapabilitiesForType(mime).videoCapabilities.bitrateRange.upper }.getOrDefault(20_000_000)
    } ?: 20_000_000

    fun supportsHighProfile(mime: String): Boolean = mime == MediaFormat.MIMETYPE_VIDEO_AVC && encoders(mime).any { info ->
        runCatching {
            info.getCapabilitiesForType(mime).profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
        }.getOrDefault(false)
    }

    /** Even dimensions; most hardware encoders also prefer multiples of 2 at minimum. */
    fun even(v: Int) = v - (v and 1)
}

package com.vytros.projects.sharpr.engine

import org.json.JSONObject

/**
 * How one ncnn network eats and returns pixels. The geometry (crop, alignment) was measured for every
 * model; the desktop tests in app/src/test/native keep it honest.
 */
data class NetSpec(
    val base: String,
    val inBlob: String,
    val outBlob: String,
    /** The network's native scale. Smaller requested scales are box-averaged down from it. */
    val scale: Int,
    /** Input pixels the network trims from each side. */
    val crop: Int = 0,
    /** Context fed around each tile. */
    val prepad: Int = 10,
    val align: Int = 1,
    /** Auto tile size multiplier, in percent: compact networks afford bigger tiles. */
    val tilePct: Int = 100,
    /** Predicts a residual on top of a nearest-neighbour upscale (Real-CUGAN 4x). */
    val residual: Boolean = false,
) {
    val param get() = "models/$base.param"
    val bin get() = "models/$base.bin"
}

enum class ModelFamily { General, Photo, Anime, Restore }

/**
 * The models Sharpr ships. Names of the first three are persisted (settings and library), keep them.
 * [speed] and [detail] are 1..5 ratings shown in the picker.
 */
enum class ModelId(
    val label: String,
    val blurb: String,
    val family: ModelFamily,
    val scales: List<Int>,
    val speed: Int,
    val detail: Int,
    /** Has a variant that also removes noise and compression artefacts. */
    val hasDenoiseVariant: Boolean = false,
    val source: String,
) {
    Fast(
        "Fast", "Compact Real-ESRGAN. Quick, clean, great for everything and the best pick for video.",
        ModelFamily.General, listOf(1, 2, 3, 4), speed = 5, detail = 3, source = "Real-ESRGAN animevideo v3 (BSD-3)",
    ),
    Photo(
        "Photo Ultra", "Full Real-ESRGAN. The most detail for real-world photos. Heavy.",
        ModelFamily.Photo, listOf(1, 2, 4), speed = 1, detail = 5, source = "Real-ESRGAN x4plus (BSD-3)",
    ),
    PhotoLite(
        "Photo Lite", "Gentle 2x for photos that should stay natural. Can lift JPEG noise too.",
        ModelFamily.Photo, listOf(1, 2), speed = 4, detail = 2, hasDenoiseVariant = true, source = "waifu2x UpConv7 photo (MIT)",
    ),
    Anime(
        "Anime HD", "Real-CUGAN. Crisp line art, flat colour and anime frames, up to 4x.",
        ModelFamily.Anime, listOf(1, 2, 3, 4), speed = 3, detail = 4, hasDenoiseVariant = true, source = "Real-CUGAN SE (MIT)",
    ),
    Art(
        "Illustration", "Real-ESRGAN tuned for drawings and digital art.",
        ModelFamily.Anime, listOf(1, 2, 4), speed = 2, detail = 4, source = "Real-ESRGAN x4plus anime (BSD-3)",
    ),
    Restore(
        "Restore", "Same size, cleaner: removes JPEG blocks, banding and noise without enlarging.",
        ModelFamily.Restore, listOf(1), speed = 3, detail = 3, hasDenoiseVariant = true, source = "waifu2x CUNet (MIT)",
    );

    /** The network to run for [scale]; [aiDenoise] picks the noise-removing variant where there is one. */
    fun net(scale: Int, aiDenoise: Boolean): NetSpec = when (this) {
        Fast -> {
            val s = if (scale > 1) scale else 4  // 1x = supersampled 4x averaged back down
            NetSpec("realesr-animevideov3-x$s", "data", "output", s, tilePct = 200)
        }
        Photo -> NetSpec("realesrgan-x4plus", "data", "output", 4)
        Art -> NetSpec("realesrgan-x4plus-anime", "data", "output", 4)
        PhotoLite -> NetSpec(
            if (aiDenoise) "waifu2x-photo-noise2-x2" else "waifu2x-photo-x2", "Input1", "Eltwise4", 2,
            crop = 7, prepad = 12, align = 2, tilePct = 200,
        )
        Anime -> when (scale) {
            3 -> NetSpec("realcugan-up3x-conservative", "in0", "out0", 3, crop = 14, prepad = 18, align = 4)
            4 -> NetSpec(
                if (aiDenoise) "realcugan-up4x-denoise3x" else "realcugan-up4x-conservative", "in0", "out0", 4,
                crop = 19, prepad = 23, align = 4, residual = true,
            )
            else -> NetSpec(
                if (aiDenoise) "realcugan-up2x-denoise3x" else "realcugan-up2x-conservative", "in0", "out0", 2,
                crop = 18, prepad = 22, align = 2,
            )
        }
        Restore -> NetSpec(
            if (aiDenoise) "waifu2x-cunet-noise3" else "waifu2x-cunet-noise1", "Input1", "Eltwise4", 1,
            crop = 28, prepad = 32, align = 4,
        )
    }

    /** The nearest scale this model supports. */
    fun fitScale(scale: Int): Int = if (scale in scales) scale else scales.minBy { kotlin.math.abs(it - scale) }

    /** Rough relative cost per output megapixel, used for time estimates (Fast = 1). */
    val cost: Float
        get() = when (this) {
            Fast -> 1f
            PhotoLite -> 1.4f
            Anime -> 3.5f
            Restore -> 3f
            Art -> 6f
            Photo -> 14f
        }

    companion object {
        fun parse(name: String?, default: ModelId = Fast): ModelId =
            entries.firstOrNull { it.name == name } ?: default

        /** Models that make sense frame by frame. */
        val forVideo = listOf(Fast, Anime, PhotoLite, Restore, Art, Photo)
    }
}

data class UpscaleOptions(
    val model: ModelId = ModelId.Fast,
    val scale: Int = 4,
    /** Use the model's own noise-removing variant. */
    val aiDenoise: Boolean = false,
    /** Classic pre-denoise strength, 0..100. */
    val denoise: Int = 0,
    /** Blend with a plain Lanczos upscale to tame invented texture, 0..100. */
    val natural: Int = 0,
    val sharpen: Int = 0,
    val grain: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("model", model.name).put("scale", scale).put("aiDenoise", aiDenoise).put("denoise", denoise)
        .put("natural", natural).put("sharpen", sharpen).put("grain", grain)

    companion object {
        fun fromJson(o: JSONObject?): UpscaleOptions {
            if (o == null) return UpscaleOptions()
            return UpscaleOptions(
                model = ModelId.parse(o.optString("model")),
                scale = o.optInt("scale", 4),
                aiDenoise = o.optBoolean("aiDenoise", false),
                denoise = o.optInt("denoise", 0),
                natural = o.optInt("natural", 0),
                sharpen = o.optInt("sharpen", 0),
                grain = o.optInt("grain", 0),
            )
        }
    }
}

data class VideoOptions(
    val model: ModelId = ModelId.Fast,
    val scale: Int = 2,
    val aiDenoise: Boolean = false,
    /** Reuse the previous result for frames that did not change (held anime frames, static shots). */
    val skipDuplicates: Boolean = true,
    /** Clean the soundtrack with the audio engine instead of copying it. */
    val cleanAudio: Boolean = false,
    /** Only the first few seconds, to try settings quickly. */
    val previewSeconds: Int = 0,
    val codec: String = "H264",
    val quality: String = "Standard",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("model", model.name).put("scale", scale).put("aiDenoise", aiDenoise)
        .put("skipDuplicates", skipDuplicates).put("cleanAudio", cleanAudio)
        .put("previewSeconds", previewSeconds).put("codec", codec).put("quality", quality)

    companion object {
        fun fromJson(o: JSONObject?): VideoOptions {
            if (o == null) return VideoOptions()
            return VideoOptions(
                model = ModelId.parse(o.optString("model")),
                scale = o.optInt("scale", 2),
                aiDenoise = o.optBoolean("aiDenoise", false),
                skipDuplicates = o.optBoolean("skipDuplicates", true),
                cleanAudio = o.optBoolean("cleanAudio", false),
                previewSeconds = o.optInt("previewSeconds", 0),
                codec = o.optString("codec", "H264"),
                quality = o.optString("quality", "Standard"),
            )
        }
    }
}

/** Mirrors the native DenoiseMode / HumMode. */
enum class AudioMode(val id: Int, val label: String, val blurb: String) {
    Voice(2, "Voice", "Neural noise suppression for speech: podcasts, calls, lectures, vlogs."),
    Universal(1, "Universal", "Spectral clean-up for any sound, music included: hiss, fans, room tone."),
    Off(0, "Enhance only", "No denoising; just the filters below."),
}

enum class HumFilter(val id: Int, val label: String) {
    Off(0, "Off"),
    Auto(1, "Auto"),
    Hz50(2, "50 Hz"),
    Hz60(3, "60 Hz"),
}

data class AudioOptions(
    val mode: AudioMode = AudioMode.Voice,
    /** 0..100 */
    val strength: Int = 70,
    val hum: HumFilter = HumFilter.Auto,
    val highpass: Boolean = true,
    /** 0..100 presence and air. */
    val clarity: Int = 25,
    val normalize: Boolean = true,
    val format: String = "M4a",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name).put("strength", strength).put("hum", hum.name).put("highpass", highpass)
        .put("clarity", clarity).put("normalize", normalize).put("format", format)

    companion object {
        fun fromJson(o: JSONObject?): AudioOptions {
            if (o == null) return AudioOptions()
            return AudioOptions(
                mode = runCatching { AudioMode.valueOf(o.optString("mode")) }.getOrDefault(AudioMode.Voice),
                strength = o.optInt("strength", 70),
                hum = runCatching { HumFilter.valueOf(o.optString("hum")) }.getOrDefault(HumFilter.Auto),
                highpass = o.optBoolean("highpass", true),
                clarity = o.optInt("clarity", 25),
                normalize = o.optBoolean("normalize", true),
                format = o.optString("format", "M4a"),
            )
        }
    }
}

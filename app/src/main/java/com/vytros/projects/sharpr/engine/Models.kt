package com.vytros.projects.sharpr.engine

import org.json.JSONObject

enum class ModelId(val label: String, val blurb: String, val scales: List<Int>) {
    Fast("Fast", "Compact network. Quickest, great all-rounder.", listOf(1, 2, 3, 4)),
    Photo("Photo Ultra", "Heavyweight Real-ESRGAN for real photos. Best detail, slower.", listOf(1, 2, 4)),
    Art("Illustration", "Tuned for drawings, anime and flat colour.", listOf(1, 2, 4));

    /**
     * Scale the network itself produces; smaller requested scales are box-averaged down from it.
     * 1× ("Enhance") runs the 4× network and averages 4×4 blocks: supersampled restoration at the original size.
     */
    fun nativeScale(scale: Int): Int = if (this == Fast && scale > 1) scale else 4

    fun assetBase(scale: Int): String = when (this) {
        Fast -> "models/realesr-animevideov3-x${nativeScale(scale)}"
        Photo -> "models/realesrgan-x4plus"
        Art -> "models/realesrgan-x4plus-anime"
    }

    /** The nearest scale this model supports. */
    fun fitScale(scale: Int): Int = if (scale in scales) scale else scales.minBy { kotlin.math.abs(it - scale) }
}

data class UpscaleOptions(
    val model: ModelId = ModelId.Fast,
    val scale: Int = 4,
    /** Pre-denoise strength, 0..100. */
    val denoise: Int = 0,
    /** Blend with a plain Lanczos upscale to tame invented texture, 0..100. */
    val natural: Int = 0,
    val sharpen: Int = 0,
    val grain: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("model", model.name).put("scale", scale).put("denoise", denoise)
        .put("natural", natural).put("sharpen", sharpen).put("grain", grain)

    companion object {
        fun fromJson(o: JSONObject): UpscaleOptions = UpscaleOptions(
            model = runCatching { ModelId.valueOf(o.optString("model")) }.getOrDefault(ModelId.Fast),
            scale = o.optInt("scale", 4),
            denoise = o.optInt("denoise", 0),
            natural = o.optInt("natural", 0),
            sharpen = o.optInt("sharpen", 0),
            grain = o.optInt("grain", 0),
        )
    }
}

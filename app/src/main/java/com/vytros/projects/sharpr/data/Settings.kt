package com.vytros.projects.sharpr.data

import android.content.Context
import com.vytros.projects.sharpr.engine.ModelId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** Two-stop gradient accents. Colours are ARGB. */
enum class Accent(val label: String, val start: Long, val end: Long, val startDark: Long, val endDark: Long) {
    Violet("Violet", 0xFF6D4AFF, 0xFFD946EF, 0xFF9B87FF, 0xFFE879F9),
    Aurora("Aurora", 0xFF4F46E5, 0xFF06B6D4, 0xFF818CF8, 0xFF22D3EE),
    Ember("Ember", 0xFFF2541B, 0xFFE11D74, 0xFFFF8A5B, 0xFFFF5C9D),
    Lagoon("Lagoon", 0xFF0D9488, 0xFF2563EB, 0xFF2DD4BF, 0xFF60A5FA),
    Lime("Lime", 0xFF4D7C0F, 0xFF059669, 0xFFA3E635, 0xFF34D399),
}

/** Body typeface; headings always use Bricolage Grotesque. */
enum class AppFont(val label: String) {
    Jakarta("Plus Jakarta Sans"),
    Inter("Inter"),
    Outfit("Outfit"),
    Grotesk("Space Grotesk"),
}

enum class EngineMode(val id: Int, val label: String) {
    Auto(0, "Auto"),
    Gpu(1, "GPU"),
    Cpu(2, "CPU"),
}

/** Maps to the native precision levels. */
enum class Precision(val id: Int, val label: String, val blurb: String) {
    Exact(0, "Exact", "Full 32-bit floats. Slowest, bit-for-bit stable on every device."),
    Balanced(1, "Balanced", "16-bit storage, 32-bit maths. Faster and lighter with no visible difference."),
    Turbo(2, "Turbo", "16-bit storage and maths. Fastest on modern chips; may shift colours on a few GPUs."),
}

enum class OutFormat(val label: String, val ext: String) {
    Png("PNG", "png"),
    Jpeg("JPEG", "jpg"),
    Webp("WebP", "webp"),
}

enum class VideoCodec(val label: String, val mime: String) {
    H264("H.264", "video/avc"),
    Hevc("HEVC", "video/hevc"),
}

enum class VideoQuality(val label: String, val bitsPerPixel: Float) {
    Compact("Compact", 0.07f),
    Standard("Standard", 0.11f),
    High("High", 0.17f),
}

enum class AudioFormat(val label: String, val ext: String) {
    M4a("AAC (M4A)", "m4a"),
    Wav("WAV", "wav"),
}

data class AppSettings(
    val onboarded: Boolean = false,

    val themeMode: ThemeMode = ThemeMode.System,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = false,
    val accent: Accent = Accent.Violet,
    val font: AppFont = AppFont.Jakarta,

    val engine: EngineMode = EngineMode.Auto,
    val cpuFallback: Boolean = true,
    /** 0 = auto (performance cores). */
    val threads: Int = 0,
    /** 0 = auto. */
    val tile: Int = 0,
    val precision: Precision = Precision.Balanced,
    /** 8x self-ensemble: cleaner edges, 8x slower. */
    val tta: Boolean = false,

    val defaultModel: ModelId = ModelId.Fast,
    val defaultScale: Int = 4,
    val maxInputSide: Int = 2048,
    /** When true the guard cannot be overridden per image. */
    val strictGuard: Boolean = false,
    val maxOutputMp: Int = 24,
    val format: OutFormat = OutFormat.Png,
    val quality: Int = 95,
    val autoSave: Boolean = false,

    val videoModel: ModelId = ModelId.Fast,
    val videoScale: Int = 2,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val videoQuality: VideoQuality = VideoQuality.Standard,
    val skipDuplicates: Boolean = true,

    val audioFormat: AudioFormat = AudioFormat.M4a,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("sharpr_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        if (next == _state.value) return
        _state.value = next
        save(next)
    }

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, default.name)!!) }.getOrDefault(default)

    private fun load(): AppSettings {
        val d = AppSettings()
        // v1 stored a boolean "fp16"; carry it over once.
        val precision = if (prefs.contains("precision")) enumOf("precision", d.precision)
        else if (prefs.contains("fp16") && !prefs.getBoolean("fp16", true)) Precision.Exact else d.precision
        return AppSettings(
            // Anyone upgrading from v1 already knows the app.
            onboarded = prefs.getBoolean("onboarded", prefs.contains("engine")),
            themeMode = enumOf("themeMode", d.themeMode),
            amoled = prefs.getBoolean("amoled", d.amoled),
            dynamicColor = prefs.getBoolean("dynamicColor", d.dynamicColor),
            accent = enumOf("accent", d.accent),
            font = enumOf("font", d.font),
            engine = enumOf("engine", d.engine),
            cpuFallback = prefs.getBoolean("cpuFallback", d.cpuFallback),
            threads = prefs.getInt("threads", d.threads),
            tile = prefs.getInt("tile", d.tile),
            precision = precision,
            tta = prefs.getBoolean("tta", d.tta),
            defaultModel = enumOf("defaultModel", d.defaultModel),
            defaultScale = prefs.getInt("defaultScale", d.defaultScale),
            maxInputSide = prefs.getInt("maxInputSide", d.maxInputSide),
            strictGuard = prefs.getBoolean("strictGuard", d.strictGuard),
            maxOutputMp = prefs.getInt("maxOutputMp", d.maxOutputMp),
            format = enumOf("format", d.format),
            quality = prefs.getInt("quality", d.quality),
            autoSave = prefs.getBoolean("autoSave", d.autoSave),
            videoModel = enumOf("videoModel", d.videoModel),
            videoScale = prefs.getInt("videoScale", d.videoScale),
            videoCodec = enumOf("videoCodec", d.videoCodec),
            videoQuality = enumOf("videoQuality", d.videoQuality),
            skipDuplicates = prefs.getBoolean("skipDuplicates", d.skipDuplicates),
            audioFormat = enumOf("audioFormat", d.audioFormat),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("onboarded", s.onboarded)
            .putString("themeMode", s.themeMode.name)
            .putBoolean("amoled", s.amoled)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putString("accent", s.accent.name)
            .putString("font", s.font.name)
            .putString("engine", s.engine.name)
            .putBoolean("cpuFallback", s.cpuFallback)
            .putInt("threads", s.threads)
            .putInt("tile", s.tile)
            .putString("precision", s.precision.name)
            .putBoolean("tta", s.tta)
            .putString("defaultModel", s.defaultModel.name)
            .putInt("defaultScale", s.defaultScale)
            .putInt("maxInputSide", s.maxInputSide)
            .putBoolean("strictGuard", s.strictGuard)
            .putInt("maxOutputMp", s.maxOutputMp)
            .putString("format", s.format.name)
            .putInt("quality", s.quality)
            .putBoolean("autoSave", s.autoSave)
            .putString("videoModel", s.videoModel.name)
            .putInt("videoScale", s.videoScale)
            .putString("videoCodec", s.videoCodec.name)
            .putString("videoQuality", s.videoQuality.name)
            .putBoolean("skipDuplicates", s.skipDuplicates)
            .putString("audioFormat", s.audioFormat.name)
            .apply()
    }
}

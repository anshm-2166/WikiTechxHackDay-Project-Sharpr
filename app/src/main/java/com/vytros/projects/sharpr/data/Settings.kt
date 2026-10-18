package com.vytros.projects.sharpr.data

import android.content.Context
import com.vytros.projects.sharpr.engine.ModelId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** Purple-family accent palettes. Colours are ARGB longs. */
enum class Accent(
    val label: String,
    val light: Long,
    val lightDeep: Long,
    val dark: Long,
    val gradEnd: Long,
) {
    Violet("Violet", 0xFF7C3AED, 0xFF5B21B6, 0xFFA78BFA, 0xFFC026D3),
    Orchid("Orchid", 0xFFC026D3, 0xFF86198F, 0xFFE879F9, 0xFF7C3AED),
    Indigo("Indigo", 0xFF5B4BEA, 0xFF3F32B8, 0xFF8B93FF, 0xFF9333EA),
    Lilac("Lilac", 0xFF9D7BEA, 0xFF7A55D0, 0xFFC4B5FD, 0xFFEC4899),
}

enum class AppFont(val label: String) {
    Outfit("Outfit"),
    Jakarta("Plus Jakarta Sans"),
    Grotesk("Space Grotesk"),
}

enum class EngineMode(val id: Int, val label: String) {
    Auto(0, "Auto"),
    Gpu(1, "GPU"),
    Cpu(2, "CPU"),
}

enum class OutFormat(val label: String, val ext: String) {
    Png("PNG", "png"),
    Jpeg("JPEG", "jpg"),
    Webp("WebP", "webp"),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val amoled: Boolean = false,
    val accent: Accent = Accent.Violet,
    val font: AppFont = AppFont.Outfit,

    val engine: EngineMode = EngineMode.Auto,
    val cpuFallback: Boolean = true,
    /** 0 = auto (performance cores). */
    val threads: Int = 0,
    /** 0 = auto. */
    val tile: Int = 0,
    val fp16: Boolean = true,

    val defaultModel: ModelId = ModelId.Fast,
    val defaultScale: Int = 4,

    val maxInputSide: Int = 2048,
    /** When true the guard cannot be overridden per image. */
    val strictGuard: Boolean = false,
    val maxOutputMp: Int = 24,

    val format: OutFormat = OutFormat.Png,
    val quality: Int = 95,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("sharpr_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        _state.value = next
        save(next)
    }

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, default.name)!!) }.getOrDefault(default)

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = enumOf("themeMode", d.themeMode),
            amoled = prefs.getBoolean("amoled", d.amoled),
            accent = enumOf("accent", d.accent),
            font = enumOf("font", d.font),
            engine = enumOf("engine", d.engine),
            cpuFallback = prefs.getBoolean("cpuFallback", d.cpuFallback),
            threads = prefs.getInt("threads", d.threads),
            tile = prefs.getInt("tile", d.tile),
            fp16 = prefs.getBoolean("fp16", d.fp16),
            defaultModel = enumOf("defaultModel", d.defaultModel),
            defaultScale = prefs.getInt("defaultScale", d.defaultScale),
            maxInputSide = prefs.getInt("maxInputSide", d.maxInputSide),
            strictGuard = prefs.getBoolean("strictGuard", d.strictGuard),
            maxOutputMp = prefs.getInt("maxOutputMp", d.maxOutputMp),
            format = enumOf("format", d.format),
            quality = prefs.getInt("quality", d.quality),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString("themeMode", s.themeMode.name)
            .putBoolean("amoled", s.amoled)
            .putString("accent", s.accent.name)
            .putString("font", s.font.name)
            .putString("engine", s.engine.name)
            .putBoolean("cpuFallback", s.cpuFallback)
            .putInt("threads", s.threads)
            .putInt("tile", s.tile)
            .putBoolean("fp16", s.fp16)
            .putString("defaultModel", s.defaultModel.name)
            .putInt("defaultScale", s.defaultScale)
            .putInt("maxInputSide", s.maxInputSide)
            .putBoolean("strictGuard", s.strictGuard)
            .putInt("maxOutputMp", s.maxOutputMp)
            .putString("format", s.format.name)
            .putInt("quality", s.quality)
            .apply()
    }
}

package com.vytros.projects.sharpr.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.vytros.projects.sharpr.BuildConfig
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.Accent
import com.vytros.projects.sharpr.data.AppFont
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.EngineMode
import com.vytros.projects.sharpr.data.OutFormat
import com.vytros.projects.sharpr.data.ThemeMode
import com.vytros.projects.sharpr.databinding.FragmentSettingsBinding
import com.vytros.projects.sharpr.engine.ModelId
import com.vytros.projects.sharpr.engine.NativeBridge
import com.vytros.projects.sharpr.ui.widgets.dpi
import com.vytros.projects.sharpr.ui.widgets.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsFragment : Fragment(R.layout.fragment_settings) {
    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private val threadChoices = listOf(0, 1, 2, 4, 6, 8)
    private val tileChoices = listOf(0, 64, 128, 200, 256, 400)
    private val inputChoices = listOf(1024, 1536, 2048, 3072, 4096)
    private val outputChoices = listOf(12, 24, 36, 48, 64)
    private val scaleChoices = listOf(1, 2, 3, 4)

    private fun update(block: (AppSettings) -> AppSettings) = graph.settings.update(block)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentSettingsBinding.bind(view)
        val s = graph.settings.state.value

        // Appearance
        b.themeSeg.setItems(ThemeMode.entries.map { it.label }, s.themeMode.ordinal)
        b.themeSeg.onSelect = { i -> update { it.copy(themeMode = ThemeMode.entries[i]) } }
        b.amoled.checked = s.amoled
        b.amoled.onChange = { v -> update { it.copy(amoled = v) } }
        buildAccents(s.accent)
        buildFonts(s.font)

        // Engine
        b.engineSeg.setItems(EngineMode.entries.map { it.label }, s.engine.ordinal)
        b.engineSeg.onSelect = { i ->
            update { it.copy(engine = EngineMode.entries[i]) }
            engineHint(EngineMode.entries[i])
        }
        engineHint(s.engine)
        b.fallback.checked = s.cpuFallback
        b.fallback.onChange = { v -> update { it.copy(cpuFallback = v) } }
        b.threadsSeg.setItems(threadChoices.map { if (it == 0) "Auto" else "$it" }, threadChoices.indexOf(s.threads).coerceAtLeast(0))
        b.threadsSeg.onSelect = { i -> update { it.copy(threads = threadChoices[i]) } }
        b.tileSeg.setItems(tileChoices.map { if (it == 0) "Auto" else "$it" }, tileChoices.indexOf(s.tile).coerceAtLeast(0))
        b.tileSeg.onSelect = { i -> update { it.copy(tile = tileChoices[i]) } }
        b.fp16.checked = s.fp16
        b.fp16.onChange = { v -> update { it.copy(fp16 = v) } }
        detectGpu()

        // Defaults
        b.defModelSeg.setItems(ModelId.entries.map { it.label }, s.defaultModel.ordinal)
        b.defScaleSeg.setItems(scaleChoices.map { if (it == 1) "1×" else "$it×" }, scaleChoices.indexOf(s.defaultScale).coerceAtLeast(0))
        fun syncScaleMask(m: ModelId) = b.defScaleSeg.setEnabledItems(m.scales.map { scaleChoices.indexOf(it) }.toSet())
        syncScaleMask(s.defaultModel)
        b.defModelSeg.onSelect = { i ->
            val m = ModelId.entries[i]
            syncScaleMask(m)
            val fit = m.fitScale(graph.settings.state.value.defaultScale)
            b.defScaleSeg.selectedIndex = scaleChoices.indexOf(fit)
            update { it.copy(defaultModel = m, defaultScale = fit) }
        }
        b.defScaleSeg.onSelect = { i -> update { it.copy(defaultScale = scaleChoices[i]) } }

        // Guards
        b.maxInputSeg.setItems(inputChoices.map { "$it" }, inputChoices.indexOf(s.maxInputSide).coerceAtLeast(0))
        b.maxInputSeg.onSelect = { i -> update { it.copy(maxInputSide = inputChoices[i]) } }
        b.strict.checked = s.strictGuard
        b.strict.onChange = { v -> update { it.copy(strictGuard = v) } }
        b.maxOutSeg.setItems(outputChoices.map { "$it" }, outputChoices.indexOf(s.maxOutputMp).coerceAtLeast(0))
        b.maxOutSeg.onSelect = { i -> update { it.copy(maxOutputMp = outputChoices[i]) } }

        // Output
        b.formatSeg.setItems(OutFormat.entries.map { it.label }, s.format.ordinal)
        b.formatSeg.onSelect = { i -> update { it.copy(format = OutFormat.entries[i]) } }
        b.quality.value = s.quality
        b.quality.onChange = { v -> update { it.copy(quality = v.coerceAtLeast(50)) } }

        // Storage
        b.clearBtn.setOnClickListener {
            Dialogs.confirm(requireContext(), "Clear library?", "All upscaled images and their originals will be deleted from this device.", "Clear") {
                graph.library.clear()
                refreshStorage()
            }
        }
        b.about.text = "Sharpr ${BuildConfig.VERSION_NAME} · ncnn + OpenCV · Real-ESRGAN models (BSD-3)"
        refreshStorage()
    }

    override fun onResume() {
        super.onResume()
        if (_b != null) refreshStorage()
    }

    private fun engineHint(mode: EngineMode) {
        b.engineHint.text = when (mode) {
            EngineMode.Auto -> "Uses the Vulkan GPU when there is one, otherwise the CPU. Best of both."
            EngineMode.Gpu -> "GPU only. Fastest, but see the fallback switch below if it misbehaves."
            EngineMode.Cpu -> "Always CPU. Slower, but works on every device."
        }
    }

    private fun detectGpu() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (info, cores) = withContext(Dispatchers.Default) { NativeBridge.gpuInfo() to NativeBridge.cpuCores() }
            _b?.let {
                it.gpuInfo.text = if (info.isEmpty()) {
                    "No Vulkan GPU available. Sharpr will use the CPU ($cores performance cores)."
                } else {
                    val (name, heap) = info.split("|").let { p -> p[0] to p.getOrElse(1) { "0" } }
                    "$name · ${String.format(Locale.US, "%.1f", heap.toInt() / 1024.0)} GB GPU memory budget\nCPU: $cores performance cores"
                }
            }
        }
    }

    private fun refreshStorage() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (count, bytes) = withContext(Dispatchers.IO) {
                val files = listOf(graph.library.originals, graph.library.results, graph.library.thumbs).flatMap { it.listFiles()?.toList() ?: emptyList() }
                graph.library.records.value.size to files.sumOf { it.length() }
            }
            _b?.storageInfo?.text = "$count images · ${String.format(Locale.US, "%.1f", bytes / 1_048_576.0)} MB on this device"
        }
    }

    private fun buildAccents(selected: Accent) {
        val ctx = requireContext()
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        b.accentRow.removeAllViews()
        Accent.entries.forEach { a ->
            val on = a == selected
            val swatch = FrameLayout(ctx).apply {
                setBackgroundResource(R.drawable.bg_swatch)
                backgroundTintList = ColorStateList.valueOf((if (night) a.dark else a.light).toInt())
                layoutParams = LinearLayout.LayoutParams(dpi(44), dpi(44)).apply { marginEnd = dpi(14) }
                contentDescription = a.label
                setOnClickListener { update { it.copy(accent = a) } }
                if (on) addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(0xFFFFFFFF.toInt())
                    setPadding(dpi(10), dpi(10), dpi(10), dpi(10))
                })
            }
            b.accentRow.addView(swatch)
        }
    }

    private fun buildFonts(selected: AppFont) {
        val ctx = requireContext()
        b.fontRow.removeAllViews()
        val families = mapOf(AppFont.Outfit to R.font.outfit_family, AppFont.Jakarta to R.font.jakarta_family, AppFont.Grotesk to R.font.grotesk_family)
        AppFont.entries.forEach { f ->
            val on = f == selected
            val tv = TextView(ctx).apply {
                text = "Aa\n${f.label}"
                gravity = Gravity.CENTER
                typeface = ResourcesCompat.getFont(ctx, families.getValue(f))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 13f
                setLineSpacing(0f, 1.05f)
                setPadding(dpi(6), dpi(12), dpi(6), dpi(12))
                setBackgroundResource(if (on) R.drawable.bg_nav_selected else R.drawable.bg_card_alt)
                setTextColor(if (on) 0xFFFFFFFF.toInt() else ctx.themeColor(R.attr.sText))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (f == AppFont.entries.last()) 0 else dpi(10)
                }
                setOnClickListener { update { it.copy(font = f) } }
            }
            b.fontRow.addView(tv)
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}

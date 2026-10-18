package com.vytros.projects.sharpr.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vytros.projects.sharpr.Draft
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.EngineMode
import com.vytros.projects.sharpr.databinding.FragmentCreateBinding
import com.vytros.projects.sharpr.engine.Analyzer
import com.vytros.projects.sharpr.engine.GuardVerdict
import com.vytros.projects.sharpr.engine.Guards
import com.vytros.projects.sharpr.engine.UpscaleOptions
import com.vytros.projects.sharpr.engine.UpscaleRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CreateFragment : Fragment(R.layout.fragment_create) {
    private var _b: FragmentCreateBinding? = null
    private val b get() = _b!!
    private lateinit var panel: OptionsPanel
    private var shownFile: String? = null
    private var verdict: GuardVerdict? = null

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentCreateBinding.bind(view)
        panel = OptionsPanel(b.options) { o -> graph.draft.value = graph.draft.value?.copy(options = o) }

        val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        b.pickCard.setOnClickListener { pick() }
        b.changeBtn.setOnClickListener { pick() }
        b.engineLine.setOnClickListener { navigator.goTab(2) }
        b.overrideBtn.setOnClickListener { graph.draft.value = graph.draft.value?.copy(override = true) }
        b.upscaleBtn.setOnClickListener { start() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.draft.combine(graph.settings.state) { d, s -> d to s }.collect { (d, s) -> render(d, s) }
            }
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun onPicked(uri: Uri) {
        b.pickCard.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) {
                    // A previous draft that never produced a result is just clutter.
                    graph.draft.value?.image?.file?.let { f ->
                        if (graph.library.records.value.none { it.originalFile == f.absolutePath }) f.delete()
                    }
                    val img = graph.library.importUri(uri)
                    val analysis = Analyzer.analyze(img.file) ?: error("That doesn't look like an image")
                    val s = graph.settings.state.value
                    Draft(img, analysis, UpscaleOptions(model = s.defaultModel, scale = s.defaultModel.fitScale(s.defaultScale)))
                }
                shownFile = null
                graph.draft.value = draft
            } catch (e: Exception) {
                Dialogs.message(requireContext(), "Couldn't open image", e.message ?: "Unknown error")
            } finally {
                _b?.pickCard?.isEnabled = true
            }
        }
    }

    private fun render(d: Draft?, s: AppSettings) {
        val has = d != null
        b.pickCard.visibility = if (has) View.GONE else View.VISIBLE
        b.draftGroup.visibility = if (has) View.VISIBLE else View.GONE
        if (d == null) { shownFile = null; return }

        val path = d.image.file.absolutePath
        if (shownFile != path) {
            shownFile = path
            ImageLoader.load(b.preview, d.image.file, 1280)
            b.infoChips.removeAllViews()
            val size = d.analysis.size
            listOf(
                "${Fmt.dims(size.w, size.h)} px",
                Fmt.mp(size.w, size.h),
                d.analysis.sharpnessLabel,
                d.analysis.noiseLabel,
            ).forEach { b.infoChips.addView(chip(it)) }
            panel.bind(d.options, d.analysis)
        } else {
            panel.bind(d.options, d.analysis)
        }

        val v = Guards.evaluate(d.analysis.size, d.analysis.detail, d.options, s, d.override)
        verdict = v
        b.engineLine.text = engineText(s)

        when (v) {
            is GuardVerdict.Blocked -> {
                b.guardBanner.visibility = View.VISIBLE
                b.guardBanner.setBackgroundResource(R.drawable.bg_banner_warn)
                b.guardBanner.text = "${v.title}\n${v.reason}"
                b.overrideBtn.visibility = if (v.canOverride) View.VISIBLE else View.GONE
                b.upscaleBtn.isEnabled = false
                b.upscaleBtn.alpha = 0.4f
                b.outPreview.text = "Blocked by guard"
            }
            is GuardVerdict.Ok -> {
                b.guardBanner.visibility = if (v.note != null) View.VISIBLE else View.GONE
                b.guardBanner.setBackgroundResource(R.drawable.bg_banner_info)
                b.guardBanner.text = v.note
                b.overrideBtn.visibility = View.GONE
                b.upscaleBtn.isEnabled = true
                b.upscaleBtn.alpha = 1f
                val size = d.analysis.size
                b.outPreview.text = (if (v.scale == 1) "Enhanced at original size: " else "Result: ") + "${Fmt.dims(size.w * v.scale, size.h * v.scale)} · ${Fmt.mp(size.w * v.scale, size.h * v.scale)}"
            }
        }
    }

    private fun engineText(s: AppSettings) = when (s.engine) {
        EngineMode.Auto -> "Engine: Auto (Vulkan GPU, falls back to CPU)"
        EngineMode.Gpu -> "Engine: GPU" + if (s.cpuFallback) ", CPU fallback on" else " only"
        EngineMode.Cpu -> "Engine: CPU"
    } + "  ·  change"

    private fun chip(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextAppearance(R.style.Sharpr_Chip)
        setBackgroundResource(R.drawable.bg_chip)
        gravity = android.view.Gravity.CENTER
        minHeight = dpPx(32)
        setPadding(dpPx(12), dpPx(6), dpPx(12), dpPx(6))
    }

    private fun dpPx(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun start() {
        val d = graph.draft.value ?: return
        val v = verdict as? GuardVerdict.Ok ?: return
        graph.manager.start(
            UpscaleRequest(
                sourceFile = File(d.image.file.absolutePath),
                name = d.image.name,
                options = d.options.copy(scale = v.scale),
                allowOversize = d.override,
            ),
        )
    }
}

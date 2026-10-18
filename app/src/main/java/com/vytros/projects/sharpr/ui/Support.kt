package com.vytros.projects.sharpr.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.vytros.projects.sharpr.Graph
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.SharprApp
import com.vytros.projects.sharpr.databinding.ViewOptionsPanelBinding
import com.vytros.projects.sharpr.engine.ImageAnalysis
import com.vytros.projects.sharpr.engine.ModelId
import com.vytros.projects.sharpr.engine.UpscaleOptions

interface Navigator {
    fun goTab(index: Int)
    fun openDetail(id: String)
    fun openEdit(id: String)
}

val Fragment.graph: Graph get() = (requireActivity().application as SharprApp).graph
val Fragment.navigator: Navigator get() = requireActivity() as Navigator

object Dialogs {
    fun message(context: Context, title: String, message: String) {
        AlertDialog.Builder(context, R.style.Sharpr_Dialog)
            .setTitle(title).setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    fun confirm(context: Context, title: String, message: String, positive: String, onYes: () -> Unit) {
        AlertDialog.Builder(context, R.style.Sharpr_Dialog)
            .setTitle(title).setMessage(message)
            .setPositiveButton(positive) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/** Binds the shared model/scale/enhance controls (used on both Create and Edit). */
class OptionsPanel(private val b: ViewOptionsPanelBinding, private val onChange: (UpscaleOptions) -> Unit) {
    private var options = UpscaleOptions()
    private var analysis: ImageAnalysis? = null
    private var binding = false

    private val scales = listOf(1, 2, 3, 4)

    init {
        b.modelSeg.setItems(ModelId.entries.map { it.label })
        b.scaleSeg.setItems(scales.map { if (it == 1) "1×" else "$it×" })
        b.modelSeg.onSelect = { i ->
            val model = ModelId.entries[i]
            emit(options.copy(model = model, scale = model.fitScale(options.scale)))
        }
        b.scaleSeg.onSelect = { i -> emit(options.copy(scale = scales[i])) }
        b.denoise.onChange = { emit(options.copy(denoise = it)) }
        b.natural.onChange = { emit(options.copy(natural = it)) }
        b.sharpen.onChange = { emit(options.copy(sharpen = it)) }
        b.grain.onChange = { emit(options.copy(grain = it)) }
        b.autoBtn.setOnClickListener {
            val a = analysis ?: return@setOnClickListener
            emit(
                options.copy(
                    denoise = a.suggestedDenoise,
                    natural = if (a.noiseSigma > 4.5) 20 else 0,
                    sharpen = if (a.detail < ImageAnalysis.SOFT_BELOW) 30 else 0,
                    grain = if (a.suggestedDenoise > 30) 8 else 0,
                ),
            )
            bind(options, analysis)
        }
    }

    private fun emit(o: UpscaleOptions) {
        options = o
        if (binding) return
        render()
        onChange(o)
    }

    fun bind(o: UpscaleOptions, a: ImageAnalysis?) {
        binding = true
        options = o
        analysis = a
        render()
        binding = false
    }

    private fun render() {
        val wasBinding = binding
        binding = true
        b.modelSeg.selectedIndex = options.model.ordinal
        b.modelBlurb.text = options.model.blurb
        b.scaleSeg.setEnabledItems(options.model.scales.map { scales.indexOf(it) }.toSet())
        b.scaleSeg.selectedIndex = scales.indexOf(options.scale).coerceAtLeast(0)
        b.denoise.value = options.denoise
        b.natural.value = options.natural
        b.sharpen.value = options.sharpen
        b.grain.value = options.grain
        b.autoBtn.alpha = if (analysis == null) 0.4f else 1f
        binding = wasBinding
    }
}

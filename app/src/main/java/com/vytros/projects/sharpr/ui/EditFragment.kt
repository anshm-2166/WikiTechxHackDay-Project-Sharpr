package com.vytros.projects.sharpr.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.UpscaleRecord
import com.vytros.projects.sharpr.databinding.FragmentEditBinding
import com.vytros.projects.sharpr.engine.Analyzer
import com.vytros.projects.sharpr.engine.GuardVerdict
import com.vytros.projects.sharpr.engine.Guards
import com.vytros.projects.sharpr.engine.ImageAnalysis
import com.vytros.projects.sharpr.engine.UpscaleOptions
import com.vytros.projects.sharpr.engine.UpscaleRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Regenerates an upscale from the *original* image with different settings. */
class EditFragment : Fragment(R.layout.fragment_edit) {
    private var _b: FragmentEditBinding? = null
    private val b get() = _b!!
    private lateinit var record: UpscaleRecord
    private lateinit var panel: OptionsPanel
    private var options = UpscaleOptions()
    private var analysis: ImageAnalysis? = null
    private var verdict: GuardVerdict? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentEditBinding.bind(view)
        val r = graph.library.find(requireArguments().getString("id")!!)
        if (r == null) {
            parentFragmentManager.popBackStack()
            return
        }
        record = r
        options = r.options

        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        b.origTitle.text = r.name
        b.origSub.text = "${Fmt.dims(r.srcW, r.srcH)} · ${Fmt.mp(r.srcW, r.srcH)}"
        val original = File(r.originalFile)
        if (original.exists()) ImageLoader.load(b.original, original, 256)

        b.keepPrevious.checked = true
        panel = OptionsPanel(b.options) { o -> options = o; refresh() }
        panel.bind(options, null)
        b.regenBtn.setOnClickListener { regenerate() }

        viewLifecycleOwner.lifecycleScope.launch {
            analysis = withContext(Dispatchers.IO) { if (original.exists()) Analyzer.analyze(original) else null }
            _b?.let { panel.bind(options, analysis); refresh() }
        }
        refresh()
    }

    private fun refresh() {
        // The image passed the guard when it was first upscaled, so re-runs always count as approved.
        val v = Guards.evaluate(com.vytros.projects.sharpr.engine.ImageIO.Size(record.srcW, record.srcH), null, options, graph.settings.state.value, override = true)
        verdict = v
        when (v) {
            is GuardVerdict.Ok -> {
                b.outPreview.text = (if (v.scale == 1) "Enhanced at original size: " else "Result: ") + "${Fmt.dims(record.srcW * v.scale, record.srcH * v.scale)} · ${Fmt.mp(record.srcW * v.scale, record.srcH * v.scale)}"
                b.guardNote.visibility = if (v.note != null) View.VISIBLE else View.GONE
                b.guardNote.text = v.note
                b.regenBtn.isEnabled = true
                b.regenBtn.alpha = 1f
            }
            is GuardVerdict.Blocked -> {
                b.outPreview.text = v.title
                b.guardNote.visibility = View.VISIBLE
                b.guardNote.text = v.reason
                b.regenBtn.isEnabled = false
                b.regenBtn.alpha = 0.4f
            }
        }
    }

    private fun regenerate() {
        val v = verdict as? GuardVerdict.Ok ?: return
        val original = File(record.originalFile)
        if (!original.exists()) {
            Dialogs.message(requireContext(), "Original is gone", "The source image for this result is no longer on the device, so it can't be regenerated.")
            return
        }
        graph.manager.start(
            UpscaleRequest(
                sourceFile = original,
                name = record.name,
                options = options.copy(scale = v.scale),
                replaceId = if (b.keepPrevious.checked) null else record.id,
                allowOversize = true,
            ),
        )
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(id: String) = EditFragment().apply { arguments = bundleOf("id" to id) }
    }
}

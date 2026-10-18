package com.vytros.projects.sharpr.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.UpscaleRecord
import com.vytros.projects.sharpr.databinding.FragmentDetailBinding
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class DetailFragment : Fragment(R.layout.fragment_detail) {
    private var _b: FragmentDetailBinding? = null
    private val b get() = _b!!
    private val recordId get() = requireArguments().getString("id")!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentDetailBinding.bind(view)
        val record = graph.library.find(recordId)
        if (record == null) {
            parentFragmentManager.popBackStack()
            return
        }
        bindRecord(record)

        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        b.infoBtn.setOnClickListener {
            b.infoCard.visibility = if (b.infoCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        b.compare.onZoomChanged = { z ->
            b.zoomChip.visibility = if (z > 1.02f) View.VISIBLE else View.GONE
            b.zoomChip.text = String.format(Locale.US, "%.1f×", z)
        }
        b.actShare.setOnClickListener { Share.share(requireContext(), File(record.resultFile)) }
        b.actEdit.setOnClickListener { navigator.openEdit(record.id) }
        b.actSave.setOnClickListener {
            val ok = Share.saveToGallery(requireContext(), File(record.resultFile), "${record.name}_sharpr_${record.options.scale}x")
            if (ok) Toast.makeText(requireContext(), "Saved to Pictures/Sharpr", Toast.LENGTH_SHORT).show()
        }
        b.actDelete.setOnClickListener {
            Dialogs.confirm(requireContext(), "Delete this image?", "This removes the upscaled result from your library.", "Delete") {
                ImageLoader.evict(File(record.thumbFile))
                graph.library.remove(record.id)
                parentFragmentManager.popBackStack()
            }
        }

        // Close if the record disappears underneath us (e.g. replaced by Edit).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.library.records.collect { list ->
                    if (list.none { it.id == recordId } && isAdded) parentFragmentManager.popBackStack()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val after = async { ImageLoader.decode(File(record.resultFile), 3072) }
            val before = async { ImageLoader.decode(File(record.originalFile), 2048) }
            val a = after.await()
            val bf = before.await()
            _b?.let {
                it.loading.visibility = View.GONE
                it.compare.setImages(bf ?: a, a)
            }
        }
    }

    private fun bindRecord(r: UpscaleRecord) {
        b.title.text = r.name
        b.subtitle.text = "${r.options.scale}× · ${Fmt.dims(r.srcW, r.srcH)} → ${Fmt.dims(r.outW, r.outH)}"
        val o = r.options
        val tweaks = buildList {
            if (o.denoise > 0) add("denoise ${o.denoise}")
            if (o.natural > 0) add("natural ${o.natural}")
            if (o.sharpen > 0) add("sharpen ${o.sharpen}")
            if (o.grain > 0) add("grain ${o.grain}")
        }.ifEmpty { listOf("none") }.joinToString(", ")
        b.infoCard.removeAllViews()
        listOf(
            "Original" to "${Fmt.dims(r.srcW, r.srcH)}  ·  ${Fmt.mp(r.srcW, r.srcH)}",
            "Result" to "${Fmt.dims(r.outW, r.outH)}  ·  ${Fmt.mp(r.outW, r.outH)}",
            "Model" to "${o.model.label} (${o.scale}×)",
            "Engine" to r.backend,
            "Enhance" to tweaks,
            "Time" to "${Fmt.duration(r.durationMs)}  ·  ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.createdAt))}",
        ).forEach { (k, v) -> b.infoCard.addView(infoRow(k, v)) }
    }


    private fun infoRow(label: String, value: String): View {
        val ctx = requireContext()
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(android.widget.TextView(ctx).apply {
            text = label
            setTextAppearance(R.style.TextAppearance_S_Muted)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(92), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        row.addView(android.widget.TextView(ctx).apply {
            text = value
            setTextAppearance(R.style.TextAppearance_S_Label)
            textSize = 14f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(id: String) = DetailFragment().apply { arguments = bundleOf("id" to id) }
    }
}

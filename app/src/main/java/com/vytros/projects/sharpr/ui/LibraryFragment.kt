package com.vytros.projects.sharpr.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.UpscaleRecord
import com.vytros.projects.sharpr.databinding.FragmentLibraryBinding
import com.vytros.projects.sharpr.databinding.ItemRecordBinding
import kotlinx.coroutines.launch
import java.io.File

class LibraryFragment : Fragment(R.layout.fragment_library) {
    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentLibraryBinding.bind(view)
        val adapter = RecordAdapter(
            onClick = { navigator.openDetail(it.id) },
            onLongClick = { r ->
                Dialogs.confirm(requireContext(), "Delete this image?", "“${r.name}” (${r.options.scale}×) will be removed from your library.", "Delete") {
                    graph.library.remove(r.id)
                }
            },
        )
        b.list.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.library.records.collect { list ->
                    adapter.submitList(list)
                    b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    b.count.text = when (list.size) {
                        0 -> "Your upscaled images"
                        1 -> "1 image · long-press to delete"
                        else -> "${list.size} images · long-press to delete"
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}

private class RecordAdapter(
    val onClick: (UpscaleRecord) -> Unit,
    val onLongClick: (UpscaleRecord) -> Unit,
) : ListAdapter<UpscaleRecord, RecordAdapter.VH>(object : DiffUtil.ItemCallback<UpscaleRecord>() {
    override fun areItemsTheSame(a: UpscaleRecord, b: UpscaleRecord) = a.id == b.id
    override fun areContentsTheSame(a: UpscaleRecord, b: UpscaleRecord) = a == b
}) {
    class VH(val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = getItem(position)
        val b = holder.b
        // Keep the grid lively but sane: portrait up to 4:5, landscape down to 5:4... clamped.
        val ratio = (r.outW.toFloat() / r.outH).coerceIn(0.75f, 1.4f)
        (b.thumb.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = "$ratio:1"
        ImageLoader.load(b.thumb, File(r.thumbFile), 640)
        b.badge.text = "${r.options.scale}×"
        b.title.text = r.name
        b.subtitle.text = "${Fmt.dims(r.outW, r.outH)} · ${r.options.model.label}"
        b.root.setOnClickListener { onClick(r) }
        b.root.setOnLongClickListener { onLongClick(r); true }
    }
}

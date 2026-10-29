package com.vytros.projects.sharpr.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.MediaRecord
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.ui.Actions
import com.vytros.projects.sharpr.ui.Screen
import com.vytros.projects.sharpr.ui.Tab
import com.vytros.projects.sharpr.ui.components.Bitmaps
import com.vytros.projects.sharpr.ui.components.EmptyState
import com.vytros.projects.sharpr.ui.components.GradientButton
import com.vytros.projects.sharpr.ui.components.Segmented
import com.vytros.projects.sharpr.ui.theme.Sharpr
import java.io.File

@Composable
fun LibraryScreen(actions: Actions) {
    val c = Sharpr.colors
    val records by actions.graph.library.records.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<MediaType?>(null) }
    var deleting by remember { mutableStateOf<MediaRecord?>(null) }
    val shown = records.filter { filter == null || it.type == filter }
    val grid = rememberLazyStaggeredGridState()
    LaunchedEffect(actions.state.libraryEpoch) { if (records.isNotEmpty()) grid.scrollToItem(0) }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(160.dp),
        state = grid,
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 130.dp),
        verticalItemSpacing = 12.dp,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Column {
                ScreenHeader(
                    "Library",
                    when (records.size) {
                        0 -> "Your results live here"
                        1 -> "1 result · long-press to delete"
                        else -> "${records.size} results · long-press to delete"
                    },
                    inset = 10.dp,
                )
                if (records.isNotEmpty()) {
                    val options = listOf<MediaType?>(null, MediaType.Image, MediaType.Video, MediaType.Audio)
                    Segmented(
                        listOf("All", "Images", "Videos", "Audio"), options.indexOf(filter), { filter = options[it] },
                        Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                EmptyState(
                    R.drawable.ic_library,
                    if (records.isEmpty()) "Nothing here yet" else "No ${filter?.label?.lowercase() ?: "results"} yet",
                    "Results are kept on this device, ready to compare, share or regenerate.",
                    Modifier.padding(top = 48.dp),
                ) { GradientButton("Go to Studio", onClick = { actions.state.goTab(Tab.Studio) }, icon = R.drawable.ic_sparkle) }
            }
        }
        items(shown, key = { it.id }) { r ->
            val ratio = when (r.type) {
                MediaType.Audio -> 1.25f
                else -> (r.outW.toFloat() / r.outH.coerceAtLeast(1)).coerceIn(0.72f, 1.5f)
            }
            Column(
                Modifier.clip(RoundedCornerShape(22.dp)).combinedClickable(
                    onClick = { actions.state.push(Screen.Detail(r.id)) },
                    onLongClick = { deleting = r },
                ),
            ) {
                RecordThumb(r, Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(22.dp)))
                Box(Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 4.dp)) {
                    Column {
                        Text(r.name, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Fmt.summary(r), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    deleting?.let { r ->
        ConfirmDelete(r, onDismiss = { deleting = null }) {
            Bitmaps.evict(File(r.thumbFile))
            actions.graph.library.remove(r.id)
            deleting = null
        }
    }
}

@Composable
fun ConfirmDelete(r: MediaRecord, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = Sharpr.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Delete “${r.name}”?", style = MaterialTheme.typography.headlineSmall, color = c.text) },
        text = { Text("The result is removed from Sharpr. Copies you saved or shared elsewhere stay.", style = MaterialTheme.typography.bodyMedium, color = c.muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = c.danger, style = MaterialTheme.typography.labelLarge) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep", color = c.text, style = MaterialTheme.typography.labelLarge) } },
    )
}

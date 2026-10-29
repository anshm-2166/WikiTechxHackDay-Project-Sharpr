package com.vytros.projects.sharpr.ui.screens

import android.media.MediaMetadataRetriever
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.Export
import com.vytros.projects.sharpr.data.ImportedMedia
import com.vytros.projects.sharpr.data.MediaRecord
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.engine.Analyzer
import com.vytros.projects.sharpr.engine.ImageIO
import com.vytros.projects.sharpr.engine.media.MediaProbe
import com.vytros.projects.sharpr.engine.media.Waveform
import com.vytros.projects.sharpr.ui.Actions
import com.vytros.projects.sharpr.ui.AudioDraft
import com.vytros.projects.sharpr.ui.ImageDraft
import com.vytros.projects.sharpr.ui.Screen
import com.vytros.projects.sharpr.ui.VideoDraft
import com.vytros.projects.sharpr.ui.components.Bitmaps
import com.vytros.projects.sharpr.ui.components.CardShape
import com.vytros.projects.sharpr.ui.components.CompareSlider
import com.vytros.projects.sharpr.ui.components.Ic
import com.vytros.projects.sharpr.ui.components.IconCircle
import com.vytros.projects.sharpr.ui.components.Segmented
import com.vytros.projects.sharpr.ui.components.SharprCard
import com.vytros.projects.sharpr.ui.components.SoftButton
import com.vytros.projects.sharpr.ui.components.TopBar
import com.vytros.projects.sharpr.ui.components.Waveform
import com.vytros.projects.sharpr.ui.components.pressable
import com.vytros.projects.sharpr.ui.components.rememberABPlayer
import com.vytros.projects.sharpr.ui.theme.Sharpr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DetailScreen(actions: Actions, id: String) {
    val records by actions.graph.library.records.collectAsStateWithLifecycle()
    val record = records.firstOrNull { it.id == id }
    // Close if the record disappears underneath us (deleted, or replaced by a regenerate).
    LaunchedEffect(record == null) { if (record == null) actions.state.pop() }
    record ?: return

    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val c = Sharpr.colors

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(record.name, onBack = { actions.state.pop() }, subtitle = Fmt.summary(record)) {
            IconCircle(R.drawable.ic_info, { showInfo = !showInfo }, description = "Details", tint = if (showInfo) c.accent else c.text)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            when (record.type) {
                MediaType.Image -> ImageCompare(record)
                MediaType.Video -> VideoView(record)
                MediaType.Audio -> AudioView(record)
            }
        }
        AnimatedVisibility(showInfo, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            InfoCard(record)
        }
        ActionBar(actions, record, onDelete = { confirmDelete = true })
    }
    if (confirmDelete) {
        ConfirmDelete(record, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            Bitmaps.evict(File(record.thumbFile))
            actions.graph.library.remove(record.id)
        }
    }
}

@Composable
private fun ImageCompare(r: MediaRecord) {
    val c = Sharpr.colors
    var pair by remember(r.id) { mutableStateOf<Pair<ImageBitmap, ImageBitmap>?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    LaunchedEffect(r.id) {
        pair = withContext(Dispatchers.IO) {
            val after = ImageIO.decodeSampled(File(r.resultFile), 3072) ?: return@withContext null
            val before = ImageIO.decodeSampled(File(r.originalFile), 2048) ?: after
            before.asImageBitmap() to after.asImageBitmap()
        }
    }
    Box(Modifier.fillMaxSize().clip(CardShape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        val p = pair
        if (p == null) CircularProgressIndicator(color = c.accent) else CompareSlider(p.first, p.second, Modifier.fillMaxSize(), onZoom = { zoom = it })
        if (zoom > 1.02f) {
            Text(
                String.format(Locale.US, "%.1f×", zoom), style = MaterialTheme.typography.labelMedium, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
    Text(
        "Drag the handle · pinch to zoom · double-tap to magnify",
        style = MaterialTheme.typography.bodySmall, color = c.muted,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun VideoView(r: MediaRecord) {
    val c = Sharpr.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSharp by remember { mutableStateOf(true) }
    var frames by remember { mutableStateOf<Pair<ImageBitmap, ImageBitmap>?>(null) }
    var grabbing by remember { mutableStateOf(false) }
    val player = remember(r.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(r.resultFile))))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    fun swap(sharp: Boolean) {
        if (sharp == showSharp) return
        val pos = player.currentPosition
        val playing = player.isPlaying
        showSharp = sharp
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(if (sharp) r.resultFile else r.originalFile))), pos)
        player.prepare()
        player.playWhenReady = playing
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(CardShape).background(Color.Black)) {
            val f = frames
            if (f != null) {
                CompareSlider(f.first, f.second, Modifier.fillMaxSize())
                IconCircle(
                    R.drawable.ic_close, { frames = null }, Modifier.align(Alignment.TopEnd).padding(10.dp),
                    size = 38.dp, tint = Color.White, background = Color.Black.copy(alpha = 0.5f), description = "Back to playback",
                )
            } else {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("Original", "Sharpened"), if (showSharp) 1 else 0, { swap(it == 1) }, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            SoftButton(
                if (grabbing) "…" else "Compare frame",
                onClick = {
                    if (grabbing) return@SoftButton
                    player.pause()
                    grabbing = true
                    val atUs = player.currentPosition * 1000
                    scope.launch {
                        frames = withContext(Dispatchers.IO) { grabPair(r, atUs) }
                        grabbing = false
                    }
                },
                icon = R.drawable.ic_layers_ab, tint = c.accent,
            )
        }
    }
}

private fun grabPair(r: MediaRecord, atUs: Long): Pair<ImageBitmap, ImageBitmap>? = runCatching {
    fun grab(path: String) = MediaMetadataRetriever().run {
        try {
            setDataSource(path)
            getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            release()
        }
    }
    val before = grab(r.originalFile) ?: return null
    val after = grab(r.resultFile) ?: return null
    before.asImageBitmap() to after.asImageBitmap()
}.getOrNull()

@Composable
private fun AudioView(r: MediaRecord) {
    val c = Sharpr.colors
    val player = rememberABPlayer(File(r.originalFile), File(r.resultFile))
    var before by remember { mutableStateOf<FloatArray?>(null) }
    var after by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(r.id) {
        withContext(Dispatchers.IO) {
            before = Waveform.read(File(r.peaksIn))
            after = Waveform.read(File(r.peaksOut))
        }
        player.select(1)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SharprCard(Modifier.fillMaxWidth()) {
            WaveRow("Original", before, player.active == 0, c.muted, player.progress) { player.select(0); player.seek(it) }
            Spacer(Modifier.height(14.dp))
            WaveRow("Cleaned", after, player.active == 1, c.audio, player.progress) { player.select(1); player.seek(it) }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircle(
                    if (player.playing) R.drawable.ic_pause else R.drawable.ic_play, { player.toggle() },
                    size = 60.dp, tint = c.onAccent, background = c.audio, description = "Play",
                )
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        "${Fmt.clock(player.positionMs.toLong())} / ${Fmt.clock(r.mediaMs)}",
                        style = MaterialTheme.typography.titleMedium, color = c.text,
                    )
                    Text("Switch while playing to hear the difference at the same moment.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
            Segmented(listOf("Original", "Cleaned"), player.active, { player.select(it) }, Modifier.padding(top = 16.dp))
        }
        if (r.note.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Ic(R.drawable.ic_check, tint = c.success, size = 16.dp)
                Text("  ${r.note}", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
        }
    }
}

@Composable
private fun WaveRow(label: String, peaks: FloatArray?, active: Boolean, color: Color, progress: Float, onSeek: (Float) -> Unit) {
    val c = Sharpr.colors
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (active) c.accent else c.muted)
        Waveform(
            peaks, Modifier.fillMaxWidth().height(58.dp).padding(top = 6.dp),
            color = color.copy(alpha = if (active) 0.35f else 0.22f), played = if (active) color else color.copy(alpha = 0.35f),
            progress = if (active) progress else 0f, bars = 64, onSeek = onSeek,
        )
    }
}

@Composable
private fun InfoCard(r: MediaRecord) {
    val c = Sharpr.colors
    val rows = buildList {
        when (r.type) {
            MediaType.Audio -> {
                add("Original" to "${Fmt.clock(r.mediaMs)} · ${String.format(Locale.US, "%.1f kHz", r.srcW / 1000.0)} · ${if (r.srcH == 1) "mono" else "${r.srcH} ch"}")
                add("Result" to "${String.format(Locale.US, "%.0f kHz", r.outW / 1000.0)} · ${File(r.resultFile).extension.uppercase(Locale.US)}")
                r.audio?.let { a -> add("Clean-up" to "${a.mode.label} ${a.strength}% · hum ${a.hum.label.lowercase()} · clarity ${a.clarity}") }
            }
            else -> {
                add("Original" to "${Fmt.dims(r.srcW, r.srcH)} · ${Fmt.mp(r.srcW, r.srcH)}")
                add("Result" to "${Fmt.dims(r.outW, r.outH)} · ${Fmt.mp(r.outW, r.outH)}")
                r.image?.let { o ->
                    add("Model" to "${o.model.label} · ${o.scale}×" + if (o.aiDenoise) " · AI denoise" else "")
                    val tweaks = buildList {
                        if (o.denoise > 0) add("denoise ${o.denoise}")
                        if (o.natural > 0) add("natural ${o.natural}")
                        if (o.sharpen > 0) add("sharpen ${o.sharpen}")
                        if (o.grain > 0) add("grain ${o.grain}")
                    }.ifEmpty { listOf("none") }.joinToString(", ")
                    add("Finishing" to tweaks)
                }
                r.video?.let { v -> add("Model" to "${v.model.label} · ${v.scale}× · ${if (v.codec == "Hevc") "HEVC" else "H.264"}") }
            }
        }
        add("Engine" to r.backend)
        if (r.note.isNotEmpty() && r.type != MediaType.Audio) add("Notes" to r.note)
        add("Took" to "${Fmt.duration(r.durationMs)} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.createdAt))}")
        add("Size" to Fmt.bytes(File(r.resultFile).length()))
    }
    SharprCard(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), padding = 16.dp) {
        rows.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(k, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.width(86.dp))
                Text(v, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActionBar(actions: Actions, r: MediaRecord, onDelete: () -> Unit) {
    val c = Sharpr.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(26.dp)).background(c.surface)
            .border(1.dp, c.outline, RoundedCornerShape(26.dp)).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Action(R.drawable.ic_share, "Share") { Export.share(context, File(r.resultFile), "Share ${r.type.label.lowercase()}") }
        Action(R.drawable.ic_edit, "Edit") { edit(actions, r) }
        Action(R.drawable.ic_download, "Save") {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { Export.save(context, r) }
                actions.toast(if (ok) "Saved to ${Export.folderLabel(r.type)}" else "Saving needs Android 10 or later. Use Share instead.")
            }
        }
        Action(R.drawable.ic_trash, "Delete", tint = c.danger, onClick = onDelete)
    }
}

@Composable
private fun Action(icon: Int, label: String, tint: Color = Sharpr.colors.text, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp)).pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Ic(icon, tint = tint, size = 22.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Opens the matching editor on the original, prefilled with the record's settings. */
private fun edit(actions: Actions, r: MediaRecord) {
    val original = File(r.originalFile)
    if (!original.exists()) {
        actions.toast("The original is no longer on this device, so it can't be regenerated.")
        return
    }
    val media = ImportedMedia(original, r.name, r.type)
    when (r.type) {
        MediaType.Image -> actions.runAsync {
            val a = withContext(Dispatchers.IO) { Analyzer.analyze(original) }
            if (a == null) actions.toast("Couldn't read the original.")
            else actions.state.push(Screen.ImageEditor(listOf(ImageDraft(media, a)), initial = r.image, replaceId = r.id))
        }
        MediaType.Video -> actions.runAsync {
            val info = withContext(Dispatchers.IO) { MediaProbe.video(original) }
            if (info == null) actions.toast("Couldn't read the original.")
            else actions.state.push(Screen.VideoEditor(VideoDraft(media, info), initial = r.video))
        }
        MediaType.Audio -> actions.runAsync {
            val info = withContext(Dispatchers.IO) { MediaProbe.audio(original) }
            if (info == null) actions.toast("Couldn't read the original.")
            else actions.state.push(Screen.AudioEditor(AudioDraft(media, info), initial = r.audio))
        }
    }
}

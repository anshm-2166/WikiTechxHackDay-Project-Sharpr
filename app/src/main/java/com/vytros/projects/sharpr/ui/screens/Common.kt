package com.vytros.projects.sharpr.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vytros.projects.sharpr.data.MediaRecord
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.engine.media.Waveform
import com.vytros.projects.sharpr.ui.components.Ic
import com.vytros.projects.sharpr.ui.components.mediaIcon
import com.vytros.projects.sharpr.ui.components.rememberBitmap
import com.vytros.projects.sharpr.ui.theme.Sharpr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object Fmt {
    fun dims(w: Int, h: Int) = "$w×$h"
    fun mp(w: Int, h: Int) = String.format(Locale.US, "%.1f MP", w.toLong() * h / 1_000_000.0)
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
    fun duration(ms: Long): String = com.vytros.projects.sharpr.work.Notifications.duration(ms)
    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1 shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1 shl 20).toDouble())
        else -> String.format(Locale.US, "%d KB", b / 1024)
    }
    fun resolutionName(w: Int, h: Int): String {
        val short = minOf(w, h)
        return when {
            short >= 4320 -> "8K"
            short >= 2160 -> "4K"
            short >= 1440 -> "1440p"
            short >= 1080 -> "1080p"
            short >= 720 -> "720p"
            short >= 480 -> "480p"
            else -> "${short}p"
        }
    }

    /** "4× · 1080p → 4K" style subtitle for a record. */
    fun summary(r: MediaRecord): String = when (r.type) {
        MediaType.Image -> "${r.scale}× · ${dims(r.outW, r.outH)}"
        MediaType.Video -> "${r.scale}× · ${resolutionName(r.srcW, r.srcH)} → ${resolutionName(r.outW, r.outH)} · ${clock(r.mediaMs)}"
        MediaType.Audio -> "${r.audio?.mode?.label ?: "Clean"} · ${clock(r.mediaMs)}"
    }
}

/** Thumbnail for any record: picture for images and video, waveform tile for audio. */
@Composable
fun RecordThumb(r: MediaRecord, modifier: Modifier = Modifier, side: Int = 480, showBadge: Boolean = true) {
    val c = Sharpr.colors
    Box(modifier.background(c.surfaceHigh)) {
        when (r.type) {
            MediaType.Audio -> {
                var peaks by remember(r.id) { mutableStateOf<FloatArray?>(null) }
                LaunchedEffect(r.id) { peaks = withContext(Dispatchers.IO) { Waveform.read(File(r.peaksOut)) } }
                Box(
                    Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.audio.copy(alpha = 0.28f), c.accent.copy(alpha = 0.12f)))),
                    contentAlignment = Alignment.Center,
                ) {
                    com.vytros.projects.sharpr.ui.components.Waveform(
                        peaks, Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
                        color = c.audio, played = c.audio, bars = 28,
                    )
                }
            }
            else -> {
                val bmp = rememberBitmap(File(r.thumbFile), side)
                AnimatedVisibility(bmp != null, enter = fadeIn()) {
                    if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        if (showBadge) {
            Row(
                Modifier.align(Alignment.TopStart).padding(9.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ic(mediaIcon(r.type), tint = Color.White, size = 12.dp)
                val label = when (r.type) {
                    MediaType.Audio -> Fmt.clock(r.mediaMs)
                    MediaType.Video -> "${r.scale}× · ${Fmt.clock(r.mediaMs)}"
                    MediaType.Image -> "${r.scale}×"
                }
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

/** Big screen title with an optional trailing slot. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    inset: Dp = 24.dp,
    trailing: @Composable () -> Unit = {},
) {
    val c = Sharpr.colors
    Row(modifier.fillMaxWidth().padding(start = inset, end = (inset - 4.dp).coerceAtLeast(0.dp), top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = c.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        trailing()
    }
}

@Composable
fun MediaIconTile(type: MediaType, modifier: Modifier = Modifier, size: Int = 44) {
    val c = Sharpr.colors
    val tint = c.media(type)
    Box(Modifier.then(modifier).size(size.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Ic(mediaIcon(type), tint = tint, size = (size * 0.48f).dp)
    }
}

/** Column with the standard page padding for editors and settings. */
@Composable
fun PageColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp), content = content)

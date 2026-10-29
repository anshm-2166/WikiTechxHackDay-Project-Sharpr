package com.vytros.projects.sharpr.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.VideoCodec
import com.vytros.projects.sharpr.data.VideoQuality
import com.vytros.projects.sharpr.engine.ModelId
import com.vytros.projects.sharpr.engine.VideoOptions
import com.vytros.projects.sharpr.engine.VideoPipeline
import com.vytros.projects.sharpr.engine.media.VideoCaps
import com.vytros.projects.sharpr.ui.Actions
import com.vytros.projects.sharpr.ui.Screen
import com.vytros.projects.sharpr.ui.Tab
import com.vytros.projects.sharpr.ui.components.Banner
import com.vytros.projects.sharpr.ui.components.BannerKind
import com.vytros.projects.sharpr.ui.components.CardShape
import com.vytros.projects.sharpr.ui.components.GradientButton
import com.vytros.projects.sharpr.ui.components.Ic
import com.vytros.projects.sharpr.ui.components.InfoChip
import com.vytros.projects.sharpr.ui.components.LabeledSegmented
import com.vytros.projects.sharpr.ui.components.ModelPicker
import com.vytros.projects.sharpr.ui.components.SectionLabel
import com.vytros.projects.sharpr.ui.components.Segmented
import com.vytros.projects.sharpr.ui.components.SharprCard
import com.vytros.projects.sharpr.ui.components.SwitchRow
import com.vytros.projects.sharpr.ui.components.TopBar
import com.vytros.projects.sharpr.ui.components.rememberBitmap
import com.vytros.projects.sharpr.ui.theme.Sharpr
import com.vytros.projects.sharpr.work.JobSpec
import java.util.Locale

private val VIDEO_SCALES = listOf(1, 2, 3, 4)
private val PREVIEWS = listOf(0, 3, 10)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoEditorScreen(actions: Actions, settings: AppSettings, screen: Screen.VideoEditor) {
    val c = Sharpr.colors
    val graph = actions.graph
    val d = screen.draft
    val info = d.info

    var options by remember {
        mutableStateOf(
            screen.initial ?: VideoOptions(
                model = settings.videoModel,
                scale = settings.videoModel.fitScale(settings.videoScale),
                skipDuplicates = settings.skipDuplicates,
                codec = settings.videoCodec.name,
                quality = settings.videoQuality.name,
            ),
        )
    }
    var submitted by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (!submitted) graph.library.discardIfUnused(d.media.file) } }

    val codec = runCatching { VideoCodec.valueOf(options.codec) }.getOrDefault(VideoCodec.H264)
    val plan = remember(options.model, options.scale, codec) { VideoPipeline.plan(info, options, codec) }
    val fits = remember(options.model, codec) {
        VIDEO_SCALES.associateWith { s ->
            s in options.model.scales && VideoCaps.supports(codec.mime, VideoCaps.even(info.width * s), VideoCaps.even(info.height * s), info.fps)
        }
    }

    fun submit() {
        graph.queue.enqueue(JobSpec.Video(d.media.file, d.media.name, options))
        submitted = true
        if (actions.needsNotificationPermission) actions.askNotifications()
        actions.state.goTab(Tab.Queue)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Upscale video", onBack = { actions.state.pop() }, subtitle = d.media.name)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            val frame = rememberBitmap(d.media.file, 1280, video = true)
            val ratio = if (info.rotation % 180 == 0) info.width.toFloat() / info.height else info.height.toFloat() / info.width
            Box(
                Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.6f, 2.4f)).clip(CardShape).background(c.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(frame != null) {
                    if (frame != null) Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Box(Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text(Fmt.clock(info.durationUs / 1000), style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip("${Fmt.resolutionName(info.width, info.height)} · ${Fmt.dims(info.width, info.height)}")
                InfoChip(String.format(Locale.US, "%.0f fps", info.fps))
                InfoChip(info.codecLabel)
                InfoChip(String.format(Locale.US, "%,d frames", info.frames))
                if (info.hasAudio) InfoChip("Sound", icon = R.drawable.ic_audio)
            }
            if (info.hdr) {
                Spacer(Modifier.height(12.dp))
                Banner("This is an HDR video. Sharpr works in 8-bit SDR, so colours will be tone-mapped and may look flatter.", BannerKind.Warn)
            }

            SectionLabel("Model", Modifier.padding(top = 18.dp))
            ModelPicker(ModelId.forVideo, options.model, { m ->
                options = options.copy(model = m, scale = m.fitScale(options.scale), aiDenoise = options.aiDenoise && m.hasDenoiseVariant)
            })
            if (options.model.speed <= 2) {
                Spacer(Modifier.height(10.dp))
                Banner("${options.model.label} is heavy: expect seconds per frame. Fast or Anime HD are better for long clips.", BannerKind.Info)
            }

            SectionLabel("Scale", Modifier.padding(top = 18.dp))
            SharprCard(Modifier.fillMaxWidth()) {
                Segmented(
                    VIDEO_SCALES.map { if (it == 1) "1× fix" else "$it×" },
                    VIDEO_SCALES.indexOf(options.scale),
                    { i -> options = options.copy(scale = VIDEO_SCALES[i]) },
                    enabled = { fits[VIDEO_SCALES[it]] == true },
                )
                val p = plan
                Row(Modifier.padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ic(R.drawable.ic_swap, tint = c.accent, size = 16.dp)
                    Text(
                        if (p == null) "  Too large for this device's encoder." else "  Output: ${Fmt.resolutionName(p.outW, p.outH)} · ${Fmt.dims(p.outW, p.outH)}",
                        style = MaterialTheme.typography.bodyMedium, color = if (p == null) c.danger else c.muted,
                    )
                }
                p?.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.warn, modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }
                if (options.model.hasDenoiseVariant) {
                    SwitchRow("AI denoise", "Also removes compression noise and blocking", options.aiDenoise, { options = options.copy(aiDenoise = it) }, Modifier.padding(top = 6.dp))
                }
            }

            SectionLabel("Speed and sound", Modifier.padding(top = 18.dp))
            SharprCard(Modifier.fillMaxWidth()) {
                SwitchRow(
                    "Skip repeated frames", "Frames that didn't change reuse the last result. Big win for anime and static shots.",
                    options.skipDuplicates, { options = options.copy(skipDuplicates = it) },
                )
                SwitchRow(
                    "Clean the soundtrack", if (info.hasAudio) "Remove hiss and hum with the audio engine instead of copying it." else "This clip has no sound.",
                    options.cleanAudio && info.hasAudio, { options = options.copy(cleanAudio = it) }, enabled = info.hasAudio,
                )
                LabeledSegmented(
                    "Process", PREVIEWS.map { if (it == 0) "Whole clip" else "First $it s" }, PREVIEWS.indexOf(options.previewSeconds).coerceAtLeast(0),
                    { i -> options = options.copy(previewSeconds = PREVIEWS[i]) },
                    hint = "A short preview is the quickest way to judge a model before a long run.",
                )
            }

            SectionLabel("Output", Modifier.padding(top = 18.dp))
            SharprCard(Modifier.fillMaxWidth()) {
                LabeledSegmented(
                    "Codec", VideoCodec.entries.map { it.label }, codec.ordinal,
                    { i -> options = options.copy(codec = VideoCodec.entries[i].name) },
                    hint = if (codec == VideoCodec.Hevc) "Smaller files at the same quality; plays on most phones made after 2016." else "Plays everywhere.",
                    enabled = { i -> VideoCaps.hasEncoder(VideoCodec.entries[i].mime) },
                )
                val q = runCatching { VideoQuality.valueOf(options.quality) }.getOrDefault(VideoQuality.Standard)
                LabeledSegmented("Quality", VideoQuality.entries.map { it.label }, q.ordinal, { i -> options = options.copy(quality = VideoQuality.entries[i].name) })
            }

            Estimate(info.frames, info.fps, options)
            Spacer(Modifier.height(20.dp))
        }
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, c.bg))).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp)) {
            GradientButton(
                if (options.previewSeconds > 0) "Upscale ${options.previewSeconds}-second preview" else "Upscale video",
                onClick = ::submit, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_sparkle, enabled = plan != null,
            )
        }
    }
}

@Composable
private fun Estimate(totalFrames: Long, fps: Float, o: VideoOptions) {
    val c = Sharpr.colors
    val frames = if (o.previewSeconds > 0) minOf(totalFrames, (o.previewSeconds * fps).toLong()) else totalFrames
    Row(Modifier.padding(top = 16.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Ic(R.drawable.ic_info, tint = c.muted, size = 16.dp)
        Text(
            String.format(Locale.US, "  About %,d frames to process. Sharpr keeps going in the background; you'll get a notification when it's done.", frames),
            style = MaterialTheme.typography.bodySmall, color = c.muted,
        )
    }
}

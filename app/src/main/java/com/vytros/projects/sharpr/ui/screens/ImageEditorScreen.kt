package com.vytros.projects.sharpr.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.vytros.projects.sharpr.data.EngineMode
import com.vytros.projects.sharpr.engine.GuardVerdict
import com.vytros.projects.sharpr.engine.Guards
import com.vytros.projects.sharpr.engine.ImageAnalysis
import com.vytros.projects.sharpr.engine.ModelId
import com.vytros.projects.sharpr.engine.UpscaleOptions
import com.vytros.projects.sharpr.ui.Actions
import com.vytros.projects.sharpr.ui.Screen
import com.vytros.projects.sharpr.ui.Tab
import com.vytros.projects.sharpr.ui.components.Banner
import com.vytros.projects.sharpr.ui.components.BannerKind
import com.vytros.projects.sharpr.ui.components.CardShape
import com.vytros.projects.sharpr.ui.components.GradientButton
import com.vytros.projects.sharpr.ui.components.Ic
import com.vytros.projects.sharpr.ui.components.InfoChip
import com.vytros.projects.sharpr.ui.components.ModelPicker
import com.vytros.projects.sharpr.ui.components.SectionLabel
import com.vytros.projects.sharpr.ui.components.Segmented
import com.vytros.projects.sharpr.ui.components.SharprCard
import com.vytros.projects.sharpr.ui.components.SliderRow
import com.vytros.projects.sharpr.ui.components.SoftButton
import com.vytros.projects.sharpr.ui.components.SwitchRow
import com.vytros.projects.sharpr.ui.components.TopBar
import com.vytros.projects.sharpr.ui.components.pressable
import com.vytros.projects.sharpr.ui.components.rememberBitmap
import com.vytros.projects.sharpr.ui.theme.Sharpr
import com.vytros.projects.sharpr.work.JobSpec

private val SCALES = listOf(1, 2, 3, 4)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageEditorScreen(actions: Actions, settings: AppSettings, screen: Screen.ImageEditor) {
    val c = Sharpr.colors
    val graph = actions.graph
    val drafts = screen.drafts
    val single = drafts.size == 1
    val editing = screen.replaceId != null || screen.initial != null

    var options by remember {
        mutableStateOf(
            screen.initial ?: UpscaleOptions(model = settings.defaultModel, scale = settings.defaultModel.fitScale(settings.defaultScale)),
        )
    }
    var override by remember { mutableStateOf(editing) }
    var keepPrevious by remember { mutableStateOf(true) }
    var submitted by remember { mutableStateOf(false) }

    // Abandoned imports are clutter; files referenced by the library are never touched.
    DisposableEffect(Unit) {
        onDispose { if (!submitted) drafts.forEach { graph.library.discardIfUnused(it.media.file) } }
    }

    val verdicts = drafts.map { Guards.evaluate(it.analysis.size, it.analysis.detail, options, settings, override) }
    val blocked = verdicts.count { it is GuardVerdict.Blocked }
    val canOverride = verdicts.any { it is GuardVerdict.Blocked && it.canOverride }
    val runnable = drafts.size - blocked

    fun submit() {
        drafts.zip(verdicts).forEach { (d, v) ->
            if (v is GuardVerdict.Ok) {
                graph.queue.enqueue(
                    JobSpec.Image(
                        d.media.file, d.media.name, options.copy(scale = v.scale),
                        replaceId = if (screen.replaceId != null && !keepPrevious) screen.replaceId else null,
                    ),
                )
            }
        }
        submitted = true
        if (actions.needsNotificationPermission) actions.askNotifications()
        actions.state.goTab(Tab.Queue)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar(
            if (editing) "Edit & regenerate" else if (single) "Sharpen image" else "Sharpen ${drafts.size} images",
            onBack = { actions.state.pop() },
            subtitle = if (single) drafts[0].media.name else "Same settings for every image",
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            if (single) SinglePreview(drafts[0].media.file, drafts[0].analysis) else BatchPreview(screen)

            val first = verdicts.firstOrNull()
            if (single && first is GuardVerdict.Blocked) {
                Spacer(Modifier.height(12.dp))
                Banner(first.reason, BannerKind.Warn, title = first.title, action = if (first.canOverride) {
                    { SoftButton("Sharpen anyway", onClick = { override = true }, tint = c.warn) }
                } else null)
            } else if (!single && blocked > 0) {
                Spacer(Modifier.height(12.dp))
                Banner(
                    "$blocked of ${drafts.size} images are already large and sharp, or too big for your output limit. They'll be skipped.",
                    BannerKind.Warn,
                    action = if (canOverride) {
                        { SoftButton("Include them", onClick = { override = true }, tint = c.warn) }
                    } else null,
                )
            } else if (single && first is GuardVerdict.Ok && first.note != null) {
                Spacer(Modifier.height(12.dp))
                Banner(first.note, BannerKind.Info)
            }

            SectionLabel("Model", Modifier.padding(top = 18.dp))
            ModelPicker(ModelId.entries, options.model, { m ->
                options = options.copy(model = m, scale = m.fitScale(options.scale), aiDenoise = options.aiDenoise && m.hasDenoiseVariant)
            })

            SectionLabel("Scale", Modifier.padding(top = 18.dp))
            SharprCard(Modifier.fillMaxWidth()) {
                Segmented(
                    SCALES.map { if (it == 1) "1× fix" else "$it×" },
                    SCALES.indexOf(options.scale),
                    { i -> options = options.copy(scale = SCALES[i]) },
                    enabled = { SCALES[it] in options.model.scales },
                )
                Text(
                    if (options.scale == 1) "Same size, more detail: the network runs at its native scale and is averaged back down."
                    else "Every side ${options.scale}× longer, ${options.scale * options.scale}× the pixels.",
                    style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
                if (options.model.hasDenoiseVariant) {
                    SwitchRow(
                        "AI denoise", "Use the model's noise and JPEG-artefact removing variant",
                        options.aiDenoise, { options = options.copy(aiDenoise = it) }, Modifier.padding(top = 6.dp),
                    )
                }
                SwitchRow(
                    "Quality boost", "Runs every tile 8 ways (flips and rotations) and averages. Cleaner edges, 8× slower.",
                    settings.tta, { v -> graph.settings.update { it.copy(tta = v) } },
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Finishing", Modifier.weight(1f))
                if (single) {
                    InfoChip(
                        "Auto-tune", icon = R.drawable.ic_wand, tint = c.accent, background = c.accent.copy(alpha = 0.12f),
                        modifier = Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(50)).pressable {
                            options = autoTune(options, drafts[0].analysis)
                        },
                    )
                }
            }
            SharprCard(Modifier.fillMaxWidth()) {
                SliderRow("Pre-denoise", options.denoise, { options = options.copy(denoise = it) }, hint = "Classic filter before the network. Best for grainy low-light shots.")
                SliderRow("Natural blend", options.natural, { options = options.copy(natural = it) }, hint = "Mixes in a plain upscale to tame invented texture.")
                SliderRow("Sharpen", options.sharpen, { options = options.copy(sharpen = it) })
                SliderRow("Film grain", options.grain, { options = options.copy(grain = it) }, hint = "A touch of grain hides the 'too clean' AI look.")
            }

            if (editing && screen.replaceId != null) {
                SwitchRow("Keep previous version", "Off replaces the current result", keepPrevious, { keepPrevious = it }, Modifier.padding(top = 10.dp))
            }

            OutputSummary(drafts, verdicts, settings)
            Spacer(Modifier.height(20.dp))
        }
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, c.bg))).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp)) {
            GradientButton(
                text = when {
                    runnable == 0 -> "Nothing to sharpen"
                    editing -> "Regenerate"
                    runnable == 1 -> "Sharpen"
                    else -> "Sharpen $runnable images"
                },
                onClick = ::submit,
                modifier = Modifier.fillMaxWidth(),
                icon = R.drawable.ic_sparkle,
                enabled = runnable > 0,
            )
        }
    }
}

private fun autoTune(o: UpscaleOptions, a: ImageAnalysis) = o.copy(
    denoise = a.suggestedDenoise,
    natural = if (a.noiseSigma > 4.5) 20 else 0,
    sharpen = if (a.detail < ImageAnalysis.SOFT_BELOW) 30 else 0,
    grain = if (a.suggestedDenoise > 30) 8 else 0,
    aiDenoise = o.model.hasDenoiseVariant && a.noiseSigma > 3.0,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SinglePreview(file: java.io.File, analysis: ImageAnalysis) {
    val c = Sharpr.colors
    val bmp = rememberBitmap(file, 1280)
    Box(Modifier.fillMaxWidth().height(260.dp).clip(CardShape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        AnimatedVisibility(bmp != null) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Fit)
        }
    }
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val s = analysis.size
        InfoChip("${Fmt.dims(s.w, s.h)} px")
        InfoChip(Fmt.mp(s.w, s.h))
        InfoChip(analysis.sharpnessLabel, tint = if (analysis.isCrisp) c.success else c.text)
        InfoChip(analysis.noiseLabel, tint = if (analysis.noiseSigma > 4.5) c.warn else c.text)
    }
}

@Composable
private fun BatchPreview(screen: Screen.ImageEditor) {
    LazyRow(contentPadding = PaddingValues(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(screen.drafts, key = { it.media.file.absolutePath }) { d ->
            val bmp = rememberBitmap(d.media.file, 320)
            Box(Modifier.size(112.dp).clip(RoundedCornerShape(18.dp)).background(Sharpr.colors.surfaceHigh)) {
                if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

@Composable
private fun OutputSummary(drafts: List<com.vytros.projects.sharpr.ui.ImageDraft>, verdicts: List<GuardVerdict>, settings: AppSettings) {
    val c = Sharpr.colors
    val ok = drafts.zip(verdicts).filter { it.second is GuardVerdict.Ok }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, start = 4.dp)) {
        if (ok.size == 1) {
            val (d, v) = ok[0]
            val s = (v as GuardVerdict.Ok).scale
            val w = d.analysis.size.w * s
            val h = d.analysis.size.h * s
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ic(R.drawable.ic_swap, tint = c.accent, size = 16.dp)
                Text(
                    "  Result: ${Fmt.dims(w, h)} · ${Fmt.mp(w, h)} · ${settings.format.label}",
                    style = MaterialTheme.typography.bodyMedium, color = c.muted,
                )
            }
        } else if (ok.size > 1) {
            val mp = ok.sumOf { (d, v) -> val s = (v as GuardVerdict.Ok).scale; d.analysis.size.w.toLong() * s * d.analysis.size.h * s } / 1_000_000.0
            Text(String.format(java.util.Locale.US, "Results: %d images · %.0f MP in total", ok.size, mp), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Ic(R.drawable.ic_chip, tint = c.accent, size = 16.dp)
            Text(
                "  " + when (settings.engine) {
                    EngineMode.Auto -> "Vulkan GPU, CPU as a safety net"
                    EngineMode.Gpu -> "GPU" + if (settings.cpuFallback) " with CPU fallback" else " only"
                    EngineMode.Cpu -> "CPU only"
                } + " · ${settings.precision.label} precision",
                style = MaterialTheme.typography.bodyMedium, color = c.muted,
            )
        }
    }
}

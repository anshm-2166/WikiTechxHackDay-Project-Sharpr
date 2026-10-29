package com.vytros.projects.sharpr.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vytros.projects.sharpr.BuildConfig
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.AppFont
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.AudioFormat
import com.vytros.projects.sharpr.data.EngineMode
import com.vytros.projects.sharpr.data.OutFormat
import com.vytros.projects.sharpr.data.Precision
import com.vytros.projects.sharpr.data.ThemeMode
import com.vytros.projects.sharpr.data.VideoCodec
import com.vytros.projects.sharpr.data.VideoQuality
import com.vytros.projects.sharpr.engine.Device
import com.vytros.projects.sharpr.engine.DeviceInfo
import com.vytros.projects.sharpr.engine.ModelId
import com.vytros.projects.sharpr.engine.media.VideoCaps
import com.vytros.projects.sharpr.ui.Actions
import com.vytros.projects.sharpr.ui.components.Ic
import com.vytros.projects.sharpr.ui.components.LabeledSegmented
import com.vytros.projects.sharpr.ui.components.SectionLabel
import com.vytros.projects.sharpr.ui.components.SharprCard
import com.vytros.projects.sharpr.ui.components.SliderRow
import com.vytros.projects.sharpr.ui.components.SoftButton
import com.vytros.projects.sharpr.ui.components.SwitchRow
import com.vytros.projects.sharpr.ui.components.ThemeToggle
import com.vytros.projects.sharpr.ui.components.pressable
import com.vytros.projects.sharpr.ui.theme.Sharpr
import com.vytros.projects.sharpr.ui.theme.bodyFamily
import com.vytros.projects.sharpr.ui.theme.isDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val THREADS = listOf(0, 2, 4, 6, 8)
private val TILES = listOf(0, 64, 128, 200, 256, 400)
private val INPUT_SIDES = listOf(1024, 1536, 2048, 3072, 4096)
private val OUTPUT_MP = listOf(12, 24, 36, 48, 64)
private val SCALES = listOf(1, 2, 3, 4)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(actions: Actions, s: AppSettings) {
    val c = Sharpr.colors
    val store = actions.graph.settings
    fun update(block: (AppSettings) -> AppSettings) = store.update(block)
    val dark = isDarkTheme(s)
    var device by remember { mutableStateOf<DeviceInfo?>(Device.known) }
    LaunchedEffect(Unit) { device = Device.info() }
    val records by actions.graph.library.records.collectAsStateWithLifecycle()
    var bytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(records.size) { bytes = withContext(Dispatchers.IO) { actions.graph.library.bytesUsed() } }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 130.dp),
    ) {
        ScreenHeader("Settings", "Tune Sharpr to your device and taste", inset = 6.dp) {
            ThemeToggle(dark, onToggle = { update { it.copy(themeMode = if (dark) ThemeMode.Light else ThemeMode.Dark) } })
        }

        // ---- Appearance ---------------------------------------------------------------------------
        SectionLabel("Appearance", Modifier.padding(top = 10.dp))
        SharprCard(Modifier.fillMaxWidth()) {
            LabeledSegmented("Theme", ThemeMode.entries.map { it.label }, s.themeMode.ordinal, { i -> update { it.copy(themeMode = ThemeMode.entries[i]) } })
            SwitchRow("AMOLED black", "True black backgrounds in dark mode", s.amoled, { v -> update { it.copy(amoled = v) } })
            if (Build.VERSION.SDK_INT >= 31) {
                SwitchRow("Wallpaper colours", "Material You: take the accent from your wallpaper", s.dynamicColor, { v -> update { it.copy(dynamicColor = v) } })
            }
            Text("Accent", style = MaterialTheme.typography.titleSmall, color = if (s.dynamicColor) c.faint else c.text, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
            AccentRow(s.accent, dark) { a -> update { it.copy(accent = a, dynamicColor = false) } }
            Text("Typeface", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFont.entries.forEach { f ->
                    val on = f == s.font
                    val shape = RoundedCornerShape(18.dp)
                    Column(
                        Modifier.weight(1f).clip(shape)
                            .background(if (on) c.gradient else Brush.linearGradient(listOf(c.surfaceHigh, c.surfaceHigh)))
                            .pressable { update { it.copy(font = f) } }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Aa", style = TextStyle(fontFamily = bodyFamily(f), fontWeight = FontWeight(700), fontSize = 22.sp), color = if (on) c.onAccent else c.text)
                        Text(f.label.substringBefore(' '), style = MaterialTheme.typography.labelSmall, color = if (on) c.onAccent else c.muted, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        // ---- Engine ---------------------------------------------------------------------------------
        SectionLabel("Engine", Modifier.padding(top = 22.dp))
        SharprCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.softGradient).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ic(R.drawable.ic_cpu_gpu, tint = c.accent, size = 22.dp)
                Text(device?.detail ?: "Detecting Vulkan GPU…", style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.padding(start = 12.dp))
            }
            Spacer(Modifier.height(8.dp))
            LabeledSegmented(
                "Run on", EngineMode.entries.map { it.label }, s.engine.ordinal, { i -> update { it.copy(engine = EngineMode.entries[i]) } },
                hint = when (s.engine) {
                    EngineMode.Auto -> "Vulkan GPU when there is one, CPU otherwise. Best of both."
                    EngineMode.Gpu -> "GPU only. Fastest; see the fallback switch if it misbehaves."
                    EngineMode.Cpu -> "Always CPU. Slower, but works on every device."
                },
            )
            SwitchRow("Fall back to CPU", "If the GPU fails mid-job, finish on the CPU", s.cpuFallback, { v -> update { it.copy(cpuFallback = v) } }, enabled = s.engine == EngineMode.Gpu)
            LabeledSegmented("Precision", Precision.entries.map { it.label }, s.precision.ordinal, { i -> update { it.copy(precision = Precision.entries[i]) } }, hint = s.precision.blurb)
            SwitchRow("Quality boost (TTA)", "8× self-ensemble for images: cleaner edges and fewer artefacts, 8× the time", s.tta, { v -> update { it.copy(tta = v) } })
            LabeledSegmented(
                "CPU threads", THREADS.map { if (it == 0) "Auto" else "$it" }, THREADS.indexOf(s.threads).coerceAtLeast(0),
                { i -> update { it.copy(threads = THREADS[i]) } }, hint = "Auto uses the performance cores only, which is usually fastest and coolest.",
            )
            LabeledSegmented(
                "Tile size", TILES.map { if (it == 0) "Auto" else "$it" }, TILES.indexOf(s.tile).coerceAtLeast(0),
                { i -> update { it.copy(tile = TILES[i]) } }, hint = "Auto sizes tiles to your GPU memory. Smaller tiles use less memory; larger ones are a little faster.",
            )
        }

        // ---- Images ---------------------------------------------------------------------------------
        SectionLabel("Images", Modifier.padding(top = 22.dp))
        SharprCard(Modifier.fillMaxWidth()) {
            Text("Default model", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
            ModelChips(ModelId.entries, s.defaultModel) { m -> update { it.copy(defaultModel = m, defaultScale = m.fitScale(it.defaultScale)) } }
            LabeledSegmented(
                "Default scale", SCALES.map { "$it×" }, SCALES.indexOf(s.defaultScale).coerceAtLeast(0),
                { i -> update { it.copy(defaultScale = SCALES[i]) } }, enabled = { SCALES[it] in s.defaultModel.scales },
            )
            LabeledSegmented(
                "Count as high-res from (longest side)", INPUT_SIDES.map { "$it" }, INPUT_SIDES.indexOf(s.maxInputSide).coerceAtLeast(0),
                { i -> update { it.copy(maxInputSide = INPUT_SIDES[i]) } },
            )
            SwitchRow(
                "Strict guard", "Large images that are already sharp can't be forced through. Soft large photos are never blocked.",
                s.strictGuard, { v -> update { it.copy(strictGuard = v) } },
            )
            LabeledSegmented(
                "Largest result (megapixels)", OUTPUT_MP.map { "$it" }, OUTPUT_MP.indexOf(s.maxOutputMp).coerceAtLeast(0),
                { i -> update { it.copy(maxOutputMp = OUTPUT_MP[i]) } }, hint = "Sharpr lowers the scale automatically to stay under this. Higher limits need more RAM.",
            )
            LabeledSegmented("Format", OutFormat.entries.map { it.label }, s.format.ordinal, { i -> update { it.copy(format = OutFormat.entries[i]) } })
            if (s.format != OutFormat.Png) {
                SliderRow("Quality", s.quality, { v -> update { it.copy(quality = v.coerceAtLeast(50)) } }, range = 50..100)
            }
            SwitchRow("Save to gallery automatically", "Every finished result is also copied to Pictures, Movies or Music", s.autoSave, { v -> update { it.copy(autoSave = v) } })
        }

        // ---- Video ----------------------------------------------------------------------------------
        SectionLabel("Video", Modifier.padding(top = 22.dp))
        SharprCard(Modifier.fillMaxWidth()) {
            Text("Default model", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
            ModelChips(ModelId.forVideo, s.videoModel) { m -> update { it.copy(videoModel = m, videoScale = m.fitScale(it.videoScale)) } }
            LabeledSegmented(
                "Default scale", SCALES.map { "$it×" }, SCALES.indexOf(s.videoScale).coerceAtLeast(0),
                { i -> update { it.copy(videoScale = SCALES[i]) } }, enabled = { SCALES[it] in s.videoModel.scales },
            )
            LabeledSegmented(
                "Codec", VideoCodec.entries.map { it.label }, s.videoCodec.ordinal, { i -> update { it.copy(videoCodec = VideoCodec.entries[i]) } },
                enabled = { i -> VideoCaps.hasEncoder(VideoCodec.entries[i].mime) },
            )
            LabeledSegmented("Quality", VideoQuality.entries.map { it.label }, s.videoQuality.ordinal, { i -> update { it.copy(videoQuality = VideoQuality.entries[i]) } })
            SwitchRow("Skip repeated frames", "Reuse the last result when a frame didn't change", s.skipDuplicates, { v -> update { it.copy(skipDuplicates = v) } })
        }

        // ---- Audio ----------------------------------------------------------------------------------
        SectionLabel("Audio", Modifier.padding(top = 22.dp))
        SharprCard(Modifier.fillMaxWidth()) {
            LabeledSegmented("Default format", AudioFormat.entries.map { it.label }, s.audioFormat.ordinal, { i -> update { it.copy(audioFormat = AudioFormat.entries[i]) } })
        }

        // ---- Storage --------------------------------------------------------------------------------
        SectionLabel("Storage", Modifier.padding(top = 22.dp))
        SharprCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${records.size} results", style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text("${Fmt.bytes(bytes)} on this device, originals included", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                SoftButton("Clear", onClick = { confirmClear = true }, tint = c.danger, enabled = records.isNotEmpty())
            }
        }

        // ---- About ----------------------------------------------------------------------------------
        SectionLabel("About", Modifier.padding(top = 22.dp))
        SharprCard(Modifier.fillMaxWidth()) {
            Text("Sharpr ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, color = c.text)
            Text(
                "On-device image, video and audio enhancement. Free and open source (MIT). Nothing you process ever leaves this device.",
                style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Built on ncnn (BSD-3), OpenCV (Apache-2.0), Real-ESRGAN (BSD-3), Real-CUGAN and waifu2x (MIT), RNNoise (BSD-3). " +
                    "Fonts: Bricolage Grotesque, Plus Jakarta Sans, Inter, Outfit, Space Grotesk, JetBrains Mono (SIL OFL).",
                style = MaterialTheme.typography.bodySmall, color = c.faint, modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 14.dp)) {
                SoftButton("Replay the intro", onClick = { update { it.copy(onboarded = false) } }, icon = R.drawable.ic_sparkle)
            }
        }
        Text(
            "A project by The Bug Slayyers",
            style = MaterialTheme.typography.labelMedium, color = c.faint, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = c.surface,
            title = { Text("Clear the library?", style = MaterialTheme.typography.headlineSmall, color = c.text) },
            text = { Text("All results and their originals are deleted from Sharpr. Copies saved to your gallery stay.", style = MaterialTheme.typography.bodyMedium, color = c.muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    actions.graph.library.clear()
                    actions.toast("Library cleared")
                }) { Text("Clear everything", color = c.danger, style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = c.text, style = MaterialTheme.typography.labelLarge) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelChips(models: List<ModelId>, selected: ModelId, onSelect: (ModelId) -> Unit) {
    val c = Sharpr.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        models.forEach { m ->
            val on = m == selected
            Row(
                Modifier.clip(CircleShape)
                    .background(if (on) c.gradient else Brush.linearGradient(listOf(c.surfaceHigh, c.surfaceHigh)))
                    .border(1.dp, if (on) Color.Transparent else c.outline, CircleShape)
                    .pressable { onSelect(m) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) {
                    Ic(R.drawable.ic_check, tint = c.onAccent, size = 14.dp)
                    Spacer(Modifier.size(6.dp))
                }
                Text(m.label, style = MaterialTheme.typography.labelLarge, color = if (on) c.onAccent else c.text)
            }
        }
    }
}

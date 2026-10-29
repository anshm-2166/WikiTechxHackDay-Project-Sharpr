package com.vytros.projects.sharpr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.AudioFormat
import com.vytros.projects.sharpr.engine.AudioMode
import com.vytros.projects.sharpr.engine.AudioOptions
import com.vytros.projects.sharpr.engine.HumFilter
import com.vytros.projects.sharpr.engine.media.AudioCodecs
import com.vytros.projects.sharpr.engine.media.Waveform
import com.vytros.projects.sharpr.ui.Actions
import com.vytros.projects.sharpr.ui.Screen
import com.vytros.projects.sharpr.ui.Tab
import com.vytros.projects.sharpr.ui.components.GradientButton
import com.vytros.projects.sharpr.ui.components.IconCircle
import com.vytros.projects.sharpr.ui.components.InfoChip
import com.vytros.projects.sharpr.ui.components.LabeledSegmented
import com.vytros.projects.sharpr.ui.components.SectionLabel
import com.vytros.projects.sharpr.ui.components.SharprCard
import com.vytros.projects.sharpr.ui.components.SliderRow
import com.vytros.projects.sharpr.ui.components.SwitchRow
import com.vytros.projects.sharpr.ui.components.TopBar
import com.vytros.projects.sharpr.ui.components.rememberABPlayer
import com.vytros.projects.sharpr.ui.theme.Sharpr
import com.vytros.projects.sharpr.work.JobSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioEditorScreen(actions: Actions, settings: AppSettings, screen: Screen.AudioEditor) {
    val c = Sharpr.colors
    val graph = actions.graph
    val d = screen.draft
    val info = d.info
    var options by remember { mutableStateOf(screen.initial ?: AudioOptions(format = settings.audioFormat.name)) }
    var submitted by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (!submitted) graph.library.discardIfUnused(d.media.file) } }

    // Waveform of the original, for anything up to 20 minutes.
    var peaks by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(d.media.file) {
        if (info.durationUs > 20 * 60 * 1_000_000L) return@LaunchedEffect
        peaks = withContext(Dispatchers.IO) {
            runCatching {
                val pcm = File(graph.library.scratch, "preview-${d.media.file.nameWithoutExtension}.pcm")
                val peaksFile = File(pcm.absolutePath + ".peaks")
                val pi = AudioCodecs.decodeToPcm(d.media.file, pcm)
                Waveform.fromPcm(pcm, pi.channels, peaksFile)
                pcm.delete()
                Waveform.read(peaksFile).also { peaksFile.delete() }
            }.getOrNull()
        }
    }
    val player = rememberABPlayer(d.media.file)

    fun submit() {
        graph.queue.enqueue(JobSpec.Audio(d.media.file, d.media.name, options))
        submitted = true
        player.pause()
        if (actions.needsNotificationPermission) actions.askNotifications()
        actions.state.goTab(Tab.Queue)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Clean audio", onBack = { actions.state.pop() }, subtitle = d.media.name)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            SharprCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(
                        if (player.playing) R.drawable.ic_pause else R.drawable.ic_play, { player.toggle() },
                        size = 52.dp, tint = c.onAccent, background = c.audio, description = "Play original",
                    )
                    com.vytros.projects.sharpr.ui.components.Waveform(
                        peaks, Modifier.weight(1f).height(64.dp).padding(start = 14.dp),
                        played = c.audio, progress = player.progress, onSeek = { player.seek(it) },
                    )
                }
                Text(
                    "${Fmt.clock(player.positionMs.toLong())} / ${Fmt.clock(info.durationUs / 1000)} · original",
                    style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 10.dp),
                )
            }
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(String.format(Locale.US, "%.1f kHz", info.sampleRate / 1000.0))
                InfoChip(if (info.channels == 1) "Mono" else if (info.channels == 2) "Stereo" else "${info.channels} channels")
                InfoChip(info.mime.substringAfter('/').uppercase(Locale.US))
                InfoChip(Fmt.clock(info.durationUs / 1000))
            }

            SectionLabel("Noise removal", Modifier.padding(top = 18.dp))
            SharprCard(Modifier.fillMaxWidth()) {
                LabeledSegmented(
                    "Mode", AudioMode.entries.map { it.label }, options.mode.ordinal,
                    { i -> options = options.copy(mode = AudioMode.entries[i]) }, hint = options.mode.blurb,
                )
                if (options.mode != AudioMode.Off) {
                    SliderRow(
                        "Strength", options.strength, { options = options.copy(strength = it) },
                        valueText = { "$it%" },
                        hint = when {
                            options.strength < 35 -> "Light touch: keeps room ambience."
                            options.strength < 75 -> "Balanced: clear voice, natural tail."
                            else -> "Aggressive: maximum quiet between words, may sound processed."
                        },
                    )
                }
            }

            SectionLabel("Enhance", Modifier.padding(top = 18.dp))
            SharprCard(Modifier.fillMaxWidth()) {
                LabeledSegmented(
                    "Mains hum", HumFilter.entries.map { it.label }, options.hum.ordinal,
                    { i -> options = options.copy(hum = HumFilter.entries[i]) },
                    hint = "Auto finds 50 or 60 Hz buzz (and its harmonics) and only removes it if it's there.",
                )
                SwitchRow("Rumble filter", "Cuts wind, handling noise and traffic below 75 Hz", options.highpass, { options = options.copy(highpass = it) })
                SliderRow("Clarity", options.clarity, { options = options.copy(clarity = it) }, hint = "Presence and air: lifts 3 kHz and 10 kHz for intelligibility.")
                SwitchRow("Level loudness", "Normalises to −16 LUFS, the podcast and streaming standard", options.normalize, { options = options.copy(normalize = it) })
            }

            SectionLabel("Output", Modifier.padding(top = 18.dp))
            SharprCard(Modifier.fillMaxWidth()) {
                val fmt = runCatching { AudioFormat.valueOf(options.format) }.getOrDefault(AudioFormat.M4a)
                LabeledSegmented(
                    "Format", AudioFormat.entries.map { it.label }, fmt.ordinal,
                    { i -> options = options.copy(format = AudioFormat.entries[i].name) },
                    hint = if (fmt == AudioFormat.Wav) "Lossless, 48 kHz 16-bit. Large files." else "AAC at 192 kbps, 48 kHz. Small and plays everywhere.",
                )
            }
            Spacer(Modifier.height(20.dp))
        }
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, c.bg))).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp)) {
            GradientButton("Clean audio", onClick = ::submit, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_sparkle)
        }
    }
}

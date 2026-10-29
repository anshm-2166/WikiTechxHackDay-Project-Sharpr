package com.vytros.projects.sharpr.ui.screens

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.data.ThemeMode
import com.vytros.projects.sharpr.engine.Device
import com.vytros.projects.sharpr.engine.DeviceInfo
import com.vytros.projects.sharpr.ui.Actions
import com.vytros.projects.sharpr.ui.Screen
import com.vytros.projects.sharpr.ui.Tab
import com.vytros.projects.sharpr.ui.components.CardShape
import com.vytros.projects.sharpr.ui.components.GradientText
import com.vytros.projects.sharpr.ui.components.Ic
import com.vytros.projects.sharpr.ui.components.LinearMeter
import com.vytros.projects.sharpr.ui.components.ProgressRing
import com.vytros.projects.sharpr.ui.components.SectionLabel
import com.vytros.projects.sharpr.ui.components.SharpenHero
import com.vytros.projects.sharpr.ui.components.SharprCard
import com.vytros.projects.sharpr.ui.components.ThemeToggle
import com.vytros.projects.sharpr.ui.components.mediaIcon
import com.vytros.projects.sharpr.ui.components.pressable
import com.vytros.projects.sharpr.ui.theme.Sharpr
import com.vytros.projects.sharpr.ui.theme.isDarkTheme
import java.util.Calendar

@Composable
fun StudioScreen(actions: Actions, settings: AppSettings) {
    val c = Sharpr.colors
    val graph = actions.graph
    val records by graph.library.records.collectAsStateWithLifecycle()
    val jobs by graph.queue.jobs.collectAsStateWithLifecycle()
    val running = jobs.firstOrNull { it.running }
    val waiting = jobs.count { it.status == com.vytros.projects.sharpr.work.JobStatus.Queued }
    var device by remember { mutableStateOf<DeviceInfo?>(Device.known) }
    LaunchedEffect(Unit) { device = Device.info() }
    val dark = isDarkTheme(settings)

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(bottom = 130.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.labelLarge, color = c.muted)
                    GradientText("Sharpr")
                }
                ThemeToggle(dark, onToggle = {
                    graph.settings.update { it.copy(themeMode = if (dark) ThemeMode.Light else ThemeMode.Dark) }
                })
            }
        }
        item {
            Box(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp).fillMaxWidth().clip(CardShape)
                    .background(c.surface).border(1.dp, c.outline, CardShape),
            ) {
                Column {
                    SharpenHero(Modifier.fillMaxWidth().aspectRatio(1.9f).padding(10.dp))
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("What are we sharpening?", style = MaterialTheme.typography.headlineSmall, color = c.text)
                            Text(
                                device?.summary?.let { "Runs on $it · nothing leaves your phone" } ?: "All on-device · nothing leaves your phone",
                                style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 18.dp).height(232.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CreateTile(
                    MediaType.Image, "Image", "Upscale up to 4×, restore and denoise",
                    Modifier.weight(1.1f).fillMaxHeight(), big = true, onClick = actions.pickImages,
                )
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CreateTile(MediaType.Video, "Video", "Up to 4K, keeps sound", Modifier.weight(1f).fillMaxWidth(), onClick = actions.pickVideo)
                    CreateTile(MediaType.Audio, "Audio", "Remove noise & hum", Modifier.weight(1f).fillMaxWidth(), onClick = actions.pickAudio)
                }
            }
        }
        item {
            AnimatedVisibility(running != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                running?.let { job ->
                    SharprCard(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp).fillMaxWidth(), onClick = { actions.state.goTab(Tab.Queue) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(contentAlignment = Alignment.Center) {
                                ProgressRing(job.fraction, Modifier.size(54.dp), stroke = 6.dp)
                                Ic(mediaIcon(job.spec.type), tint = c.media(job.spec.type), size = 20.dp)
                            }
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(job.spec.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${job.stage} · ${(job.fraction * 100).toInt()}%" + (job.etaMs()?.let { " · ${Fmt.duration(it)} left" } ?: "") +
                                        if (waiting > 0) " · $waiting queued" else "",
                                    style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(8.dp))
                                LinearMeter(job.fraction, height = 5.dp)
                            }
                        }
                    }
                }
            }
        }
        if (records.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Recent", Modifier.weight(1f))
                    Text(
                        "See all", style = MaterialTheme.typography.labelLarge, color = c.accent,
                        modifier = Modifier.clip(CircleShape).pressable { actions.state.goTab(Tab.Library) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(records.take(12), key = { it.id }) { r ->
                        Column(Modifier.width(138.dp).pressable { actions.state.push(Screen.Detail(r.id)) }) {
                            RecordThumb(r, Modifier.size(138.dp).clip(RoundedCornerShape(20.dp)), side = 320)
                            Text(r.name, style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
                            Text(Fmt.summary(r), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
                        }
                    }
                }
            }
        }
        item {
            SectionLabel("Good to know", Modifier.padding(start = 18.dp, top = 22.dp))
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Tip(R.drawable.ic_wand, "Restore old JPEGs", "The Restore model cleans blocks and noise without enlarging. Pair it with 1× in Image.")
                Tip(R.drawable.ic_film, "Try a preview first", "Video can process just the first few seconds, so you can check settings before a long run.")
                Tip(R.drawable.ic_mic, "Voice vs Universal", "Voice uses a neural network trained on speech; Universal is gentler and works on music.")
            }
        }
    }
}

@Composable
private fun CreateTile(type: MediaType, title: String, subtitle: String, modifier: Modifier, big: Boolean = false, onClick: () -> Unit) {
    val c = Sharpr.colors
    val tint = c.media(type)
    Box(
        modifier.clip(CardShape)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = if (c.isDark) 0.30f else 0.20f), tint.copy(alpha = 0.06f))))
            .border(1.dp, tint.copy(alpha = 0.28f), CardShape)
            .pressable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            Modifier.size(if (big) 52.dp else 42.dp).clip(CircleShape).background(tint),
            contentAlignment = Alignment.Center,
        ) { Ic(mediaIcon(type), tint = Color.White, size = if (big) 24.dp else 20.dp) }
        Box(Modifier.align(Alignment.TopEnd).size(30.dp).clip(CircleShape).background(c.surface.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
            Ic(R.drawable.ic_plus, tint = tint, size = 16.dp)
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(title, style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tip(icon: Int, title: String, text: String) {
    val c = Sharpr.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.outline, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.softGradient), contentAlignment = Alignment.Center) { Ic(icon, tint = c.accent, size = 18.dp) }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(text, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..22 -> "Good evening"
    else -> "Up late?"
}

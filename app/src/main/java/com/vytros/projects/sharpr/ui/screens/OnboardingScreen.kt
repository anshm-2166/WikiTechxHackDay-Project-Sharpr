package com.vytros.projects.sharpr.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.Accent
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.ThemeMode
import com.vytros.projects.sharpr.engine.Device
import com.vytros.projects.sharpr.engine.DeviceInfo
import com.vytros.projects.sharpr.ui.Actions
import com.vytros.projects.sharpr.ui.components.FilmStripArt
import com.vytros.projects.sharpr.ui.components.GradientButton
import com.vytros.projects.sharpr.ui.components.Ic
import com.vytros.projects.sharpr.ui.components.InfoChip
import com.vytros.projects.sharpr.ui.components.PhotoStackArt
import com.vytros.projects.sharpr.ui.components.PrivacyArt
import com.vytros.projects.sharpr.ui.components.Segmented
import com.vytros.projects.sharpr.ui.components.SharpenHero
import com.vytros.projects.sharpr.ui.components.SharprCard
import com.vytros.projects.sharpr.ui.components.SoftButton
import com.vytros.projects.sharpr.ui.components.WaveArt
import com.vytros.projects.sharpr.ui.components.pressable
import com.vytros.projects.sharpr.ui.theme.Sharpr
import com.vytros.projects.sharpr.ui.theme.isDarkTheme
import kotlinx.coroutines.launch

private data class Page(val eyebrow: String, val title: String, val body: String, val chips: List<String>, val art: @Composable (Modifier) -> Unit)

@Composable
fun OnboardingScreen(actions: Actions, settings: AppSettings, onDone: () -> Unit) {
    val c = Sharpr.colors
    val pages = remember {
        listOf(
            Page(
                "Welcome to Sharpr", "Make everything\nsharper.",
                "Images, video and audio, enhanced by AI that runs entirely on your phone.",
                listOf("On-device", "No uploads", "No account"),
            ) { SharpenHero(it) },
            Page(
                "Images", "Up to 4× the detail.",
                "Six models for photos, anime and art: upscale, restore JPEG damage or clean noise, then compare with a slider.",
                listOf("Real-ESRGAN", "Real-CUGAN", "waifu2x"),
            ) { PhotoStackArt(it) },
            Page(
                "Video", "Frame by frame,\nup to 4K.",
                "Upscale clips on the GPU while the soundtrack is kept or cleaned. Repeated frames are reused, so anime flies.",
                listOf("H.264 / HEVC", "Keeps audio", "Background"),
            ) { FilmStripArt(it) },
            Page(
                "Audio", "Hear every word.",
                "Strip hiss, hum and background noise from voice notes, podcasts and music, then level the loudness.",
                listOf("RNNoise AI", "De-hum", "Loudness"),
            ) { WaveArt(it) },
        )
    }
    val total = pages.size + 1
    val pager = rememberPagerState(pageCount = { total })
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(c.bg)) {
        // Soft accent glow behind everything.
        Box(
            Modifier.fillMaxWidth().height(420.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.16f), Color.Transparent))),
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sharpr", style = MaterialTheme.typography.headlineSmall, color = c.text, modifier = Modifier.weight(1f))
                if (pager.currentPage < total - 1) {
                    Text(
                        "Skip", style = MaterialTheme.typography.labelLarge, color = c.muted,
                        modifier = Modifier.clip(CircleShape).pressable { scope.launch { pager.animateScrollToPage(total - 1) } }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { index ->
                if (index < pages.size) PageContent(pages[index]) else SetupPage(actions, settings)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(total) { i ->
                        val on = i == pager.currentPage
                        val w by animateDpAsState(if (on) 26.dp else 8.dp, label = "dot")
                        val col by animateColorAsState(if (on) c.accent else c.outline, label = "dotColor")
                        Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(col))
                    }
                }
                val last = pager.currentPage == total - 1
                GradientButton(
                    if (last) "Start sharpening" else "Next",
                    onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    icon = if (last) R.drawable.ic_sparkle else R.drawable.ic_arrow_right,
                )
            }
        }
    }
}

@Composable
private fun PageContent(page: Page) {
    val c = Sharpr.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.Start) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            page.art(Modifier.fillMaxWidth().aspectRatio(1.25f))
        }
        Text(page.eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.accent)
        Text(page.title, style = MaterialTheme.typography.displayMedium, color = c.text, modifier = Modifier.padding(top = 8.dp))
        Text(page.body, style = MaterialTheme.typography.bodyLarge, color = c.muted, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 18.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            page.chips.forEach { InfoChip(it) }
        }
    }
}

@Composable
private fun SetupPage(actions: Actions, settings: AppSettings) {
    val c = Sharpr.colors
    var device by remember { mutableStateOf<DeviceInfo?>(Device.known) }
    LaunchedEffect(Unit) { device = Device.info() }
    val dark = isDarkTheme(settings)
    var notifAsked by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            PrivacyArt(Modifier.size(150.dp))
        }
        Text("YOUR SHARPR", style = MaterialTheme.typography.labelSmall, color = c.accent)
        Text("Make it yours.", style = MaterialTheme.typography.displaySmall, color = c.text, modifier = Modifier.padding(top = 6.dp))
        Text(
            "Everything stays on this device. Change any of this later in Settings.",
            style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        SharprCard(Modifier.fillMaxWidth()) {
            Text("Theme", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
            Segmented(
                ThemeMode.entries.map { it.label }, settings.themeMode.ordinal,
                { i -> actions.graph.settings.update { it.copy(themeMode = ThemeMode.entries[i]) } },
            )
            Text("Accent", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
            AccentRow(settings.accent, dark) { a -> actions.graph.settings.update { it.copy(accent = a) } }
        }
        Spacer(Modifier.height(12.dp))

        SharprCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(c.softGradient), contentAlignment = Alignment.Center) {
                    Ic(R.drawable.ic_cpu_gpu, tint = c.accent, size = 20.dp)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("Engine", style = MaterialTheme.typography.titleSmall, color = c.text)
                    AnimatedContent(device, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "device") { d ->
                        Text(d?.summary ?: "Looking for a Vulkan GPU…", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (actions.needsNotificationPermission && !notifAsked) {
            SharprCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(c.softGradient), contentAlignment = Alignment.Center) {
                        Ic(R.drawable.ic_notification, tint = c.accent, size = 20.dp)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                        Text("Progress notifications", style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text("Follow long videos with the app closed.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    SoftButton("Allow", onClick = { notifAsked = true; actions.askNotifications() }, tint = c.accent)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(
            "Models: Real-ESRGAN (BSD-3), Real-CUGAN and waifu2x (MIT), RNNoise (BSD-3).",
            style = MaterialTheme.typography.bodySmall, color = c.faint, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
    }
}

@Composable
fun AccentRow(selected: Accent, dark: Boolean, onSelect: (Accent) -> Unit) {
    val c = Sharpr.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Accent.entries.forEach { a ->
            val on = a == selected
            val start = Color(if (dark) a.startDark else a.start)
            val end = Color(if (dark) a.endDark else a.end)
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(start, end)))
                    .border(if (on) 3.dp else 0.dp, if (on) c.text else Color.Transparent, CircleShape)
                    .pressable { onSelect(a) },
                contentAlignment = Alignment.Center,
            ) { if (on) Ic(R.drawable.ic_check, tint = Color.White, size = 18.dp, description = a.label) }
        }
    }
}

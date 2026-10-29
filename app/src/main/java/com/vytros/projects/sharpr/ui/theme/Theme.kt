package com.vytros.projects.sharpr.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.data.ThemeMode

/** Sharpr's palette. Material's ColorScheme is derived from it so stock components fit in. */
@Immutable
data class SharprColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    val danger: Color,
    val warn: Color,
    val success: Color,
    val image: Color,
    val video: Color,
    val audio: Color,
) {
    val gradient: Brush get() = Brush.linearGradient(listOf(accent, accent2))
    val softGradient: Brush get() = Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), accent2.copy(alpha = 0.10f)))

    fun media(type: MediaType) = when (type) {
        MediaType.Image -> image
        MediaType.Video -> video
        MediaType.Audio -> audio
    }
}

val LocalSharpr = staticCompositionLocalOf { lightPalette(Color(0xFF6D4AFF), Color(0xFFD946EF)) }

object Sharpr {
    val colors: SharprColors @Composable get() = LocalSharpr.current
}

private fun lightPalette(a: Color, b: Color) = SharprColors(
    isDark = false,
    bg = Color(0xFFF6F5FA),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEFEEF6),
    surfaceHighest = Color(0xFFE5E3EF),
    outline = Color(0xFFE2E0EC),
    text = Color(0xFF12111A),
    muted = Color(0xFF615F78),
    faint = Color(0xFF9896AC),
    accent = a,
    accent2 = b,
    onAccent = Color.White,
    danger = Color(0xFFD7263D),
    warn = Color(0xFFB45309),
    success = Color(0xFF15803D),
    image = Color(0xFF7C3AED),
    video = Color(0xFFE5484D),
    audio = Color(0xFF0E9F8E),
)

private fun darkPalette(a: Color, b: Color, amoled: Boolean) = SharprColors(
    isDark = true,
    bg = if (amoled) Color.Black else Color(0xFF09090F),
    surface = if (amoled) Color(0xFF0B0B11) else Color(0xFF13131C),
    surfaceHigh = if (amoled) Color(0xFF15151D) else Color(0xFF1C1C28),
    surfaceHighest = if (amoled) Color(0xFF1E1E28) else Color(0xFF262635),
    outline = if (amoled) Color(0xFF20202B) else Color(0xFF2A2A3A),
    text = Color(0xFFF4F3F9),
    muted = Color(0xFFA09EB6),
    faint = Color(0xFF6C6A82),
    accent = a,
    accent2 = b,
    onAccent = Color.White,
    danger = Color(0xFFFF6B7A),
    warn = Color(0xFFF5B94B),
    success = Color(0xFF4ADE80),
    image = Color(0xFFA78BFA),
    video = Color(0xFFFF7A6E),
    audio = Color(0xFF3EE0C8),
)

private fun schemeFrom(c: SharprColors, base: ColorScheme): ColorScheme = base.copy(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = lerp(c.surface, c.accent, 0.18f),
    onPrimaryContainer = c.text,
    secondary = c.accent2,
    onSecondary = c.onAccent,
    background = c.bg,
    onBackground = c.text,
    surface = c.surface,
    onSurface = c.text,
    surfaceVariant = c.surfaceHigh,
    onSurfaceVariant = c.muted,
    surfaceContainerLowest = c.bg,
    surfaceContainerLow = c.surface,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surfaceHigh,
    surfaceContainerHighest = c.surfaceHighest,
    outline = c.outline,
    outlineVariant = c.outline,
    error = c.danger,
    inverseSurface = c.text,
    inverseOnSurface = c.bg,
)

@Composable
fun isDarkTheme(s: AppSettings): Boolean = when (s.themeMode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun SharprTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isDarkTheme(settings)
    val context = LocalContext.current
    val target = remember(dark, settings.accent, settings.amoled, settings.dynamicColor) {
        if (settings.dynamicColor && Build.VERSION.SDK_INT >= 31) {
            val dyn = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            val base = if (dark) darkPalette(dyn.primary, dyn.tertiary, settings.amoled) else lightPalette(dyn.primary, dyn.tertiary)
            base.copy(
                surfaceHigh = if (dark && settings.amoled) base.surfaceHigh else dyn.surfaceContainerHigh,
                onAccent = dyn.onPrimary,
            )
        } else if (dark) {
            darkPalette(Color(settings.accent.startDark), Color(settings.accent.endDark), settings.amoled)
        } else {
            lightPalette(Color(settings.accent.start), Color(settings.accent.end))
        }
    }
    // Theme switches cross-fade instead of snapping.
    val spec = tween<Color>(380)
    val bg by animateColorAsState(target.bg, spec, label = "bg")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val surfaceHigh by animateColorAsState(target.surfaceHigh, spec, label = "surfaceHigh")
    val surfaceHighest by animateColorAsState(target.surfaceHighest, spec, label = "surfaceHighest")
    val outline by animateColorAsState(target.outline, spec, label = "outline")
    val text by animateColorAsState(target.text, spec, label = "text")
    val muted by animateColorAsState(target.muted, spec, label = "muted")
    val accent by animateColorAsState(target.accent, spec, label = "accent")
    val accent2 by animateColorAsState(target.accent2, spec, label = "accent2")
    val colors = target.copy(
        bg = bg, surface = surface, surfaceHigh = surfaceHigh, surfaceHighest = surfaceHighest, outline = outline,
        text = text, muted = muted, accent = accent, accent2 = accent2,
    )
    val scheme = schemeFrom(colors, if (dark) darkColorScheme() else lightColorScheme())
    val typography = remember(settings.font) { sharprTypography(settings.font) }
    CompositionLocalProvider(LocalSharpr provides colors) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

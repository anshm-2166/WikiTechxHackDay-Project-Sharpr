package com.vytros.projects.sharpr.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vytros.projects.sharpr.R
import com.vytros.projects.sharpr.data.MediaType
import com.vytros.projects.sharpr.engine.ModelId
import com.vytros.projects.sharpr.ui.theme.MonoStyle
import com.vytros.projects.sharpr.ui.theme.Sharpr

val CardShape = RoundedCornerShape(26.dp)
val InnerShape = RoundedCornerShape(18.dp)

fun mediaIcon(type: MediaType) = when (type) {
    MediaType.Image -> R.drawable.ic_image
    MediaType.Video -> R.drawable.ic_video
    MediaType.Audio -> R.drawable.ic_audio
}

/** Gentle shrink while pressed, for anything tappable that is not a stock Material component. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun Ic(@DrawableRes res: Int, tint: Color = Sharpr.colors.text, size: Dp = 22.dp, modifier: Modifier = Modifier, description: String? = null) =
    Icon(painterResource(res), description, modifier.size(size), tint = tint)

@Composable
fun SharprCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Sharpr.colors
    val base = modifier
        .clip(CardShape)
        .background(c.surface)
        .border(1.dp, c.outline.copy(alpha = if (c.isDark) 0.9f else 0.7f), CardShape)
    Column(
        (if (onClick != null) base.pressable(onClick = onClick) else base).padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(start = 6.dp, bottom = 10.dp, top = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Sharpr.colors.muted,
    )
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val c = Sharpr.colors
    Box(
        modifier
            .heightIn(min = 58.dp)
            .shadow(if (enabled) 18.dp else 0.dp, CircleShape, ambientColor = c.accent, spotColor = c.accent)
            .clip(CircleShape)
            .background(if (enabled) c.gradient else Brush.linearGradient(listOf(c.surfaceHighest, c.surfaceHighest)))
            .pressable(enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.onAccent, strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
            } else if (icon != null) {
                Ic(icon, tint = if (enabled) c.onAccent else c.muted, size = 20.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) c.onAccent else c.muted)
        }
    }
}

@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    tint: Color = Sharpr.colors.text,
    enabled: Boolean = true,
) {
    val c = Sharpr.colors
    Row(
        modifier
            .heightIn(min = 46.dp)
            .clip(CircleShape)
            .background(c.surfaceHigh)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Ic(icon, tint = tint, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
fun IconCircle(
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    size: Dp = 46.dp,
    tint: Color = Sharpr.colors.text,
    background: Color = Sharpr.colors.surfaceHigh,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Ic(icon, tint = tint, size = size * 0.46f, description = description) }
}

/** Pill-shaped choice with a sliding gradient thumb. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (Int) -> Boolean = { true },
) {
    val c = Sharpr.colors
    BoxWithConstraints(
        modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(c.surfaceHigh).padding(4.dp),
    ) {
        val n = options.size.coerceAtLeast(1)
        val segment = maxWidth / n
        val x by animateDpAsState(segment * selected.coerceIn(0, n - 1), spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow), label = "seg")
        if (selected in options.indices) {
            Box(Modifier.offset(x = x).width(segment).fillMaxHeight().shadow(6.dp, CircleShape, spotColor = c.accent).clip(CircleShape).background(c.gradient))
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val ok = enabled(i)
                val color by animateColorAsState(
                    when {
                        on -> c.onAccent
                        ok -> c.muted
                        else -> c.faint.copy(alpha = 0.5f)
                    },
                    tween(220), label = "segText",
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable(enabled = ok && !on) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

@Composable
fun LabeledSegmented(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, hint: String? = null, enabled: (Int) -> Boolean = { true }) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Sharpr.colors.text, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
        Segmented(options, selected, onSelect, enabled = enabled)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Sharpr.colors.muted, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        }
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 0..100,
    valueText: (Int) -> String = { "$it" },
    hint: String? = null,
) {
    val c = Sharpr.colors
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
            Text(
                valueText(value), style = MonoStyle, color = c.accent,
                modifier = Modifier.clip(CircleShape).background(c.accent.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = c.accent,
                activeTrackColor = c.accent,
                inactiveTrackColor = c.surfaceHighest,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Sharpr.colors
    Row(
        modifier.fillMaxWidth().clip(InnerShape).clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) c.text else c.faint)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accent,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = c.surfaceHighest,
                uncheckedThumbColor = c.muted,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, tint: Color = Sharpr.colors.text, background: Color = Sharpr.colors.surfaceHigh) {
    Row(
        modifier.clip(CircleShape).background(background).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Ic(icon, tint = tint, size = 14.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

enum class BannerKind { Info, Warn, Error, Success }

@Composable
fun Banner(text: String, kind: BannerKind = BannerKind.Info, modifier: Modifier = Modifier, title: String? = null, action: (@Composable RowScope.() -> Unit)? = null) {
    val c = Sharpr.colors
    val tint = when (kind) {
        BannerKind.Info -> c.accent
        BannerKind.Warn -> c.warn
        BannerKind.Error -> c.danger
        BannerKind.Success -> c.success
    }
    Column(
        modifier.fillMaxWidth().clip(InnerShape).background(tint.copy(alpha = if (c.isDark) 0.13f else 0.09f))
            .border(1.dp, tint.copy(alpha = 0.25f), InnerShape).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Ic(if (kind == BannerKind.Success) R.drawable.ic_check else R.drawable.ic_info, tint = tint, size = 18.dp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.padding(bottom = 2.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = if (title != null) c.muted else c.text)
            }
        }
        if (action != null) Row(Modifier.padding(top = 12.dp, start = 30.dp), content = action)
    }
}

/** Sun/moon button that flips the theme with a little spin. */
@Composable
fun ThemeToggle(dark: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = Sharpr.colors
    val spin by animateFloatAsState(if (dark) 360f else 0f, tween(520), label = "spin")
    Box(
        modifier.size(46.dp).clip(CircleShape).background(c.surfaceHigh).border(1.dp, c.outline, CircleShape).pressable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = dark,
            transitionSpec = { (scaleIn(initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut()) },
            label = "themeIcon",
        ) { d ->
            Ic(
                if (d) R.drawable.ic_moon else R.drawable.ic_sun,
                tint = if (d) c.accent else Color(0xFFF59E0B),
                size = 21.dp,
                modifier = Modifier.graphicsLayer { rotationZ = spin },
                description = if (d) "Switch to light theme" else "Switch to dark theme",
            )
        }
    }
}

@Composable
fun ProgressRing(fraction: Float, modifier: Modifier = Modifier, stroke: Dp = 10.dp, track: Color = Sharpr.colors.surfaceHigh) {
    val c = Sharpr.colors
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(300), label = "ring")
    Canvas(modifier) {
        val s = stroke.toPx()
        val inset = s / 2
        val arcSize = Size(size.width - s, size.height - s)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(s))
        rotate(-90f) {
            drawArc(
                Brush.sweepGradient(listOf(c.accent, c.accent2, c.accent)),
                0f, 360f * shown, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
fun LinearMeter(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val c = Sharpr.colors
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(300), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(c.surfaceHigh)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(CircleShape).background(c.gradient))
    }
}

/** Five dots, [value] of them lit. */
@Composable
fun Dots(value: Int, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(5) { i ->
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (i < value) color else color.copy(alpha = 0.18f)))
        }
    }
}

@Composable
fun ModelPicker(
    models: List<ModelId>,
    selected: ModelId,
    onSelect: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Sharpr.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        models.forEach { m ->
            val on = m == selected
            val border by animateColorAsState(if (on) c.accent else c.outline.copy(alpha = 0f), tween(200), label = "mb")
            Row(
                Modifier.fillMaxWidth().clip(InnerShape)
                    .background(if (on) c.softGradient else Brush.linearGradient(listOf(c.surfaceHigh, c.surfaceHigh)))
                    .border(BorderStroke(1.5.dp, border), InnerShape)
                    .pressable { onSelect(m) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.label, style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text(m.blurb, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Speed", style = MaterialTheme.typography.labelSmall, color = c.faint)
                        Spacer(Modifier.width(6.dp))
                        Dots(m.speed, c.success)
                        Spacer(Modifier.width(14.dp))
                        Text("Detail", style = MaterialTheme.typography.labelSmall, color = c.faint)
                        Spacer(Modifier.width(6.dp))
                        Dots(m.detail, c.accent)
                    }
                }
                Box(
                    Modifier.padding(start = 12.dp).size(24.dp).clip(CircleShape)
                        .background(if (on) c.gradient else Brush.linearGradient(listOf(c.surfaceHighest, c.surfaceHighest))),
                    contentAlignment = Alignment.Center,
                ) { if (on) Ic(R.drawable.ic_check, tint = c.onAccent, size = 14.dp) }
            }
        }
    }
}

data class NavItem(@DrawableRes val icon: Int, val label: String, val badge: Int = 0)

/** Floating navigation: the selected tab grows into a gradient capsule with its label. */
@Composable
fun NavPill(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Sharpr.colors
    Row(
        modifier
            .shadow(24.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(CircleShape)
            .background(if (c.isDark) c.surfaceHigh.copy(alpha = 0.96f) else c.surface.copy(alpha = 0.97f))
            .border(1.dp, c.outline, CircleShape)
            .padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            val on = i == selected
            Box {
                Row(
                    Modifier.height(50.dp).clip(CircleShape)
                        .background(if (on) c.gradient else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                        .pressable { onSelect(i) }
                        .padding(horizontal = if (on) 18.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ic(item.icon, tint = if (on) c.onAccent else c.muted, size = 22.dp, description = item.label)
                    AnimatedVisibility(on, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                        Text(item.label, style = MaterialTheme.typography.labelLarge, color = c.onAccent, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (item.badge > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 4.dp).widthIn(min = 18.dp).height(18.dp)
                            .clip(CircleShape).background(c.video).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("${item.badge}", style = MaterialTheme.typography.labelSmall, color = Color.White) }
                }
            }
        }
    }
}

@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Sharpr.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(28.dp)).background(c.softGradient), contentAlignment = Alignment.Center) {
            Ic(icon, tint = c.accent, size = 34.dp)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text, modifier = Modifier.padding(top = 18.dp), textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
        if (action != null) Box(Modifier.padding(top = 20.dp)) { action() }
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Sharpr.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(R.drawable.ic_back, onBack, description = "Back")
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** Gradient-filled wordmark text. */
@Composable
fun GradientText(text: String, modifier: Modifier = Modifier) {
    val c = Sharpr.colors
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.displayMedium.copy(brush = Brush.linearGradient(listOf(c.accent, c.accent2))),
    )
}

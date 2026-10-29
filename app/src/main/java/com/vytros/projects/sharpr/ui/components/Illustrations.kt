package com.vytros.projects.sharpr.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import com.vytros.projects.sharpr.ui.theme.Sharpr
import com.vytros.projects.sharpr.ui.theme.SharprColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A tiny scene (sun, hills, sky) rendered at a given resolution; tiny = chunky pixels once scaled up. */
private fun sceneImage(c: SharprColors, w: Int, h: Int): ImageBitmap {
    val sky1 = lerp(c.accent, Color.White, 0.25f)
    val sky2 = lerp(c.accent2, Color.White, 0.1f)
    val sun = Color(0xFFFFC94D)
    val hill1 = lerp(c.accent, Color.Black, 0.25f)
    val hill2 = lerp(c.accent2, Color.Black, 0.45f)
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val v = (y + 0.5f) / h
        for (x in 0 until w) {
            val u = (x + 0.5f) / w
            var col = lerp(sky1, sky2, v)
            val dx = (u - 0.68f) * 1.6f
            val dy = v - 0.36f
            if (sqrt(dx * dx + dy * dy) < 0.15f) col = sun
            if (v > 0.62f + 0.08f * sin(u * 7f)) col = hill1
            if (v > 0.74f + 0.06f * sin(u * 5f + 2f)) col = hill2
            px[y * w + x] = col.toArgb()
        }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
private fun rememberScenes(c: SharprColors): Pair<ImageBitmap, ImageBitmap> =
    remember(c.accent, c.accent2) { sceneImage(c, 320, 200) to sceneImage(c, 16, 10) }

private fun DrawScope.drawScene(img: ImageBitmap, left: Float, top: Float, w: Float, h: Float, smooth: Boolean) {
    drawImage(
        img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
        filterQuality = if (smooth) FilterQuality.High else FilterQuality.None,
    )
}

/** Pixelated on the left, sharp on the right, with a sweeping divider. */
@Composable
fun SharpenHero(modifier: Modifier = Modifier) {
    val c = Sharpr.colors
    val t = rememberInfiniteTransition(label = "hero")
    val sweep by t.animateFloat(0.18f, 0.82f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sweep")
    val (crisp, chunky) = rememberScenes(c)
    Canvas(modifier) {
        val pad = size.minDimension * 0.04f
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val r = CornerRadius(w * 0.08f)
        drawRoundRect(c.surfaceHigh, Offset(pad, pad), Size(w, h), r)
        val dx = pad + w * sweep
        clipRect(pad, pad, dx, pad + h) { drawScene(chunky, pad, pad, w, h, smooth = false) }
        clipRect(dx, pad, pad + w, pad + h) { drawScene(crisp, pad, pad, w, h, smooth = true) }
        drawLine(Color.White, Offset(dx, pad), Offset(dx, pad + h), strokeWidth = 3f)
        drawCircle(Color.White, w * 0.045f, Offset(dx, pad + h / 2))
        drawCircle(c.accent, w * 0.018f, Offset(dx, pad + h / 2))
    }
}

/** Three tilted photo cards with sparkles. */
@Composable
fun PhotoStackArt(modifier: Modifier = Modifier) {
    val c = Sharpr.colors
    val t = rememberInfiniteTransition(label = "stack")
    val float by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "float")
    val (crisp, _) = rememberScenes(c)
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val cw = size.minDimension * 0.52f
        val ch = cw * 1.2f
        listOf(-12f to 0.55f, 10f to 0.75f, 0f to 1f).forEachIndexed { i, (angle, alpha) ->
            val bob = sin((float + i * 0.3f) * 2 * PI).toFloat() * size.minDimension * 0.015f
            rotateAround(angle, Offset(cx, cy)) {
                val left = cx - cw / 2
                val top = cy - ch / 2 + bob
                drawRoundRect(c.surface.copy(alpha = alpha), Offset(left, top), Size(cw, ch), CornerRadius(cw * 0.1f))
                if (i == 2) {
                    val inset = cw * 0.07f
                    clipRect(left + inset, top + inset, left + cw - inset, top + ch * 0.75f) {
                        drawScene(crisp, left + inset, top + inset, cw - 2 * inset, ch * 0.75f - inset, smooth = true)
                    }
                    drawRoundRect(c.surfaceHighest, Offset(left + inset, top + ch * 0.81f), Size(cw * 0.5f, ch * 0.05f), CornerRadius(ch))
                    drawRoundRect(c.surfaceHigh, Offset(left + inset, top + ch * 0.89f), Size(cw * 0.32f, ch * 0.04f), CornerRadius(ch))
                }
            }
        }
        sparkle(Offset(cx + cw * 0.55f, cy - ch * 0.48f), size.minDimension * 0.06f * (0.8f + 0.2f * sin(float * 2 * PI).toFloat()), c.accent2)
        sparkle(Offset(cx - cw * 0.62f, cy + ch * 0.32f), size.minDimension * 0.04f, c.accent)
    }
}

private inline fun DrawScope.rotateAround(degrees: Float, pivot: Offset, block: DrawScope.() -> Unit) {
    drawContext.transform.rotate(degrees, pivot)
    block()
    drawContext.transform.rotate(-degrees, pivot)
}

fun DrawScope.sparkle(center: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(center.x, center.y - r)
        quadraticTo(center.x + r * 0.12f, center.y - r * 0.12f, center.x + r, center.y)
        quadraticTo(center.x + r * 0.12f, center.y + r * 0.12f, center.x, center.y + r)
        quadraticTo(center.x - r * 0.12f, center.y + r * 0.12f, center.x - r, center.y)
        quadraticTo(center.x - r * 0.12f, center.y - r * 0.12f, center.x, center.y - r)
        close()
    }
    drawPath(p, color)
}

/** A film strip sliding past; frames turn crisp as they cross the centre. */
@Composable
fun FilmStripArt(modifier: Modifier = Modifier) {
    val c = Sharpr.colors
    val t = rememberInfiniteTransition(label = "film")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "shift")
    val (crisp, chunky) = rememberScenes(c)
    Canvas(modifier) {
        val h = size.height * 0.46f
        val top = (size.height - h) / 2
        val fw = h * 1.25f
        val gap = fw * 0.12f
        val period = fw + gap
        drawRoundRect(c.surfaceHigh, Offset(-20f, top - h * 0.16f), Size(size.width + 40f, h * 1.32f), CornerRadius(h * 0.08f))
        val holes = (size.width / (h * 0.16f)).toInt() + 2
        for (k in 0 until holes) {
            val x = k * h * 0.16f - (shift * period) % (h * 0.16f)
            drawRoundRect(c.bg, Offset(x, top - h * 0.12f), Size(h * 0.07f, h * 0.06f), CornerRadius(4f))
            drawRoundRect(c.bg, Offset(x, top + h * 1.06f), Size(h * 0.07f, h * 0.06f), CornerRadius(4f))
        }
        val count = (size.width / period).toInt() + 3
        for (k in -1 until count) {
            val x = k * period - shift * period + gap
            val centre = x + fw / 2
            val sharp = abs(centre - size.width / 2) < period / 2
            clipRect(x, top, x + fw, top + h) {
                drawScene(if (sharp) crisp else chunky, x, top, fw, h, smooth = sharp)
            }
        }
        drawRoundRect(Color.White, Offset(size.width / 2 - period / 2 + gap / 2, top - 6f), Size(fw + 4f, h + 12f), CornerRadius(14f), style = Stroke(5f))
    }
}

/** Noisy waveform turning clean from left to right. */
@Composable
fun WaveArt(modifier: Modifier = Modifier) {
    val c = Sharpr.colors
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "phase")
    val sweep by t.animateFloat(0.15f, 0.85f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sweep")
    Canvas(modifier) {
        val bars = 46
        val slot = size.width / bars
        val bw = slot * 0.55f
        val mid = size.height / 2
        for (i in 0 until bars) {
            val u = i / (bars - 1f)
            val envelope = 0.25f + 0.75f * sin((u * 3.2f + phase) * PI).toFloat().let { abs(it) }
            val clean = u < sweep
            val noise = if (clean) 0.04f else (0.18f + 0.22f * abs(sin(i * 12.9898f + phase * 40f)))
            val v = min(1f, envelope * (if (clean) 0.8f else 0.55f) + noise)
            val bh = size.height * 0.84f * v
            val color = if (clean) c.audio else c.muted.copy(alpha = 0.45f)
            drawRoundRect(color, Offset(i * slot + (slot - bw) / 2, mid - bh / 2), Size(bw, bh), CornerRadius(bw / 2))
        }
        val x = size.width * sweep
        drawLine(Brush.verticalGradient(listOf(Color.Transparent, c.accent, Color.Transparent)), Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f, cap = StrokeCap.Round)
    }
}

/** A shield with a check over the phone outline. */
@Composable
fun PrivacyArt(modifier: Modifier = Modifier) {
    val c = Sharpr.colors
    val t = rememberInfiniteTransition(label = "privacy")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "pulse")
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val pw = size.minDimension * 0.42f
        val ph = pw * 1.85f
        for (k in 0..2) {
            val f = (pulse + k / 3f) % 1f
            drawCircle(c.accent.copy(alpha = 0.18f * (1 - f)), size.minDimension * (0.25f + 0.25f * f), Offset(cx, cy))
        }
        drawRoundRect(c.surface, Offset(cx - pw / 2, cy - ph / 2), Size(pw, ph), CornerRadius(pw * 0.16f))
        drawRoundRect(c.outline, Offset(cx - pw / 2, cy - ph / 2), Size(pw, ph), CornerRadius(pw * 0.16f), style = Stroke(4f))
        val s = pw * 0.62f
        val shield = Path().apply {
            moveTo(cx, cy - s * 0.55f)
            lineTo(cx + s * 0.45f, cy - s * 0.35f)
            lineTo(cx + s * 0.45f, cy + s * 0.05f)
            quadraticTo(cx + s * 0.4f, cy + s * 0.45f, cx, cy + s * 0.62f)
            quadraticTo(cx - s * 0.4f, cy + s * 0.45f, cx - s * 0.45f, cy + s * 0.05f)
            lineTo(cx - s * 0.45f, cy - s * 0.35f)
            close()
        }
        drawPath(shield, Brush.linearGradient(listOf(c.accent, c.accent2), Offset(cx - s, cy - s), Offset(cx + s, cy + s)))
        val check = Path().apply {
            moveTo(cx - s * 0.2f, cy + s * 0.02f)
            lineTo(cx - s * 0.04f, cy + s * 0.18f)
            lineTo(cx + s * 0.22f, cy - s * 0.12f)
        }
        drawPath(check, Color.White, style = Stroke(s * 0.08f, cap = StrokeCap.Round))
    }
}

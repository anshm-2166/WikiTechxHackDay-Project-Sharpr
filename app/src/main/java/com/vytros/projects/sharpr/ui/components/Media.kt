package com.vytros.projects.sharpr.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vytros.projects.sharpr.engine.ImageIO
import com.vytros.projects.sharpr.engine.media.MediaProbe
import com.vytros.projects.sharpr.ui.theme.Sharpr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Small memory cache for decoded thumbnails and previews. */
object Bitmaps {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(96 shl 20)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun key(file: File, side: Int) = "${file.absolutePath}@$side@${file.lastModified()}"
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bmp: Bitmap) { cache.put(key, bmp) }
    fun evict(file: File) = cache.snapshot().keys.filter { it.startsWith(file.absolutePath) }.forEach { cache.remove(it) }
}

/** Decodes [file] off the main thread (images, or a frame for videos), cached by size. */
@Composable
fun rememberBitmap(file: File?, maxSide: Int, video: Boolean = false, cache: Boolean = true): ImageBitmap? {
    val key = file?.let { Bitmaps.key(it, maxSide) + if (video) "#v" else "" }
    var bmp by remember(key) { mutableStateOf(key?.let { Bitmaps.get(it) }?.asImageBitmap()) }
    LaunchedEffect(key) {
        if (bmp != null || file == null || key == null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            runCatching { if (video) MediaProbe.frame(file, maxSide = maxSide) else ImageIO.decodeSampled(file, maxSide) }.getOrNull()
        } ?: return@LaunchedEffect
        if (cache) Bitmaps.put(key, decoded)
        bmp = decoded.asImageBitmap()
    }
    return bmp
}

/**
 * Before/after slider: left of the handle is the original, right is the result.
 * Pinch to zoom (both stay aligned), drag to pan, double-tap to magnify, drag the handle to compare.
 */
@Composable
fun CompareSlider(
    before: ImageBitmap,
    after: ImageBitmap,
    modifier: Modifier = Modifier,
    onZoom: (Float) -> Unit = {},
) {
    val c = Sharpr.colors
    var split by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // Gestures move the picture directly; only double-tap animates.
    var animate by remember { mutableStateOf(false) }
    val spec: AnimationSpec<Float> = if (animate) tween(260) else snap()
    val shownZoom by animateFloatAsState(zoom, spec, label = "zoom")
    val shownX by animateFloatAsState(pan.x, spec, label = "panX")
    val shownY by animateFloatAsState(pan.y, spec, label = "panY")

    BoxWithConstraints(modifier.clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val k = min(w / after.width, h / after.height)
        val dw = after.width * k
        val dh = after.height * k
        val dst = Offset((w - dw) / 2, (h - dh) / 2)
        val handleSlop = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.toPx() }

        fun clampPan(p: Offset, z: Float) = Offset(p.x.coerceIn(w * (1 - z), 0f), p.y.coerceIn(h * (1 - z), 0f))

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(w, h) {
                    detectTapGestures(onDoubleTap = { o ->
                        val nz = if (zoom > 1.05f) 1f else 2.5f
                        animate = true
                        pan = clampPan(o - (o - pan) * (nz / zoom), nz)
                        zoom = nz
                        onZoom(nz)
                    })
                }
                .pointerInput(w, h) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        animate = false
                        val onHandle = abs(down.position.x - split * w) < handleSlop
                        do {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.count { it.pressed }
                            if (onHandle && pressed == 1) {
                                ev.changes.firstOrNull { it.pressed }?.let { ch ->
                                    split = (ch.position.x / w).coerceIn(0.02f, 0.98f)
                                    ch.consume()
                                }
                            } else if (pressed >= 2 || zoom > 1.01f) {
                                val zc = ev.calculateZoom()
                                val pc = ev.calculatePan()
                                val centroid = ev.calculateCentroid(useCurrent = true)
                                val nz = (zoom * zc).coerceIn(1f, 10f)
                                var np = if (centroid.isSpecified()) centroid - (centroid - pan) * (nz / zoom) else pan
                                np += pc
                                zoom = nz
                                pan = clampPan(np, nz)
                                onZoom(nz)
                                ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                },
        ) {
            val z = shownZoom
            val p = Offset(shownX, shownY)
            val dx = split * w
            fun drawFrame(img: ImageBitmap) {
                translate(p.x, p.y) {
                    scale(z, z, pivot = Offset.Zero) {
                        drawImage(
                            img,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(img.width, img.height),
                            dstOffset = IntOffset(dst.x.toInt(), dst.y.toInt()),
                            dstSize = IntSize(dw.toInt(), dh.toInt()),
                            filterQuality = if (z > 1.5f) FilterQuality.None else FilterQuality.High,
                        )
                    }
                }
            }
            drawFrame(before)
            clipRect(left = dx) { drawFrame(after) }

            // Divider + handle
            drawLine(Color.White, Offset(dx, 0f), Offset(dx, h), strokeWidth = 2.dp.toPx())
            val r = 22.dp.toPx()
            val cy = h / 2
            drawCircle(Color.Black.copy(alpha = 0.25f), r + 2.dp.toPx(), Offset(dx, cy + 2.dp.toPx()))
            drawCircle(Color.White, r, Offset(dx, cy))
            val s = 5.dp.toPx()
            val arrows = Path().apply {
                moveTo(dx - 4.dp.toPx(), cy - s); lineTo(dx - 10.dp.toPx(), cy); lineTo(dx - 4.dp.toPx(), cy + s)
                moveTo(dx + 4.dp.toPx(), cy - s); lineTo(dx + 10.dp.toPx(), cy); lineTo(dx + 4.dp.toPx(), cy + s)
            }
            drawPath(arrows, c.accent, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (split * w > 90) CompareLabel("BEFORE", Modifier.align(Alignment.TopStart).padding(14.dp))
        if (w - split * w > 90) CompareLabel("AFTER", Modifier.align(Alignment.TopEnd).padding(14.dp))
    }
}

private fun Offset.isSpecified() = x.isFinite() && y.isFinite()

@Composable
private fun CompareLabel(text: String, modifier: Modifier) {
    Text(
        text,
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 11.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
    )
}

/** Peak bars; the part before [progress] is drawn in [played]. */
@Composable
fun Waveform(
    peaks: FloatArray?,
    modifier: Modifier = Modifier,
    color: Color = Sharpr.colors.muted.copy(alpha = 0.45f),
    played: Color = Sharpr.colors.accent,
    progress: Float = 0f,
    bars: Int = 72,
    onSeek: ((Float) -> Unit)? = null,
) {
    val m = if (onSeek != null) modifier.pointerInput(Unit) { detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) } } else modifier
    Canvas(m) {
        val data = peaks ?: FloatArray(bars) { 0.08f }
        val n = bars.coerceAtLeast(1)
        val slot = size.width / n
        val bw = (slot * 0.56f).coerceAtLeast(1.5f)
        for (i in 0 until n) {
            val from = i * data.size / n
            val to = ((i + 1) * data.size / n).coerceAtLeast(from + 1).coerceAtMost(data.size)
            var v = 0f
            for (j in from until to) v = maxOf(v, data[j])
            val bh = (size.height * (0.08f + 0.92f * v)).coerceAtMost(size.height)
            val x = i * slot + (slot - bw) / 2
            drawRoundRect(
                if ((i + 0.5f) / n <= progress) played else color,
                topLeft = Offset(x, (size.height - bh) / 2),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bw / 2, bw / 2),
            )
        }
    }
}

/** Placeholder while a preview decodes. */
@Composable
fun ImagePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(Sharpr.colors.surfaceHigh))
}

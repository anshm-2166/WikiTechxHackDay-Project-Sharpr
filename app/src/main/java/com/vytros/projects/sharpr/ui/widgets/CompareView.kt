package com.vytros.projects.sharpr.ui.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.vytros.projects.sharpr.R
import kotlin.math.abs
import kotlin.math.min

/**
 * Before/after slider. Left of the divider shows the original, right shows the upscaled result.
 * Pinch to zoom (both images stay aligned), drag to pan, double-tap to toggle 2.5x, drag the handle to compare.
 */
class CompareView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var before: Bitmap? = null
    private var after: Bitmap? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var split = 0.5f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var draggingHandle = false
    private val base = RectF()

    var onZoomChanged: ((Float) -> Unit)? = null

    fun setImages(beforeBmp: Bitmap?, afterBmp: Bitmap?) {
        before = beforeBmp
        after = afterBmp
        zoom = 1f; panX = 0f; panY = 0f
        computeBase()
        invalidate()
    }

    fun resetZoom() {
        zoom = 1f; panX = 0f; panY = 0f
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    private fun computeBase() {
        val a = after ?: return
        if (width == 0 || height == 0) return
        val k = min(width.toFloat() / a.width, height.toFloat() / a.height)
        val w = a.width * k
        val h = a.height * k
        base.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        computeBase()
    }

    private fun clampPan() {
        panX = panX.coerceIn(width * (1 - zoom), 0f)
        panY = panY.coerceIn(height * (1 - zoom), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val a = after ?: return
        val b = before ?: a
        val dividerX = split * width

        // Original (left side), drawn full, result is clipped over it.
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(zoom, zoom)
        canvas.drawBitmap(b, null, base, bitmapPaint)
        canvas.restore()

        canvas.save()
        canvas.clipRect(dividerX, 0f, width.toFloat(), height.toFloat())
        canvas.translate(panX, panY)
        canvas.scale(zoom, zoom)
        canvas.drawBitmap(a, null, base, bitmapPaint)
        canvas.restore()

        // Divider + handle
        linePaint.strokeWidth = dp(2f)
        canvas.drawLine(dividerX, 0f, dividerX, height.toFloat(), linePaint)
        val cy = height / 2f
        val r = dp(22f)
        canvas.drawCircle(dividerX, cy + dp(2f), r, shadowPaint)
        canvas.drawCircle(dividerX, cy, r, linePaint)
        arrowPaint.color = context.themeColor(R.attr.sPrimaryDeep)
        arrowPaint.strokeWidth = dp(2.2f)
        val s = dp(5f)
        val p = Path().apply {
            moveTo(dividerX - dp(4f), cy - s); lineTo(dividerX - dp(10f), cy); lineTo(dividerX - dp(4f), cy + s)
            moveTo(dividerX + dp(4f), cy - s); lineTo(dividerX + dp(10f), cy); lineTo(dividerX + dp(4f), cy + s)
        }
        canvas.drawPath(p, arrowPaint)

        drawLabel(canvas, "BEFORE", dividerX / 2f, dividerX > dp(90f))
        drawLabel(canvas, "AFTER", (dividerX + width) / 2f, width - dividerX > dp(90f))
    }

    private fun drawLabel(canvas: Canvas, text: String, cx: Float, show: Boolean) {
        if (!show) return
        labelText.textSize = dp(11.5f)
        val w = labelText.measureText(text) + dp(22f)
        val h = dp(26f)
        val top = dp(14f)
        canvas.drawRoundRect(cx - w / 2, top, cx + w / 2, top + h, h / 2, h / 2, labelBg)
        canvas.drawText(text, cx, top + h / 2 + dp(4f), labelText)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newZoom = (zoom * d.scaleFactor).coerceIn(1f, 8f)
            val k = newZoom / zoom
            panX = d.focusX - (d.focusX - panX) * k
            panY = d.focusY - (d.focusY - panY) * k
            zoom = newZoom
            clampPan()
            onZoomChanged?.invoke(zoom)
            invalidate()
            return true
        }
    })

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.01f) {
                resetZoom()
            } else {
                val target = 2.5f
                panX = e.x - (e.x - panX) * target
                panY = e.y - (e.y - panY) * target
                zoom = target
                clampPan()
                onZoomChanged?.invoke(zoom)
                invalidate()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (draggingHandle || scaleDetector.isInProgress || zoom <= 1.01f) return false
            panX -= dx; panY -= dy
            clampPan()
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> draggingHandle = abs(e.x - split * width) < dp(36f)
            MotionEvent.ACTION_POINTER_DOWN -> draggingHandle = false
        }
        if (draggingHandle) {
            if (e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_DOWN) {
                split = (e.x / width).coerceIn(0.02f, 0.98f)
                invalidate()
            }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) draggingHandle = false
            return true
        }
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

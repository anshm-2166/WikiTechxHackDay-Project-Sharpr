package com.vytros.projects.sharpr.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.vytros.projects.sharpr.R

/** Circular progress with an accent-gradient sweep. */
class RingProgressView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private var shown = 0f
    private var animator: ValueAnimator? = null

    /** 0..1 */
    var progress: Float = 0f
        set(v) {
            val target = v.coerceIn(0f, 1f)
            field = target
            animator?.cancel()
            animator = ValueAnimator.ofFloat(shown, target).apply {
                duration = 280
                interpolator = DecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val stroke = dp(10f)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        track.color = context.themeColor(R.attr.sOutline)
        val inset = stroke / 2 + dp(2f)
        rect.set(inset, inset, w - inset, h - inset)
        arc.shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(context.themeColor(R.attr.sPrimary), context.themeColor(R.attr.sGradEnd), context.themeColor(R.attr.sPrimary)),
            floatArrayOf(0f, 0.75f, 1f),
        ).also { it.setLocalMatrix(android.graphics.Matrix().apply { postRotate(-90f, w / 2f, h / 2f) }) }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawArc(rect, 0f, 360f, false, track)
        if (shown > 0.003f) canvas.drawArc(rect, -90f, 360f * shown, false, arc)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}

package com.vytros.projects.sharpr.ui.widgets

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import com.vytros.projects.sharpr.R
import kotlin.math.max

fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun View.dp(v: Float): Float = context.dp(v)
fun View.dpi(v: Int): Int = context.dpi(v)

/** Pill-shaped multiple-choice control. Items can be individually disabled. */
class SegmentedControl @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    var onSelect: ((Int) -> Unit)? = null
    var selectedIndex = 0
        set(value) {
            field = value
            refresh()
        }
    private val labels = ArrayList<TextView>()
    private var enabledMask: Set<Int>? = null

    init {
        orientation = HORIZONTAL
        setBackgroundResource(R.drawable.bg_segment_container)
        setPadding(dpi(4), dpi(4), dpi(4), dpi(4))
        attrs?.let {
            val a = ctx.obtainStyledAttributes(it, R.styleable.SegmentedControl)
            val id = a.getResourceId(R.styleable.SegmentedControl_entries, 0)
            a.recycle()
            if (id != 0) setItems(resources.getStringArray(id).toList())
        }
    }

    fun setItems(items: List<String>, selected: Int = selectedIndex) {
        removeAllViews()
        labels.clear()
        items.forEachIndexed { i, text ->
            val tv = TextView(context).apply {
                this.text = text
                gravity = Gravity.CENTER
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                setPadding(dpi(6), 0, dpi(6), 0)
                layoutParams = LayoutParams(0, dpi(38), 1f)
                setOnClickListener { if (enabledMask?.contains(i) != false) select(i, true) }
            }
            labels += tv
            addView(tv)
        }
        selectedIndex = selected.coerceIn(0, max(0, items.size - 1))
    }

    /** Null = everything enabled. */
    fun setEnabledItems(indices: Set<Int>?) {
        enabledMask = indices
        refresh()
    }

    private fun select(i: Int, fromUser: Boolean) {
        if (i == selectedIndex && fromUser) return
        selectedIndex = i
        if (fromUser) onSelect?.invoke(i)
    }

    private fun refresh() {
        val muted = context.themeColor(R.attr.sMuted)
        val onPrimary = context.themeColor(R.attr.sOnPrimary)
        labels.forEachIndexed { i, tv ->
            val on = i == selectedIndex
            tv.setBackgroundResource(if (on) R.drawable.bg_segment_selected else 0)
            tv.setTextColor(if (on) onPrimary else muted)
            tv.alpha = if (enabledMask?.contains(i) == false) 0.35f else 1f
        }
    }
}

/** "Label ........ 42%" over a gradient seek bar. */
class LabeledSlider @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    var onChange: ((Int) -> Unit)? = null
    private val seek: SeekBar
    private val valueText: TextView
    private var suffix = "%"
    private var silent = false

    var value: Int
        get() = seek.progress
        set(v) {
            silent = true
            seek.progress = v
            silent = false
            valueText.text = "$v$suffix"
        }

    init {
        orientation = VERTICAL
        LayoutInflater.from(ctx).inflate(R.layout.view_labeled_slider, this, true)
        seek = findViewById(R.id.seek)
        valueText = findViewById(R.id.value)
        val label = findViewById<TextView>(R.id.label)
        attrs?.let {
            val a = ctx.obtainStyledAttributes(it, R.styleable.LabeledSlider)
            label.text = a.getString(R.styleable.LabeledSlider_label)
            seek.max = a.getInt(R.styleable.LabeledSlider_max, 100)
            a.getString(R.styleable.LabeledSlider_suffix)?.let { s -> suffix = s }
            a.recycle()
        }
        valueText.text = "${seek.progress}$suffix"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                valueText.text = "$p$suffix"
                if (!silent) onChange?.invoke(p)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
    }
}

/** Title + subtitle with a switch on the right. */
class SwitchRow @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    var onChange: ((Boolean) -> Unit)? = null
    private val sw: SwitchCompat
    private var silent = false

    var checked: Boolean
        get() = sw.isChecked
        set(v) {
            silent = true
            sw.isChecked = v
            silent = false
        }

    init {
        LayoutInflater.from(ctx).inflate(R.layout.view_switch_row, this, true)
        sw = findViewById(R.id.sw)
        attrs?.let {
            val a = ctx.obtainStyledAttributes(it, R.styleable.SwitchRow)
            findViewById<TextView>(R.id.title).text = a.getString(R.styleable.SwitchRow_title)
            val sub = a.getString(R.styleable.SwitchRow_subtitle)
            findViewById<TextView>(R.id.subtitle).apply {
                text = sub
                visibility = if (sub.isNullOrEmpty()) GONE else VISIBLE
            }
            a.recycle()
        }
        sw.setOnCheckedChangeListener { _, c -> if (!silent) onChange?.invoke(c) }
        setOnClickListener { sw.toggle() }
    }
}

/** Wraps children onto new lines. */
class FlowLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ViewGroup(ctx, attrs) {
    var spacing = dpi(8)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0; y += rowH + spacing; rowH = 0
            }
            x += c.measuredWidth + spacing
            rowH = max(rowH, c.measuredHeight)
            widest = max(widest, x - spacing)
        }
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, wSpec),
            resolveSize(y + rowH + paddingTop + paddingBottom, hSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0; y += rowH + spacing; rowH = 0
            }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + spacing
            rowH = max(rowH, c.measuredHeight)
        }
    }
}

/** Text filled with the accent gradient. */
class GradientTextView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : AppCompatTextView(ctx, attrs) {
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = paint.measureText(text.toString()).coerceAtLeast(1f)
        paint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(context.themeColor(R.attr.sPrimary), context.themeColor(R.attr.sGradEnd)),
            null, Shader.TileMode.CLAMP,
        )
    }
}

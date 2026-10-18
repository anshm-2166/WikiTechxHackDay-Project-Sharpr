package com.vytros.projects.sharpr.ui.widgets

import android.animation.LayoutTransition
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vytros.projects.sharpr.R

data class NavSpec(val icon: Int, val label: String)

/** Floating pill navigation: the selected tab expands into a gradient capsule with its label. */
class NavPill @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    var onSelect: ((Int) -> Unit)? = null
    var selectedIndex = 0
        private set
    private class Item(val root: LinearLayout, val icon: ImageView, val label: TextView)
    private val items = ArrayList<Item>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_nav_pill)
        setPadding(dpi(8), dpi(8), dpi(8), dpi(8))
        elevation = dp(10f)
        layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
    }

    fun setItems(specs: List<NavSpec>, selected: Int = 0) {
        removeAllViews()
        items.clear()
        specs.forEachIndexed { i, spec ->
            val icon = ImageView(context).apply {
                setImageResource(spec.icon)
                layoutParams = LayoutParams(dpi(24), dpi(24))
            }
            val label = TextView(context).apply {
                text = spec.label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dpi(8), 0, 0, 0)
            }
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                minimumHeight = dpi(48)
                addView(icon)
                addView(label)
                contentDescription = spec.label
                setOnClickListener { select(i, true) }
            }
            addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            items += Item(row, icon, label)
        }
        select(selected, false)
    }

    fun select(i: Int, notify: Boolean) {
        selectedIndex = i
        val muted = context.themeColor(R.attr.sMuted)
        items.forEachIndexed { idx, it ->
            val on = idx == i
            it.label.visibility = if (on) View.VISIBLE else View.GONE
            it.icon.setColorFilter(if (on) 0xFFFFFFFF.toInt() else muted)
            it.root.setBackgroundResource(if (on) R.drawable.bg_nav_selected else R.drawable.bg_ripple_plain)
            it.root.setPadding(dpi(if (on) 20 else 18), 0, dpi(if (on) 22 else 18), 0)
        }
        if (notify) onSelect?.invoke(i)
    }
}

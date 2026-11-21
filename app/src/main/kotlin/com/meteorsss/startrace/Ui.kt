package com.meteorsss.startrace

import android.R
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class Ui(val context: Context, val light: Boolean) {
    val bg: Int
    val card: Int
    val border: Int
    val text: Int
    val muted: Int
    val accent: Int
    val warning: Int

    init {
        bg = Color.parseColor(if (light) "#F3F6FB" else "#0C1421")
        card = Color.parseColor(if (light) "#FFFFFF" else "#152236")
        border = Color.parseColor(if (light) "#DAE3EF" else "#2A3A51")
        text = Color.parseColor(if (light) "#152B40" else "#EDF6FF")
        muted = Color.parseColor(if (light) "#566E85" else "#9AADC4")
        accent = Color.parseColor(if (light) "#007B77" else "#68DED3")
        warning = Color.parseColor(if (light) "#9B5B0A" else "#F1BB6B")
    }

    fun dp(n: Float): Int {
        return Math.round(n * context.getResources().getDisplayMetrics().density)
    }

    fun text(value: String?, size: Int, color: Int): TextView {
        val t = TextView(context)
        t.setText(value)
        t.setTextSize(size.toFloat())
        t.setTextColor(color)
        t.setFontFeatureSettings("tnum")
        t.setLineSpacing(dp(3f).toFloat(), 1f)
        t.setIncludeFontPadding(false)
        return t
    }

    fun title(value: String?, size: Int): TextView {
        val t = text(value, size, text)
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        return t
    }

    fun column(): LinearLayout {
        val v = LinearLayout(context)
        v.setOrientation(LinearLayout.VERTICAL)
        return v
    }

    fun row(): LinearLayout {
        val v = LinearLayout(context)
        v.setOrientation(LinearLayout.HORIZONTAL)
        v.setGravity(Gravity.CENTER_VERTICAL)
        return v
    }

    fun shape(color: Int, radius: Int, stroke: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.setCornerRadius(dp(radius.toFloat()).toFloat())
        if (stroke) d.setStroke(dp(1f), border)
        return d
    }

    fun panel(): LinearLayout {
        val p = column()
        p.setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        p.setBackground(shape(card, 22, true))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(12f)
        p.setLayoutParams(lp)
        return p
    }

    fun gap(p: LinearLayout, size: Int) {
        val v: View = View(context)
        p.addView(v, LinearLayout.LayoutParams(1, dp(size.toFloat())))
    }

    fun button(label: String?, primary: Boolean, action: Runnable?): TextView {
        val b = title(label, 14)
        b.setGravity(Gravity.CENTER)
        b.setMinHeight(dp(48f))
        b.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        b.setTextColor(if (primary) (if (light) Color.WHITE else bg) else text)
        val state = StateListDrawable()
        state.addState(
            intArrayOf(R.attr.state_pressed), shape(if (primary) muted else border, 15, false)
        )
        state.addState(intArrayOf(), shape(if (primary) accent else card, 15, !primary))
        b.setBackground(state)
        b.setOnClickListener(View.OnClickListener { v: View? -> action!!.run() })
        b.setFocusable(true)
        b.setContentDescription(label)
        return b
    }

    fun pair(parent: LinearLayout, a: View?, b: View?) {
        val row = row()
        val left: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        left!!.rightMargin = dp(6f)
        val right = LinearLayout.LayoutParams(0, -2, 1f)
        right.leftMargin = dp(6f)
        row.addView(a, left)
        row.addView(b, right)
        parent.addView(row)
    }

    fun section(parent: LinearLayout, eyebrow: String?, title: String?) {
        val e = text(eyebrow, 11, accent)
        e.setLetterSpacing(.12f)
        parent.addView(e)
        gap(parent, 9)
        parent.addView(title(title, 25))
        gap(parent, 18)
    }

    fun note(p: LinearLayout, text: String?): TextView {
        val t = text(text, 12, muted)
        p.addView(t)
        return t
    }

    fun constellation(system: Int): Int {
        val colors: IntArray? = intArrayOf(
            muted,
            Color.rgb(104, 222, 211),
            Color.rgb(197, 167, 255),
            Color.rgb(255, 179, 128),
            Color.rgb(240, 209, 113),
            Color.rgb(135, 177, 255),
            Color.rgb(242, 137, 157),
            Color.rgb(175, 214, 144)
        )
        return colors!![max(0, min(system, colors.size - 1))]
    }
}

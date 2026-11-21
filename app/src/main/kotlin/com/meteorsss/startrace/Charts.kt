package com.meteorsss.startrace

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.meteorsss.startrace.GnssEngine.Satellite
import com.meteorsss.startrace.Store.Place
import java.util.*
import java.util.function.ToDoubleFunction
import java.util.function.ToIntFunction
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Charts(
    private val a: MainActivity,
    private val kind: String,
    private val track: Long,
    private val destination: Place?
) : View(a) {
    private val u: Ui
    private val p: Paint? = Paint(Paint.ANTI_ALIAS_FLAG)
    private var loaded: Long = 0
    private var points: MutableList<TrackPoint>? = mutableListOf<TrackPoint>()

    init {
        u = a.u
        // Keep hardware acceleration enabled.
        setContentDescription(
            if (kind == "sky")
                "卫星天空图"
            else
                if (kind == "signal") "卫星信号强度图" else if (kind == "track") "无底图轨迹预览" else "指南针"
        )
    }

    fun refreshData() {
        if (kind == "signal" && getLayoutParams() != null) {
            val height = u.dp(max(64, a.e.currentSatellites().size * 34 + 14).toFloat())
            if (getLayoutParams().height != height) {
                getLayoutParams().height = height
                requestLayout()
            }
        }
        invalidate()
    }

    private fun line(c: Canvas, x: Float, y: Float, x2: Float, y2: Float, color: Int, width: Float) {
        p!!.setColor(color)
        p.setStyle(Paint.Style.STROKE)
        p.setStrokeWidth(u.dp(width).toFloat())
        c.drawLine(x, y, x2, y2, p)
        p.setStyle(Paint.Style.FILL)
    }

    private fun label(
        c: Canvas, text: String?, x: Float, y: Float, color: Int, size: Int, align: Align?
    ) {
        p!!.setStyle(Paint.Style.FILL)
        p.setTextSize(u.dp(size.toFloat()).toFloat())
        p.setColor(color)
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        p.setTextAlign(align)
        c.drawText(text!!, x, y, p)
    }

    private fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int, fill: Boolean) {
        p!!.setStyle(if (fill) Paint.Style.FILL else Paint.Style.STROKE)
        p.setColor(color)
        p.setStrokeWidth(u.dp(1f).toFloat())
        c.drawCircle(x, y, r, p)
        p.setStyle(Paint.Style.FILL)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        when (kind) {
            "sky" -> sky(c)
            "signal" -> signals(c)
            "track" -> track(c)
            else -> {}
        }
    }

    private fun sky(c: Canvas) {
        val cx = getWidth() / 2f
        val cy = getHeight() / 2f
        val r = min(cx, cy) - u.dp(24f)
        for (i in 1..3) circle(c, cx, cy, r * i / 3, u.border, false)
        line(c, cx - r, cy, cx + r, cy, u.border, 1f)
        line(c, cx, cy - r, cx, cy + r, u.border, 1f)
        label(c, "N", cx, cy - r - u.dp(8f), u.accent, 13, Align.CENTER)
        label(c, "S", cx, cy + r + u.dp(17f), u.muted, 12, Align.CENTER)
        label(c, "W", cx - r - u.dp(12f), cy + u.dp(4f), u.muted, 12, Align.CENTER)
        label(c, "E", cx + r + u.dp(12f), cy + u.dp(4f), u.muted, 12, Align.CENTER)
        val drawn: MutableSet<String?> = HashSet<String?>()
        for (s in a.e.currentSatellites()) {
            if (!drawn.add(s.key())) continue
            val rr = r * (1 - s.elevation / 90f)
            val x = cx + sin(Math.toRadians(s.azimuth.toDouble())).toFloat() * rr
            val y = cy - cos(Math.toRadians(s.azimuth.toDouble())).toFloat() * rr
            val color = u.constellation(s.system)
            var used = s.used
            for (other in a.e.currentSatellites()) if (other.key() == s.key() && other.used) used = true
            circle(c, x, y, u.dp(7f).toFloat(), color, used)
            label(c, "" + s.id, x, y - u.dp(11f), color, 10, Align.CENTER)
        }
        if (drawn.isEmpty()) label(c, "等待卫星数据", cx, cy - u.dp(8f), u.muted, 14, Align.CENTER)
    }

    private fun signals(c: Canvas) {
        val list: MutableList<Satellite>? = ArrayList<Satellite>(a.e.currentSatellites())
        if (a.groupSignals()) list!!.sort(
            Comparator.comparingInt<Satellite?>(ToIntFunction { s: Satellite? -> s!!.system })
                .thenComparingInt(ToIntFunction { s: Satellite? -> s!!.id })
                .thenComparingDouble(ToDoubleFunction { s: Satellite? -> if (s!!.hasFrequency) s.frequency else 0f })
        )
        val n = list!!.size
        if (n == 0) {
            label(c, "暂无信号记录", getWidth() / 2f, u.dp(35f).toFloat(), u.muted, 14, Align.CENTER)
            return
        }
        val left = u.dp(110f).toFloat()
        val right = (getWidth() - u.dp(40f)).toFloat()
        for (i in 0..<n) {
            val s: Satellite? = list.get(i)
            val y = u.dp((17 + i * 34).toFloat()).toFloat()
            label(
                c,
                (if (s!!.used) "✓ " else "— ") + s.label(),
                0f,
                y,
                if (s.used) u.accent else u.text,
                11,
                Align.LEFT
            )
            val freq: String? =
                if (s.hasFrequency) String.format(Locale.US, "%.2f MHz", s.frequency / 1e6) else "频率未提供"
            label(c, freq, 0f, y + u.dp(12f), u.muted, 9, Align.LEFT)
            line(c, left, y - 2, right, y - 2, u.border, 5f)
            line(
                c,
                left,
                y - 2,
                left
                        + (right - left) * (if (java.lang.Float.isFinite(s.cn0)) max(
                    0f,
                    min(1f, s.cn0 / 60)
                ) else 0f),
                y - 2,
                u.constellation(s.system),
                5f
            )
            label(
                c,
                String.format(Locale.US, "%.0f", s.cn0),
                getWidth().toFloat(),
                y + 2,
                if (s.used) u.accent else u.muted,
                12,
                Align.RIGHT
            )
        }
    }

    private fun track(c: Canvas) {
        val now = SystemClock.elapsedRealtime()
        if (now - loaded > 3000 || loaded == 0L) {
            points = a.app.store.preview(track, 1200)
            loaded = now
        }
        for (i in 1..4) {
            line(c, getWidth() * i / 5f, 0f, getWidth() * i / 5f, getHeight().toFloat(), u.border, .5f)
            line(c, 0f, getHeight() * i / 5f, getWidth().toFloat(), getHeight() * i / 5f, u.border, .5f)
        }
        label(c, "N ↑", u.dp(10f).toFloat(), u.dp(20f).toFloat(), u.muted, 11, Align.LEFT)
        if (points!!.isEmpty()) {
            label(c, "等待有效轨迹点", getWidth() / 2f, getHeight() / 2f, u.muted, 14, Align.CENTER)
            return
        }
        val origin = points!!.get(0)
        val cos = cos(Math.toRadians(origin.lat))
        var xmin = 0.0
        var xmax = 0.0
        var ymin = 0.0
        var ymax = 0.0
        val xs = DoubleArray(points!!.size)
        val ys = DoubleArray(points!!.size)
        for (i in points!!.indices) {
            val v = points!!.get(i)
            val dl = ((v.lon - origin.lon + 540) % 360) - 180
            xs[i] = dl * cos * 111195
            ys[i] = -(v.lat - origin.lat) * 111195
            xmin = min(xmin, xs[i])
            xmax = max(xmax, xs[i])
            ymin = min(ymin, ys[i])
            ymax = max(ymax, ys[i])
        }
        val scale = min(
            (getWidth() - u.dp(52f)) / max(20.0, xmax - xmin),
            (getHeight() - u.dp(64f)) / max(20.0, ymax - ymin)
        )
        var px = 0f
        var py = 0f
        var seg = -1
        for (i in points!!.indices) {
            val x = (getWidth() / 2.0 + (xs[i] - (xmin + xmax) / 2) * scale).toFloat()
            val y = (getHeight() / 2.0 + (ys[i] - (ymin + ymax) / 2) * scale).toFloat()
            if (seg == points!!.get(i).segment) line(c, px, py, x, y, u.accent, 2.5f)
            if (i == 0) circle(c, x, y, u.dp(5f).toFloat(), u.warning, true)
            if (i == points!!.size - 1) circle(c, x, y, u.dp(6f).toFloat(), u.accent, true)
            px = x
            py = y
            seg = points!!.get(i).segment
        }
        label(
            c,
            "相对轨迹 · 非道路地图",
            getWidth() / 2f,
            (getHeight() - u.dp(10f)).toFloat(),
            u.muted,
            10,
            Align.CENTER
        )
    }
}

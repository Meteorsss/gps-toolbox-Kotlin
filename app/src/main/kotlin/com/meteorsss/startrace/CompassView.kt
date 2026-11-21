package com.meteorsss.startrace

import android.graphics.*
import android.graphics.Paint.Align
import android.os.SystemClock
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import android.view.View
import com.meteorsss.startrace.Store.Place
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A vsync-driven compass. Only the dial/needle redraw, never the containing page.  */
class CompassView(private val activity: MainActivity, private val destination: Place?) : View(activity), FrameCallback {
    private val ui: Ui
    private val paint: Paint? = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val typeface: Typeface? = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val arrow = Path()
    private val smoothing = HeadingSmoother()
    private var dial: Bitmap? = null
    private var active = false
    private var attached = false
    private var scheduled = false
    private var locationFresh = false
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var displayHeading = Float.NaN
    private var targetBearing = 0f
    private var declination = 0f
    private var labelHeading = -1
    private var headingText = ""
    private var frames: Long = 0

    init {
        this.ui = activity.u
        setContentDescription(if (destination == null) "平滑指南针，磁北基准" else "目标方向指南针，真北基准")
        // Default hardware acceleration; no forced software layer.
    }

    fun setActive(active: Boolean) {
        this.active = active
        if (!active) {
            cancelFrame()
            smoothing.reset()
        } else scheduleFrame()
    }

    private fun scheduleFrame() {
        if (active && attached && getWindowVisibility() == VISIBLE && !scheduled) {
            scheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun cancelFrame() {
        if (scheduled) {
            Choreographer.getInstance().removeFrameCallback(this)
            scheduled = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        attached = false
        cancelFrame()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) scheduleFrame()
        else {
            cancelFrame()
            smoothing.reset()
        }
    }

    fun updateSlowState() {
        locationFresh = activity.e.fresh()
        declination = activity.e.declination()
        if (destination != null && locationFresh) targetBearing = Geo.bearing(
            activity.e.location.getLatitude(),
            activity.e.location.getLongitude(),
            destination.lat,
            destination.lon
        ).toFloat()
    }

    override fun doFrame(frameNanos: Long) {
        scheduled = false
        if (!active || !attached || getWindowVisibility() != VISIBLE) return
        var target = activity.e.magneticHeading
        if (activity.e.orientationAt == 0L
            || SystemClock.elapsedRealtime() - activity.e.orientationAt > 2000
        ) target = Float.NaN
        if (destination != null) target =
            if (locationFresh) Geo.normalize((target + declination).toDouble()).toFloat() else Float.NaN
        displayHeading = smoothing.update(target.toDouble(), frameNanos).toFloat()
        val next = if (java.lang.Float.isNaN(displayHeading)) -1 else Math.round(displayHeading) % 360
        if (next != labelHeading) {
            labelHeading = next
            headingText = if (next < 0) "" else next.toString() + "°"
        }
        invalidate()
        scheduleFrame()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        centerX = w / 2f
        centerY = h / 2f
        radius = min(centerX, centerY) - ui.dp(30f)
        // Allocate/cache dial only on layout changes; no bitmap or path allocation in each frame.
        dial = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dial!!)
        paint!!.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(ui.dp(1f).toFloat())
        paint.setColor(ui.border)
        canvas.drawCircle(centerX, centerY, radius, paint)
        var deg = 0
        while (deg < 360) {
            val angle = Math.toRadians(deg.toDouble())
            val inner = radius - ui.dp((if (deg % 30 == 0) 15 else 7).toFloat())
            paint.setColor(if (deg == 0) ui.accent else ui.border)
            paint.setStrokeWidth(ui.dp((if (deg % 30 == 0) 2 else 1).toFloat()).toFloat())
            canvas.drawLine(
                centerX + sin(angle).toFloat() * inner,
                centerY - cos(angle).toFloat() * inner,
                centerX + sin(angle).toFloat() * radius,
                centerY - cos(angle).toFloat() * radius,
                paint
            )
            deg += 5
        }
        val labels: Array<String?>? = arrayOf<String?>("N", "E", "S", "W")
        for (i in 0..3) {
            val angle = Math.toRadians((i * 90).toDouble())
            text(
                canvas,
                labels!![i]!!,
                centerX + sin(angle).toFloat() * (radius - ui.dp(33f)),
                centerY - cos(angle).toFloat() * (radius - ui.dp(33f)) + ui.dp(5f),
                if (i == 0) ui.accent else ui.text,
                16
            )
        }
        arrow.reset()
        arrow.moveTo(centerX, centerY - radius * .48f)
        arrow.lineTo(centerX - ui.dp(14f), centerY + ui.dp(22f))
        arrow.lineTo(centerX, centerY + ui.dp(12f))
        arrow.lineTo(centerX + ui.dp(14f), centerY + ui.dp(22f))
        arrow.close()
        // Avoid recycling a bitmap that may still be referenced by a previous hardware display list.
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, color: Int, size: Int) {
        paint!!.setStyle(Paint.Style.FILL)
        paint.setTypeface(typeface)
        paint.setTextSize(ui.dp(size.toFloat()).toFloat())
        paint.setTextAlign(Align.CENTER)
        paint.setColor(color)
        c.drawText(value, x, y, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frames++
        if (dial == null) return
        canvas.save()
        if (!java.lang.Float.isNaN(displayHeading)) canvas.rotate(-displayHeading, centerX, centerY)
        paint!!.setColor(Color.WHITE)
        paint.setStyle(Paint.Style.FILL)
        canvas.drawBitmap(dial!!, 0f, 0f, paint)
        canvas.restore()
        if (java.lang.Float.isNaN(displayHeading)) {
            text(
                canvas,
                if (destination != null && !locationFresh) "等待有效位置与方向" else "等待方向数据",
                centerX,
                centerY,
                ui.muted,
                14
            )
            return
        }
        canvas.save()
        if (destination != null) canvas.rotate(targetBearing - displayHeading, centerX, centerY)
        paint.setColor(ui.accent)
        canvas.drawPath(arrow, paint)
        canvas.restore()
        text(canvas, headingText, centerX, centerY + radius * .62f, ui.text, 24)
    }

    fun renderedFrames(): Long {
        return frames
    }

    fun hasScheduledFrame(): Boolean {
        return scheduled
    }
}

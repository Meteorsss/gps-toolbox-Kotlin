package com.meteorsss.startrace

import kotlin.math.exp

/** Frame-rate-independent shortest-arc interpolation. No sensor/platform dependencies.  */
class HeadingSmoother {
    private var value = Double.NaN
    private var previousFrame: Long = 0

    fun update(target: Double, frameNanos: Long): Double {
        if (!java.lang.Double.isFinite(target)) {
            reset()
            return Double.NaN
        }
        if (!java.lang.Double.isFinite(value) || previousFrame == 0L || frameNanos - previousFrame > 1000000000L) value =
            Geo.normalize(target)
        else if (frameNanos > previousFrame) {
            val dt = (frameNanos - previousFrame) / 1000000000.0
            val alpha = 1 - exp(-dt / 0.045)
            value = Geo.normalize(value + delta(value, target) * alpha)
        }
        previousFrame = frameNanos
        return value
    }

    fun reset() {
        value = Double.NaN
        previousFrame = 0
    }

    companion object {
        fun delta(from: Double, to: Double): Double {
            return (Geo.normalize(to) - Geo.normalize(from) + 540) % 360 - 180
        }
    }
}

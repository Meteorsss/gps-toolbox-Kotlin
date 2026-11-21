package com.meteorsss.startrace

import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Streaming analysis of all raw points, not the downsampled map preview.  */
class TrackAnalytics {
    class Result {
        var activeMs: Long = 0
        var movingMs: Long = 0
        var stationaryMs: Long = 0
        var observedMs: Long = 0
        var unknownMs: Long = 0
        var distance: Double = 0.0
        var movingDistance: Double = 0.0
        var maxSpeed: Double = Double.NaN
        var ascent: Double = Double.NaN
        var descent: Double = Double.NaN
        var minHeight: Double = Double.NaN
        var maxHeight: Double = Double.NaN
        var raw: Int = 0
        var accepted: Int = 0
        var sections: Int = 0
        var heightPoints: Int = 0
        var msl: Boolean = false

        fun averageKmh(): Double {
            return if (activeMs > 0) distance / (activeMs / 1000.0) * 3.6 else Double.NaN
        }

        fun movingKmh(): Double {
            return if (movingMs > 0) movingDistance / (movingMs / 1000.0) * 3.6 else Double.NaN
        }

        fun validPercent(): Double {
            return if (raw > 0) 100.0 * accepted / raw else Double.NaN
        }
    }

    private val result = Result()
    private var previous: TrackPoint? = null
    private val speeds = DoubleArray(3)
    private var speedCount = 0
    private var speedIndex = 0
    private val ellipsoid = HeightFilter()
    private val msl = HeightFilter()

    fun add(p: TrackPoint) {
        result.raw++
        if (!p.accepted) {
            previous = null
            speedCount = 0
            ellipsoid.breakSegment()
            msl.breakSegment()
            return
        }
        result.accepted++
        val connected =
            previous != null && previous!!.segment == p.segment && p.elapsed > previous!!.elapsed && p.elapsed - previous!!.elapsed <= 30000
        if (!connected) {
            result.sections++
            speedCount = 0
            ellipsoid.breakSegment()
            msl.breakSegment()
        }
        val reliableSpeed =
            java.lang.Double.isFinite(p.speed)
                    && p.speed >= 0 && (!java.lang.Double.isFinite(p.speedAccuracy) || p.speedAccuracy <= 3)
        if (reliableSpeed) {
            speeds[speedIndex] = p.speed
            speedIndex = (speedIndex + 1) % 3
            if (speedCount < 3) speedCount++
            if (speedCount == 3) {
                val sorted: DoubleArray? = speeds.clone()
                Arrays.sort(sorted)
                result.maxSpeed =
                    if (java.lang.Double.isFinite(result.maxSpeed)) max(result.maxSpeed, sorted!![1]) else sorted!![1]
            }
        } else speedCount = 0
        if (connected) {
            val dt = p.elapsed - previous!!.elapsed
            result.observedMs += dt
            val previousReliable =
                java.lang.Double.isFinite(previous!!.speed)
                        && previous!!.speed >= 0 && (!java.lang.Double.isFinite(previous!!.speedAccuracy) || previous!!.speedAccuracy <= 3)
            if (reliableSpeed && previousReliable) {
                if (p.speed >= MOVING_MPS && previous!!.speed >= MOVING_MPS) {
                    result.movingMs += dt
                    result.movingDistance += (p.speed + previous!!.speed) * 0.5 * dt / 1000.0
                } else if (p.speed < MOVING_MPS && previous!!.speed < MOVING_MPS) result.stationaryMs += dt
            }
        }
        ellipsoid.add(p.altitude, p.verticalAccuracy, p.elapsed)
        msl.add(p.mslAltitude, p.mslAccuracy, p.elapsed)
        previous = p
    }

    fun finish(recordedDistance: Double, activeMs: Long): Result {
        val out = Result()
        out.distance = max(0.0, recordedDistance)
        out.activeMs = max(0, activeMs)
        out.raw = result.raw
        out.accepted = result.accepted
        out.sections = result.sections
        out.maxSpeed = result.maxSpeed
        out.observedMs = min(out.activeMs, result.observedMs)
        out.movingMs = min(out.activeMs, result.movingMs)
        out.stationaryMs = min(max(0, out.activeMs - out.movingMs), result.stationaryMs)
        out.movingDistance = result.movingDistance
        out.unknownMs = max(0, out.activeMs - out.movingMs - out.stationaryMs)
        val selected = if (msl.count >= 3 && msl.count >= ellipsoid.count * 0.8) msl else ellipsoid
        out.msl = selected == msl
        out.heightPoints = selected.count
        if (selected.filteredCount >= 2) {
            out.ascent = selected.up
            out.descent = selected.down
            out.minHeight = selected.minimum
            out.maxHeight = selected.maximum
        }
        return out
    }

    private class HeightFilter {
        private val window = DoubleArray(5)
        private var size = 0
        private var index = 0
        private var lastTime: Long = 0
        private var lastRaw = Double.NaN
        private var anchor = Double.NaN
        private var anchorError = 0.0
        private var filtered = Double.NaN
        var count: Int = 0
        var filteredCount: Int = 0
        var up: Double = 0.0
        var down: Double = 0.0
        var minimum: Double = Double.POSITIVE_INFINITY
        var maximum: Double = Double.NEGATIVE_INFINITY

        fun breakSegment() {
            size = 0
            index = 0
            lastTime = 0
            lastRaw = Double.NaN
            anchor = Double.NaN
            filtered = Double.NaN
        }

        fun add(height: Double, error: Double, elapsed: Long) {
            if (!java.lang.Double.isFinite(height) || !java.lang.Double.isFinite(error) || error < 0 || error > MAX_VERTICAL_ERROR) {
                breakSegment()
                return
            }
            if (lastTime > 0 && (elapsed <= lastTime || elapsed - lastTime > 30000)) {
                breakSegment()
            }
            if (java.lang.Double.isFinite(lastRaw)
                && abs(height - lastRaw) > max(20.0, 8 * (elapsed - lastTime) / 1000.0)
            ) {
                breakSegment()
                return
            }
            count++
            lastRaw = height
            lastTime = elapsed
            window[index] = height
            index = (index + 1) % 5
            if (size < 5) size++
            if (size < 3) return
            val values: DoubleArray? = window.copyOf(size)
            Arrays.sort(values)
            val median = values!![size / 2]
            filtered = if (java.lang.Double.isFinite(filtered)) filtered + 0.45 * (median - filtered) else median
            filteredCount++
            minimum = min(minimum, filtered)
            maximum = max(maximum, filtered)
            if (!java.lang.Double.isFinite(anchor)) {
                anchor = filtered
                anchorError = error
                return
            }
            val threshold = max(4.0, max(anchorError, error) * 1.5)
            val change = filtered - anchor
            if (abs(change) >= threshold) {
                if (change > 0) up += change
                else down -= change
                anchor = filtered
                anchorError = error
            }
        }
    }

    companion object {
        const val MOVING_MPS: Double = 0.8
        const val MAX_VERTICAL_ERROR: Double = 15.0
    }
}

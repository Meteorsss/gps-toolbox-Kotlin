package com.meteorsss.startrace

import kotlin.math.max

/**
 * In-memory foreground speedometer session: no recorder, database, saved-point cadence or history.
 */
class SpeedSession(private var notBefore: Long) {
    class Snapshot private constructor(
        val distance: Double,
        val maxMps: Double,
        val averageMps: Double,
        val activeMs: Long,
        val validPoints: Int
    )

    private var activeSince: Long
    private var accumulated: Long = 0
    private var lastElapsed: Long = -1
    private var visible = true
    private var validPoints = 0
    private var distance = 0.0
    private var maxMps = Double.NaN
    private var previous: TrackPoint? = null
    private var anchor: TrackPoint? = null

    init {
        activeSince = notBefore
    }

    fun add(p: TrackPoint, mock: Boolean, nowElapsed: Long) {
        if (!visible || p.elapsed < notBefore || p.elapsed <= lastElapsed) return
        if (p.elapsed > nowElapsed) {
            disconnect()
            return
        }
        lastElapsed = p.elapsed
        if (mock
            || !p.accepted || !TrackFilter.usable(p, nowElapsed) || !TrackFilter.plausible(previous, p)
        ) {
            disconnect()
            return
        }
        if (previous != null && p.elapsed - previous!!.elapsed > 30000) disconnect()
        val step = TrackFilter.distanceContribution(anchor, p)
        distance += step
        if (anchor == null || step > 0 || p.elapsed - anchor!!.elapsed > 30000) anchor = p
        previous = p
        validPoints++
        if (java.lang.Double.isFinite(p.speed)
            && p.speed >= 0 && (!java.lang.Double.isFinite(p.speedAccuracy) || (p.speedAccuracy >= 0 && p.speedAccuracy <= 3))
        ) maxMps = if (java.lang.Double.isFinite(maxMps)) max(maxMps, p.speed) else p.speed
    }

    /** Missing, rejected or background positions must not form a fictitious connecting leg.  */
    fun disconnect() {
        previous = null
        anchor = null
    }

    fun pause(nowElapsed: Long) {
        if (!visible) return
        accumulated += max(0, nowElapsed - activeSince)
        visible = false
        disconnect()
    }

    fun resume(nowElapsed: Long) {
        if (visible) return
        visible = true
        notBefore = nowElapsed
        activeSince = notBefore
        disconnect()
    }

    fun snapshot(nowElapsed: Long): Snapshot {
        val activeMs = accumulated + (if (visible) max(0, nowElapsed - activeSince) else 0)
        val average = if (validPoints >= 2 && activeMs > 0) distance / (activeMs / 1000.0) else Double.NaN
        return SpeedSession.Snapshot(distance, maxMps, average, activeMs, validPoints)
    }
}

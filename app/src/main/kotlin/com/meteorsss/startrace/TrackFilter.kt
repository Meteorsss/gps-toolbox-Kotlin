package com.meteorsss.startrace

import kotlin.math.max

object TrackFilter {
    fun usable(p: TrackPoint, nowElapsed: Long): Boolean {
        return Geo.valid(p.lat, p.lon)
                && java.lang.Double.isFinite(p.accuracy)
                && p.accuracy >= 0 && p.accuracy <= 80 && p.elapsed <= nowElapsed + 1000 && nowElapsed - p.elapsed <= 15000
    }

    fun plausible(previous: TrackPoint?, p: TrackPoint): Boolean {
        if (previous == null) return true
        val dt = p.elapsed - previous.elapsed
        if (dt <= 0) return false
        if (dt > 30000) return true
        val limit = max(100.0, if (java.lang.Double.isFinite(p.speed)) p.speed * 3 + 15 else 100.0)
        return Geo.distance(previous.lat, previous.lon, p.lat, p.lon) / (dt / 1000.0) <= limit
    }

    fun distanceContribution(anchor: TrackPoint?, p: TrackPoint): Double {
        if (anchor == null || anchor.segment != p.segment || p.elapsed - anchor.elapsed > 30000) return 0.0
        val distance = Geo.distance(anchor.lat, anchor.lon, p.lat, p.lon)
        val floor = max(3.0, (anchor.accuracy + p.accuracy) * 0.6)
        val moving = java.lang.Double.isFinite(p.speed) && p.speed >= 0.5
        return if (distance >= floor && (moving || distance >= max(10.0, p.accuracy * 1.5)))
            distance
        else
            0.0
    }
}

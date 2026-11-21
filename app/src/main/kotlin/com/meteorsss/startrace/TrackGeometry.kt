package com.meteorsss.startrace

import kotlin.math.*

/** Pure geometry: preserve breaks and never join across the antimeridian. Input stays WGS84.  */
object TrackGeometry {
    fun segments(points: MutableList<TrackPoint>): MutableList<MutableList<TrackPoint>> {
        val out: MutableList<MutableList<TrackPoint>> = ArrayList<MutableList<TrackPoint>>()
        var current: MutableList<TrackPoint>? = null
        var previous: TrackPoint? = null
        for (p in points) {
            if (!p.accepted || !Geo.valid(p.lat, p.lon)) {
                previous = null
                current = null
                continue
            }
            if (previous == null || p.segment != previous.segment || abs(p.lon - previous.lon) > 180) {
                current = ArrayList<TrackPoint>()
                out.add(current)
            }
            current!!.add(p)
            previous = p
        }
        return out
    }

    fun accuracyRing(lat: Double, lon: Double, radius: Double): MutableList<DoubleArray> {
        if (!Geo.valid(
                lat,
                lon
            ) || !java.lang.Double.isFinite(radius) || radius < 0 || radius > 10000
        ) return mutableListOf<DoubleArray>()
        val ring: MutableList<DoubleArray> = ArrayList<DoubleArray>()
        val distance = radius / 6371008.8
        val latitude = Math.toRadians(lat)
        val longitude = Math.toRadians(lon)
        for (i in 0..48) {
            val bearing = 2 * Math.PI * i / 48
            val la = asin(
                sin(latitude) * cos(distance)
                        + cos(latitude) * sin(distance) * cos(bearing)
            )
            val lo =
                (longitude
                        + atan2(
                    sin(bearing) * sin(distance) * cos(latitude),
                    cos(distance) - sin(latitude) * sin(la)
                ))
            ring.add(doubleArrayOf(Math.toDegrees(la), ((Math.toDegrees(lo) + 540) % 360) - 180))
        }
        return ring
    }
}

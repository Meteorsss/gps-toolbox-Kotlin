package com.meteorsss.startrace

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline approximate WGS84 to GCJ-02 conversion, adapted from eviltransform. See
 * assets/third-party/eviltransform-LICENSE.txt for BSD-2-Clause attribution. The rectangular
 * applicability check is NOT a national or administrative boundary.
 */
object Gcj02 {
    private const val EARTH_R = 6378137.0
    private const val EE = 0.00669342162296594323

    fun inApproximateCoverage(latitude: Double, longitude: Double): Boolean {
        return Geo.valid(latitude, longitude)
                && longitude >= 72.004 && longitude <= 137.8347 && latitude >= 0.8293 && latitude <= 55.8271
    }

    fun fromWgs84(latitude: Double, longitude: Double): Coordinate {
        require(Geo.valid(latitude, longitude)) { "WGS84 坐标超出范围" }
        if (!inApproximateCoverage(latitude, longitude)) return Coordinate(latitude, longitude, false)
        val x = longitude - 105
        val y = latitude - 35
        val xy = x * y
        val absX = sqrt(abs(x))
        val xp = x * Math.PI
        val yp = y * Math.PI
        val common = 20 * sin(6 * xp) + 20 * sin(2 * xp)
        var dlat =
            ((common
                    + 20 * sin(yp) + 40 * sin(yp / 3) + 160 * sin(yp / 12) + 320 * sin(yp / 30))
                    * 2
                    / 3)
        var dlon =
            ((common
                    + 20 * sin(xp) + 40 * sin(xp / 3) + 150 * sin(xp / 12) + 300 * sin(xp / 30))
                    * 2
                    / 3)
        dlat += -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * xy + 0.2 * absX
        dlon += 300 + x + 2 * y + 0.1 * x * x + 0.1 * xy + 0.1 * absX
        val rad = Math.toRadians(latitude)
        val sin = sin(rad)
        val magic = 1 - EE * sin * sin
        val sqrt = sqrt(magic)
        dlat = dlat * 180 / ((EARTH_R * (1 - EE)) / (magic * sqrt) * Math.PI)
        dlon = dlon * 180 / (EARTH_R / sqrt * cos(rad) * Math.PI)
        return Coordinate(latitude + dlat, longitude + dlon, true)
    }

    class Coordinate private constructor(val latitude: Double, val longitude: Double, val converted: Boolean)
}

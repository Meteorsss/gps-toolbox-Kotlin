package com.meteorsss.startrace

class TrackPoint @JvmOverloads constructor(
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val accuracy: Double,
    val speed: Double,
    val time: Long,
    val elapsed: Long,
    val segment: Int,
    val accepted: Boolean,
    val verticalAccuracy: Double = Double.NaN,
    val mslAltitude: Double = Double.NaN,
    val mslAccuracy: Double = Double.NaN,
    val speedAccuracy: Double = Double.NaN
)

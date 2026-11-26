package com.meteorsss.startrace

/** Pure eligibility rules, independent of GPS course/speed and historical track points. */
object MapHeading {
    const val MAX_SAMPLE_AGE_MS = 2000L

    fun usable(hasCompass: Boolean, subscribed: Boolean, freshFix: Boolean,
               sampleAgeMs: Long, accuracy: Int, trueHeading: Double): Double {
        // Android SENSOR_STATUS_ACCURACY_MEDIUM=2; LOW/UNRELIABLE must not look precise.
        if (!hasCompass || !subscribed || !freshFix || sampleAgeMs !in 0..MAX_SAMPLE_AGE_MS ||
            accuracy < 2 || !trueHeading.isFinite()) return Double.NaN
        return Geo.normalize(trueHeading)
    }
}

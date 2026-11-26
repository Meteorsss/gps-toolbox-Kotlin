package com.meteorsss.startrace

import org.junit.Assert.*
import org.junit.Test

class MapHeadingTest {
    private fun heading(compass: Boolean = true, subscribed: Boolean = true, fix: Boolean = true,
                        age: Long = 10, accuracy: Int = 3, value: Double = 90.0) =
        MapHeading.usable(compass, subscribed, fix, age, accuracy, value)

    @Test fun reliableHeadingIsTrueNorthNormalized() { assertEquals(5.0, heading(value = 365.0), 0.0) }
    @Test fun missingSensorIsNotInventedFromTravelBearing() { assertTrue(heading(compass = false).isNaN()) }
    @Test fun pausedSensorIsHidden() { assertTrue(heading(subscribed = false).isNaN()) }
    @Test fun staleFixCannotProvideTrueNorthArrow() { assertTrue(heading(fix = false).isNaN()) }
    @Test fun lowAndUnreliableAccuracyAreHidden() { for (a in -1..1) assertTrue(heading(accuracy = a).isNaN()) }
    @Test fun sensorAgeMustBeCurrentAndMonotonic() {
        assertTrue(heading(age = -1).isNaN()); assertTrue(heading(age = 2001).isNaN())
        assertEquals(90.0, heading(age = 2000, accuracy = 2), 0.0)
    }
    @Test fun invalidAnglesAreHidden() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
            assertTrue(heading(value = value).isNaN())
    }
    @Test fun arrowSmoothingCrossesNorthByShortestArcAndResetsOnLoss() {
        val smoother = HeadingSmoother()
        assertEquals(359.0, smoother.update(359.0, 1000000000), 0.0)
        val next = smoother.update(1.0, 1050000000)
        assertTrue(kotlin.math.abs(HeadingSmoother.delta(359.0, next)) < 2.0)
        assertTrue(smoother.update(Double.NaN, 1100000000).isNaN())
        assertEquals(180.0, smoother.update(180.0, 1150000000), 0.0)
    }
}

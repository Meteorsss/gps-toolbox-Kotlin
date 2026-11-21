package com.meteorsss.startrace

import org.junit.Assert
import org.junit.Test

class TrackFilterTest {
    private fun p(
        lat: Double, lon: Double, acc: Double, speed: Double, elapsed: Long, segment: Int
    ): TrackPoint {
        return TrackPoint(
            lat, lon, 10.0, acc, speed, 1700000000000L + elapsed, elapsed, segment, true
        )
    }

    @Test
    fun rejectsStaleAndPoorAccuracy() {
        Assert.assertFalse(TrackFilter.usable(p(0.0, 0.0, 5.0, 0.0, 1000, 0), 20000))
        Assert.assertFalse(TrackFilter.usable(p(0.0, 0.0, 90.0, 0.0, 1000, 0), 1000))
        Assert.assertFalse(TrackFilter.usable(p(0.0, 0.0, Double.NaN, 0.0, 1000, 0), 1000))
    }

    @Test
    fun rejectsFutureLocations() {
        Assert.assertFalse(TrackFilter.usable(p(0.0, 0.0, 5.0, 0.0, 20000, 0), 1000))
    }

    @Test
    fun acceptsFreshPoint() {
        Assert.assertTrue(TrackFilter.usable(p(0.0, 0.0, 5.0, 2.0, 1000, 0), 2000))
    }

    @Test
    fun rejectsJumpAndDuplicateTimes() {
        val a = p(0.0, 0.0, 5.0, 0.0, 1000, 0)
        Assert.assertFalse(TrackFilter.plausible(a, p(1.0, 1.0, 5.0, 0.0, 2000, 0)))
        Assert.assertFalse(TrackFilter.plausible(a, p(0.0, 0.0, 5.0, 0.0, 1000, 0)))
    }

    @Test
    fun acceptsWalking() {
        Assert.assertTrue(TrackFilter.plausible(p(0.0, 0.0, 5.0, 1.0, 1000, 0), p(.00002, 0.0, 5.0, 1.0, 3000, 0)))
    }

    @Test
    fun neverBridgesSegmentsOrOutages() {
        val a = p(0.0, 0.0, 5.0, 1.0, 1000, 0)
        Assert.assertEquals(0.0, TrackFilter.distanceContribution(a, p(.001, 0.0, 5.0, 1.0, 5000, 1)), 0.0)
        Assert.assertEquals(0.0, TrackFilter.distanceContribution(a, p(.001, 0.0, 5.0, 1.0, 40000, 0)), 0.0)
    }

    @Test
    fun suppressesStationaryJitter() {
        Assert.assertEquals(
            0.0,
            TrackFilter.distanceContribution(p(0.0, 0.0, 5.0, 0.0, 1000, 0), p(.00002, 0.0, 5.0, 0.0, 2000, 0)),
            0.0
        )
    }

    @Test
    fun distanceAccumulatesWhenMoving() {
        Assert.assertTrue(
            TrackFilter.distanceContribution(p(0.0, 0.0, 5.0, 2.0, 1000, 0), p(.0001, 0.0, 5.0, 2.0, 6000, 0)) > 10
        )
    }
}

package com.meteorsss.startrace

import org.junit.Assert
import org.junit.Test

class TrackAnalyticsTest {
    private fun p(
        t: Int, height: Double, vAcc: Double, speed: Double, segment: Int, accepted: Boolean
    ): TrackPoint {
        return TrackPoint(
            30 + t * .00001,
            110.0,
            height,
            3.0,
            speed,
            t * 1000L,
            t * 1000L,
            segment,
            accepted,
            vAcc,
            Double.NaN,
            Double.NaN,
            0.1
        )
    }

    @Test
    fun noDataIsNotZeroHeight() {
        val r = TrackAnalytics().finish(0.0, 10000)
        Assert.assertTrue(java.lang.Double.isNaN(r.ascent))
        Assert.assertTrue(java.lang.Double.isNaN(r.maxSpeed))
        Assert.assertEquals(10000, r.unknownMs)
    }

    @Test
    fun averageExcludesUserPauseViaActiveDuration() {
        val r = TrackAnalytics().finish(1000.0, 100000)
        Assert.assertEquals(36.0, r.averageKmh(), .001)
    }

    @Test
    fun constantMovingSpeedAndTime() {
        val a = TrackAnalytics()
        for (i in 1..11) a.add(p(i, 10.0, 2.0, 2.0, 0, true))
        val r = a.finish(20.0, 10000)
        Assert.assertEquals(10000, r.movingMs)
        Assert.assertEquals(7.2, r.movingKmh(), .001)
        Assert.assertEquals(2.0, r.maxSpeed, 0.0)
        Assert.assertEquals(0, r.unknownMs)
    }

    @Test
    fun stationaryJitterDoesNotBecomeClimb() {
        val a = TrackAnalytics()
        for (i in 1..100) a.add(p(i, 100 + (if (i % 2 == 0) .8 else -.8), 3.0, 0.0, 0, true))
        val r = a.finish(0.0, 99000)
        Assert.assertEquals(0.0, r.ascent, 0.0)
        Assert.assertEquals(0.0, r.descent, 0.0)
        Assert.assertEquals(99000, r.stationaryMs)
    }

    @Test
    fun ascentAndDescentAreFiltered() {
        val a = TrackAnalytics()
        for (i in 1..101) a.add(p(i, (100 + (if (i <= 51) i - 1 else 101 - i) * 2).toDouble(), 2.0, 2.0, 0, true))
        val r = a.finish(200.0, 100000)
        Assert.assertTrue(r.ascent > 85 && r.ascent < 105)
        Assert.assertTrue(r.descent > 75 && r.descent < 105)
    }

    @Test
    fun missingVerticalQualityIsUnknown() {
        val a = TrackAnalytics()
        for (i in 1..19) a.add(p(i, (i * 10).toDouble(), Double.NaN, 1.0, 0, true))
        Assert.assertTrue(java.lang.Double.isNaN(a.finish(20.0, 18000).ascent))
    }

    @Test
    fun poorVerticalQualityIsUnknown() {
        val a = TrackAnalytics()
        for (i in 1..19) a.add(p(i, (i * 2).toDouble(), 80.0, 1.0, 0, true))
        Assert.assertTrue(java.lang.Double.isNaN(a.finish(20.0, 18000).ascent))
    }

    @Test
    fun segmentBreakDoesNotCountHeightJump() {
        val a = TrackAnalytics()
        for (i in 1..20) a.add(p(i, (if (i <= 10) 10 else 200).toDouble(), 2.0, 1.0, if (i <= 10) 0 else 1, true))
        val r = a.finish(20.0, 19000)
        Assert.assertEquals(0.0, r.ascent, 0.0)
        Assert.assertEquals(2, r.sections.toLong())
        Assert.assertEquals(18000, r.movingMs)
    }

    @Test
    fun gapDoesNotCountMissingTravel() {
        val a = TrackAnalytics()
        a.add(p(1, 10.0, 2.0, 2.0, 0, true))
        a.add(p(60, 200.0, 2.0, 2.0, 0, true))
        val r = a.finish(0.0, 59000)
        Assert.assertEquals(0, r.movingMs)
        Assert.assertEquals(59000, r.unknownMs)
    }

    @Test
    fun rejectedPointsBreakContinuity() {
        val a = TrackAnalytics()
        a.add(p(1, 10.0, 2.0, 2.0, 0, true))
        a.add(p(2, 20.0, 2.0, 2.0, 0, false))
        a.add(p(3, 30.0, 2.0, 2.0, 0, true))
        val r = a.finish(0.0, 2000)
        Assert.assertEquals(3, r.raw.toLong())
        Assert.assertEquals(2, r.accepted.toLong())
        Assert.assertEquals(0, r.movingMs)
    }

    @Test
    fun medianRejectsSingleSpeedSpike() {
        val a = TrackAnalytics()
        val speeds = doubleArrayOf(2.0, 2.0, 90.0, 2.0, 2.0)
        for (i in speeds.indices) a.add(p(i + 1, 10.0, 2.0, speeds[i], 0, true))
        Assert.assertEquals(2.0, a.finish(10.0, 4000).maxSpeed, 0.0)
    }

    @Test
    fun mslAndEllipsoidNeverMix() {
        val a = TrackAnalytics()
        for (i in 1..19) a.add(
            TrackPoint(
                30.0,
                110.0,
                150.0,
                3.0,
                2.0,
                (i * 1000).toLong(),
                (i * 1000).toLong(),
                0,
                true,
                2.0,
                100.0,
                2.0,
                .1
            )
        )
        val r = a.finish(30.0, 18000)
        Assert.assertTrue(r.msl)
        Assert.assertEquals(100.0, r.minHeight, 0.0)
        Assert.assertEquals(0.0, r.ascent, 0.0)
    }

    @Test
    fun snapshotsDoNotMutateAccumulator() {
        val a = TrackAnalytics()
        for (i in 1..5) a.add(p(i, 10.0, 2.0, 2.0, 0, true))
        a.finish(5.0, 1000)
        Assert.assertEquals(4000, a.finish(8.0, 4000).movingMs)
    }
}

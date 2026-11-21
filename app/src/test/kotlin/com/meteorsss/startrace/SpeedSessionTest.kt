package com.meteorsss.startrace

import org.junit.Assert
import org.junit.Test

class SpeedSessionTest {
    private fun point(elapsed: Long, lat: Double, speed: Double): TrackPoint {
        return TrackPoint(
            lat,
            121.0,
            Double.NaN,
            3.0,
            speed,
            1700000000000L + elapsed,
            elapsed,
            0,
            true,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            .1
        )
    }

    private fun add(session: SpeedSession, elapsed: Long, lat: Double, speed: Double) {
        session.add(point(elapsed, lat, speed), false, elapsed)
    }

    @Test
    fun enteringIsFreshAndWaitingIsNotARecorderDependency() {
        val s = SpeedSession(1000).snapshot(3000)
        Assert.assertEquals(0.0, s.distance, 0.0)
        Assert.assertEquals(0, s.validPoints.toLong())
        Assert.assertTrue(java.lang.Double.isNaN(s.maxMps))
        Assert.assertTrue(java.lang.Double.isNaN(s.averageMps))
        Assert.assertEquals(2000, s.activeMs)
    }

    @Test
    fun firstFreshSpeedProducesAnIndependentPeak() {
        val session = SpeedSession(1000)
        add(session, 1000, 31.0, 5.0)
        val s = session.snapshot(2000)
        Assert.assertEquals(5.0, s.maxMps, 0.0)
        Assert.assertEquals(18.0, s.maxMps * 3.6, .001)
        Assert.assertTrue(java.lang.Double.isNaN(s.averageMps))
        Assert.assertEquals(1, s.validPoints.toLong())
    }

    @Test
    fun distanceAndAverageUseOnlyThisSession() {
        val session = SpeedSession(1000)
        add(session, 1000, 31.0, 12.0)
        add(session, 2000, 31.0001, 12.0)
        val expected = Geo.distance(31.0, 121.0, 31.0001, 121.0)
        val s = session.snapshot(3000)
        Assert.assertEquals(expected, s.distance, .001)
        Assert.assertEquals(expected / 2, s.averageMps, .001)
        Assert.assertEquals(12.0, s.maxMps, 0.0)
    }

    @Test
    fun repeatedRefreshesNeverDoubleCountOneFix() {
        val session = SpeedSession(1000)
        add(session, 1000, 31.0, 1.0)
        session.add(point(1000, 32.0, 100.0), false, 1500)
        Assert.assertEquals(1, session.snapshot(2000).validPoints.toLong())
        Assert.assertEquals(1.0, session.snapshot(2000).maxMps, 0.0)
    }

    @Test
    fun preEntryFixIsNotPartOfNewSession() {
        val session = SpeedSession(5000)
        session.add(point(4000, 31.0, 20.0), false, 5000)
        Assert.assertEquals(0, session.snapshot(5000).validPoints.toLong())
        add(session, 6000, 32.0, 2.0)
        Assert.assertEquals(0.0, session.snapshot(6000).distance, 0.0)
        Assert.assertEquals(2.0, session.snapshot(6000).maxMps, 0.0)
    }

    @Test
    fun mockPositionNeitherCountsNorConnectsAcrossBreak() {
        val session = SpeedSession(1000)
        add(session, 1000, 31.0, 2.0)
        session.add(point(2000, 31.0001, 100.0), true, 2000)
        add(session, 3000, 31.0002, 2.0)
        Assert.assertEquals(0.0, session.snapshot(3000).distance, 0.0)
        Assert.assertEquals(2.0, session.snapshot(3000).maxMps, 0.0)
        Assert.assertEquals(2, session.snapshot(3000).validPoints.toLong())
    }

    @Test
    fun lowAccuracyPositionDoesNotCreateDistanceOrPeak() {
        val session = SpeedSession(1000)
        val poor = TrackPoint(31.0, 121.0, 0.0, 100.0, 40.0, 0, 1000, 0, true)
        session.add(poor, false, 1000)
        Assert.assertEquals(0, session.snapshot(2000).validPoints.toLong())
        Assert.assertTrue(java.lang.Double.isNaN(session.snapshot(2000).maxMps))
    }

    @Test
    fun unreliableSpeedDoesNotBecomeMaximum() {
        val session = SpeedSession(1000)
        session.add(
            TrackPoint(
                31.0, 121.0, 0.0, 3.0, 100.0, 0, 1000, 0, true, Double.NaN, Double.NaN, Double.NaN, 20.0
            ),
            false,
            1000
        )
        Assert.assertTrue(java.lang.Double.isNaN(session.snapshot(1000).maxMps))
        add(session, 2000, 31.0001, 2.0)
        Assert.assertEquals(2.0, session.snapshot(2000).maxMps, 0.0)
    }

    @Test
    fun staleAndFutureFixesAreIgnoredWithoutPoisoningNextFix() {
        val session = SpeedSession(1000)
        session.add(point(1000, 31.0, 20.0), false, 20000)
        session.add(point(50000, 32.0, 20.0), false, 20000)
        add(session, 21000, 31.0, 2.0)
        Assert.assertEquals(1, session.snapshot(21000).validPoints.toLong())
        Assert.assertEquals(0.0, session.snapshot(21000).distance, 0.0)
        Assert.assertEquals(2.0, session.snapshot(21000).maxMps, 0.0)
    }

    @Test
    fun implausibleJumpIsNotMeasuredAsMovement() {
        val session = SpeedSession(1000)
        add(session, 1000, 31.0, 2.0)
        add(session, 2000, 32.0, 2.0)
        Assert.assertEquals(1, session.snapshot(2000).validPoints.toLong())
        Assert.assertEquals(0.0, session.snapshot(2000).distance, 0.0)
    }

    @Test
    fun gpsGapOrExplicitDisconnectHasNoBridge() {
        val session = SpeedSession(1000)
        add(session, 1000, 31.0, 2.0)
        add(session, 41000, 31.001, 2.0)
        Assert.assertEquals(0.0, session.snapshot(41000).distance, 0.0)
        session.disconnect()
        add(session, 42000, 31.0011, 2.0)
        Assert.assertEquals(0.0, session.snapshot(42000).distance, 0.0)
        add(session, 43000, 31.0012, 2.0)
        Assert.assertTrue(session.snapshot(43000).distance > 0)
    }

    @Test
    fun backgroundTimeAndMovementAreNotCounted() {
        val session = SpeedSession(1000)
        add(session, 1000, 31.0, 2.0)
        add(session, 2000, 31.0001, 2.0)
        val distance = session.snapshot(2000).distance
        session.pause(3000)
        session.pause(4000)
        add(session, 5000, 31.0005, 30.0)
        Assert.assertEquals(2000, session.snapshot(10000).activeMs)
        session.resume(10000)
        session.resume(11000)
        session.add(point(9000, 31.0009, 20.0), false, 10000)
        add(session, 11000, 31.001, 2.0)
        Assert.assertEquals(distance, session.snapshot(11000).distance, 0.0)
        Assert.assertEquals(3000, session.snapshot(11000).activeMs)
        Assert.assertEquals(2.0, session.snapshot(11000).maxMps, 0.0)
    }

    @Test
    fun newSessionHasNoPreviousTotals() {
        val previous = SpeedSession(1000)
        add(previous, 1000, 31.0, 8.0)
        add(previous, 2000, 31.0001, 8.0)
        Assert.assertTrue(previous.snapshot(2000).distance > 0)
        val fresh = SpeedSession(3000)
        Assert.assertEquals(0.0, fresh.snapshot(3000).distance, 0.0)
        Assert.assertEquals(0, fresh.snapshot(3000).validPoints.toLong())
        Assert.assertTrue(java.lang.Double.isNaN(fresh.snapshot(3000).maxMps))
    }

    @Test
    fun stationaryValidPositionsReportZeroNotUnknown() {
        val session = SpeedSession(1000)
        add(session, 1000, 31.0, 0.0)
        add(session, 2000, 31.0, 0.0)
        val s = session.snapshot(3000)
        Assert.assertEquals(0.0, s.distance, 0.0)
        Assert.assertEquals(0.0, s.maxMps, 0.0)
        Assert.assertEquals(0.0, s.averageMps, 0.0)
        Assert.assertTrue(SpeedSession(5000).snapshot(4000).activeMs >= 0)
    }
}

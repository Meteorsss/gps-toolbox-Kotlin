package com.meteorsss.startrace

import org.junit.Assert
import org.junit.Test

class HeadingSmootherTest {
    @Test
    fun crossesNorthByShortestArc() {
        val s = HeadingSmoother()
        s.update(359.0, 1000000000L)
        val h = s.update(1.0, 1016000000L)
        Assert.assertTrue(h > 359 || h < 1)
        Assert.assertEquals(2.0, HeadingSmoother.Companion.delta(359.0, 1.0), 1e-8)
    }

    @Test
    fun reverseCrossingNeverSpins() {
        val s = HeadingSmoother()
        s.update(1.0, 1000000000L)
        val h = s.update(359.0, 1016000000L)
        Assert.assertTrue(h >= 0 && h < 1)
        Assert.assertEquals(-2.0, HeadingSmoother.Companion.delta(1.0, 359.0), 1e-8)
    }

    @Test
    fun frameRateIndependent() {
        val a = HeadingSmoother()
        val b = HeadingSmoother()
        a.update(0.0, 1000000000L)
        b.update(0.0, 1000000000L)
        var av = 0.0
        var bv = 0.0
        for (i in 1..6) av = a.update(90.0, 1000000000L + i * 16000000L)
        for (i in 1..12) bv = b.update(90.0, 1000000000L + i * 8000000L)
        Assert.assertEquals(av, bv, 1e-8)
        Assert.assertTrue(av > 75)
    }

    @Test
    fun firstFrameSnapsWithoutStartupSpin() {
        Assert.assertEquals(270.0, HeadingSmoother().update(270.0, 1000000000L), 1e-8)
    }

    @Test
    fun pauseResets() {
        val s = HeadingSmoother()
        s.update(10.0, 1000000000L)
        s.reset()
        Assert.assertEquals(240.0, s.update(240.0, 2000000000L), 1e-8)
    }

    @Test
    fun longGapDoesNotAnimateStaleHeading() {
        val s = HeadingSmoother()
        s.update(10.0, 1000000000L)
        Assert.assertEquals(240.0, s.update(240.0, 4000000000L), 1e-8)
    }

    @Test
    fun unknownHeadingRemainsUnknown() {
        val s = HeadingSmoother()
        Assert.assertTrue(java.lang.Double.isNaN(s.update(Double.NaN, 1)))
        Assert.assertEquals(90.0, s.update(90.0, 100), 1e-8)
    }
}

package com.meteorsss.startrace

import org.junit.Assert
import org.junit.Test
import java.util.*

class TrackGeometryTest {
    private fun p(lon: Double, segment: Int, valid: Boolean): TrackPoint {
        return TrackPoint(30.0, lon, 10.0, 3.0, 1.0, 0, 0, segment, valid)
    }

    @Test
    fun breaksArePreserved() {
        Assert.assertEquals(
            2,
            TrackGeometry.segments(Arrays.asList<TrackPoint?>(p(110.0, 0, true), p(111.0, 0, true), p(112.0, 1, true)))
                .size.toLong()
        )
    }

    @Test
    fun noDateLineBridge() {
        Assert.assertEquals(
            2, TrackGeometry.segments(Arrays.asList<TrackPoint?>(p(179.0, 0, true), p(-179.0, 0, true))).size.toLong()
        )
    }

    @Test
    fun rejectedPointDoesNotGetConnected() {
        Assert.assertEquals(
            2,
            TrackGeometry.segments(Arrays.asList<TrackPoint?>(p(110.0, 0, true), p(111.0, 0, false), p(112.0, 0, true)))
                .size.toLong()
        )
    }

    @Test
    fun accuracyRingHasCorrectRadius() {
        val ring = TrackGeometry.accuracyRing(30.0, 110.0, 20.0)
        Assert.assertEquals(49, ring.size.toLong())
        for (point in ring) Assert.assertEquals(20.0, Geo.distance(30.0, 110.0, point[0], point[1]), .001)
        Assert.assertEquals(ring.get(0)[0], ring.get(48)[0], .000001)
    }

    @Test
    fun invalidRadiusHasNoCircle() {
        Assert.assertTrue(TrackGeometry.accuracyRing(30.0, 110.0, Double.NaN).isEmpty())
        Assert.assertTrue(TrackGeometry.accuracyRing(30.0, 110.0, -1.0).isEmpty())
    }
}

package com.meteorsss.startrace

import org.junit.Assert
import org.junit.Test

/**
 * Reference vectors from eviltransform/go/transform_test.go (03ba58d); bundled BSD-2-Clause
 * license.
 */
class Gcj02Test {
    private fun check(a: Double, b: Double, x: Double, y: Double) {
        val c = Gcj02.fromWgs84(a, b)
        Assert.assertTrue(c.converted)
        Assert.assertEquals(x, c.latitude, 0.0000005)
        Assert.assertEquals(y, c.longitude, 0.0000005)
    }

    @Test
    fun shanghaiFixture() {
        check(31.1774276, 121.5272106, 31.17530398364597, 121.531541859215)
    }

    @Test
    fun shenzhenFixture() {
        check(22.543847, 113.912316, 22.540796131694766, 113.9171764808363)
    }

    @Test
    fun beijingFixture() {
        check(39.911954, 116.377817, 39.91334545536069, 116.38404722455657)
    }

    @Test
    fun outsideCoverageIsNotFalselyLabelledConverted() {
        val c = Gcj02.fromWgs84(40.7, -74.0)
        Assert.assertFalse(c.converted)
        Assert.assertEquals(40.7, c.latitude, 0.0)
        Assert.assertEquals(-74.0, c.longitude, 0.0)
    }

    @Test
    fun boundaryIsOnlyApproximateCoverage() {
        Assert.assertFalse(Gcj02.inApproximateCoverage(0.0, 0.0))
        Assert.assertTrue(Gcj02.inApproximateCoverage(31.0, 121.0))
        Assert.assertFalse(Gcj02.inApproximateCoverage(56.0, 121.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNan() {
        Gcj02.fromWgs84(Double.NaN, 121.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRange() {
        Gcj02.fromWgs84(91.0, 121.0)
    }
}

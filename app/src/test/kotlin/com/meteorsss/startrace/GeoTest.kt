package com.meteorsss.startrace

import org.junit.Assert
import org.junit.Test

class GeoTest {
    @Test
    fun identicalPointsHaveZeroDistance() {
        Assert.assertEquals(0.0, Geo.distance(31.0, 121.0, 31.0, 121.0), .0001)
    }

    @Test
    fun equatorDegreeIsAbout111km() {
        Assert.assertEquals(111195.0, Geo.distance(0.0, 0.0, 0.0, 1.0), 2.0)
    }

    @Test
    fun crossesDateLineByShortPath() {
        Assert.assertTrue(Geo.distance(0.0, 179.9, 0.0, -179.9) < 23000)
    }

    @Test
    fun northAndEastBearings() {
        Assert.assertEquals(0.0, Geo.bearing(0.0, 0.0, 1.0, 0.0), .001)
        Assert.assertEquals(90.0, Geo.bearing(0.0, 0.0, 0.0, 1.0), .001)
    }

    @Test
    fun coordinatesAreRangeChecked() {
        Assert.assertFalse(Geo.valid(Double.NaN, 0.0))
        Assert.assertFalse(Geo.valid(91.0, 0.0))
        Assert.assertFalse(Geo.valid(0.0, 181.0))
        Assert.assertTrue(Geo.valid(-90.0, -180.0))
    }

    @Test
    fun decimalAndDmsParsing() {
        Assert.assertEquals(31.5, Geo.parse("31° 30′ 00″ N", true), .000001)
        Assert.assertEquals(-121.25, Geo.parse("121° 15′ W", false), .000001)
        Assert.assertEquals(-12.5, Geo.parse("-12.5", true), .000001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidMinutes() {
        Geo.parse("31° 61′", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongHemisphere() {
        Geo.parse("31 E", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGarbage() {
        Geo.parse("abc 31", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRange() {
        Geo.parse("181", false)
    }

    @Test
    fun dmsCarriesSeconds() {
        Assert.assertEquals("32° 00′ 00.00″ N", Geo.dms(31.99999999, true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedDecimal() {
        Geo.parse("31..5", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsConflictingSign() {
        Geo.parse("-31 N", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultipleHemisphereMarkers() {
        Geo.parse("31 NS", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmbeddedHemisphere() {
        Geo.parse("3N1", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrefixSignConflict() {
        Geo.parse("N -31", true)
    }

    @Test
    fun parsesWhitespaceSeparators() {
        Assert.assertEquals(31.5, Geo.parse("31\t30\t0 N", true), .000001)
    }

    @Test
    fun durationAndNormalization() {
        Assert.assertEquals("01:01:01", Geo.duration(3661000))
        Assert.assertEquals(350.0, Geo.normalize(-10.0), .001)
    }
}

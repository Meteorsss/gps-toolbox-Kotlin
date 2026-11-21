package com.meteorsss.startrace

import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

class GpxTest {
    @Test
    fun escapesTrackNames() {
        Assert.assertEquals("A&amp;B&lt;C&gt;&quot;&apos;", Gpx.escape("A&B<C>\"'"))
    }

    @Test
    @Throws(Exception::class)
    fun emitsValidXmlAndHonestAltitude() {
        val p = TrackPoint(31.2, 121.3, 32.0, 5.0, 1.0, 1700000000000L, 1000, 0, true)
        val xml = Gpx.header("A&B") + "<trkseg>" + Gpx.point(p) + "</trkseg>" + Gpx.footer()
        val factory = DocumentBuilderFactory.newInstance()
        factory!!.setNamespaceAware(true)
        Assert.assertNotNull(
            factory
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        )
        Assert.assertFalse(xml.contains("<ele>"))
        Assert.assertTrue(xml.contains("ellipsoidHeight"))
        Assert.assertTrue(xml.contains("2023-11-14T22:13:20Z"))
    }

    @Test
    fun missingMeasurementsNeverEmitNaN() {
        val point = Gpx.point(TrackPoint(0.0, 0.0, Double.NaN, 3.0, Double.NaN, 0, 0, 0, true))
        Assert.assertFalse(point.contains("NaN"))
        Assert.assertFalse(point.contains("ellipsoidHeight"))
    }
}

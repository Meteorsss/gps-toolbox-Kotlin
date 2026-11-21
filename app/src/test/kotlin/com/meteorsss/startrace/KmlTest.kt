package com.meteorsss.startrace

import com.meteorsss.startrace.Kml.Exporter
import org.junit.Assert
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory

class KmlTest {
    private fun point(lat: Double, lon: Double, elapsed: Long, section: Int, valid: Boolean): TrackPoint {
        return TrackPoint(
            lat, lon, 123.456, 3.0, 2.0, 1700000000000L + elapsed, elapsed, section, valid
        )
    }

    @Throws(Exception::class)
    private fun export(name: String, vararg points: TrackPoint?): String {
        val out: StringWriter? = StringWriter()
        val writer = Exporter(out, name)
        for (p in points) writer.add(p)
        writer.finish()
        return out.toString()
    }

    @Throws(Exception::class)
    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory!!.setNamespaceAware(true)
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(charset("UTF-8"))))
    }

    private fun count(doc: Document, name: String): Int {
        return doc.getElementsByTagNameNS(Kml.NAMESPACE, name).getLength()
    }

    @Test
    @Throws(Exception::class)
    fun standardKmlNamespaceEscapingAndUnicode() {
        val name = "轨迹 & < > \" ' 🚶"
        val d = parse(export(name, point(31.0, 121.0, 1000, 0, true)))
        Assert.assertEquals(Kml.NAMESPACE, d.getDocumentElement().getNamespaceURI())
        Assert.assertEquals(name, d.getElementsByTagNameNS(Kml.NAMESPACE, "name").item(0).getTextContent())
        Assert.assertEquals(1, count(d, "Document").toLong())
        Assert.assertEquals(0, count(d, "NetworkLink").toLong())
    }

    @Test
    @Throws(Exception::class)
    fun longitudeFirstGroundClampedNoInventedAltitude() {
        val xml =
            export(
                "test",
                point(31.12345678, 121.87654321, 1000, 0, true),
                point(31.2, 121.9, 2000, 0, true)
            )
        val d = parse(xml)
        Assert.assertEquals(1, count(d, "LineString").toLong())
        Assert.assertEquals(2, count(d, "Point").toLong())
        Assert.assertEquals(
            "clampToGround",
            d.getElementsByTagNameNS(Kml.NAMESPACE, "altitudeMode").item(0).getTextContent()
        )
        Assert.assertEquals(
            "1", d.getElementsByTagNameNS(Kml.NAMESPACE, "tessellate").item(0).getTextContent()
        )
        Assert.assertTrue(xml.contains("121.87654321,31.12345678"))
        Assert.assertFalse(xml.contains("123.456"))
        val coordinates: Array<String>? =
            d.getElementsByTagNameNS(Kml.NAMESPACE, "coordinates")
                .item(0)
                .getTextContent()
                .trim { it <= ' ' }
                .split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        Assert.assertEquals(2, coordinates!!.size.toLong())
        for (coordinate in coordinates) Assert.assertEquals(
            2,
            coordinate.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray().size.toLong()
        )
    }

    @Test
    @Throws(Exception::class)
    fun singlePointIsPointNotInvalidOneVertexLine() {
        val d = parse(export("single", point(0.0, 0.0, 1000, 0, true)))
        Assert.assertEquals(2, count(d, "Point").toLong())
        Assert.assertEquals(0, count(d, "LineString").toLong())
    }

    @Test
    @Throws(Exception::class)
    fun pauseAndRejectedPointPreserveSeparateSections() {
        val d =
            parse(
                export(
                    "gaps",
                    point(1.0, 2.0, 1000, 0, true),
                    point(1.1, 2.1, 2000, 0, true),
                    point(5.0, 6.0, 3000, 0, false),
                    point(1.2, 2.2, 4000, 0, true),
                    point(1.3, 2.3, 5000, 1, true),
                    point(1.4, 2.4, 6000, 1, true)
                )
            )
        Assert.assertEquals(5, count(d, "Placemark").toLong())
        Assert.assertEquals(2, count(d, "LineString").toLong())
        Assert.assertEquals(3, count(d, "Point").toLong())
        Assert.assertFalse(d.getDocumentElement().getTextContent().contains("6.00000000,5.00000000"))
    }

    @Test
    @Throws(Exception::class)
    fun invalidCoordinatesNeverEmittedAndBreakTheLine() {
        val xml =
            export(
                "invalid",
                point(1.0, 2.0, 1000, 0, true),
                point(Double.NaN, 3.0, 2000, 0, true),
                point(1.0, 181.0, 3000, 0, true),
                point(1.0, 3.0, 4000, 0, true)
            )
        Assert.assertFalse(xml.contains("NaN"))
        Assert.assertFalse(xml.contains("181.00000000"))
        Assert.assertEquals(4, count(parse(xml), "Point").toLong())
    }

    @Test
    @Throws(Exception::class)
    fun antimeridianAndMissingTimeNeverJoin() {
        val d =
            parse(
                export(
                    "dateline",
                    point(1.0, 179.9, 1000, 0, true),
                    point(1.0, -179.9, 2000, 0, true),
                    point(1.0, -179.8, 33000, 0, true),
                    point(1.0, -179.7, 34000, 0, true)
                )
            )
        Assert.assertEquals(5, count(d, "Placemark").toLong())
        Assert.assertEquals(4, count(d, "Point").toLong())
        Assert.assertEquals(1, count(d, "LineString").toLong())
    }

    @Test
    @Throws(Exception::class)
    fun emptyOrAllRejectedIsWellFormed() {
        Assert.assertEquals(0, count(parse(export("empty")), "Placemark").toLong())
        Assert.assertEquals(0, count(parse(export("rejected", point(1.0, 2.0, 0, 0, false))), "Placemark").toLong())
    }

    @Test
    @Throws(Exception::class)
    fun decimalSeparatorIsIndependentOfLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            Assert.assertEquals("121.50000000,31.25000000\n", Kml.coordinate(point(31.25, 121.5, 1, 0, true)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    @Throws(Exception::class)
    fun removesXmlControlsButPreservesValidWhitespace() {
        val name = "A" + 1.toChar() + "B" + 0.toChar() + "C\nD\tE"
        val d = parse(export(name))
        Assert.assertEquals(
            "ABC\nD\tE", d.getElementsByTagNameNS(Kml.NAMESPACE, "name").item(0).getTextContent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun fullTrackIsNotPreviewSampledAndFinishIsIdempotent() {
        val out: StringWriter? = StringWriter()
        val exporter = Exporter(out, "long")
        for (i in 0..6000) exporter.add(point(31 + i * .000001, 121.0, 1000L * i, 0, true))
        exporter.finish()
        val complete = out.toString()
        exporter.finish()
        Assert.assertEquals(complete, out.toString())
        val d = parse(complete)
        Assert.assertEquals(1, count(d, "LineString").toLong())
        Assert.assertEquals(
            6001,
            d.getElementsByTagNameNS(Kml.NAMESPACE, "coordinates")
                .item(0)
                .getTextContent()
                .trim { it <= ' ' }
                .split("\\s+".toRegex())
                .dropLastWhile { it.isEmpty() }.toTypedArray().size.toLong()
        )
    }

    @Test
    @Throws(Exception::class)
    fun endpointsUseFirstAndLastValidPointsNotRejectedEdges() {
        val xml =
            export(
                "endpoints",
                point(2.0, 3.0, 0, 0, false),
                point(31.0, 121.0, 1000, 0, true),
                point(31.1, 121.1, 2000, 0, true),
                point(31.2, 121.2, 3000, 1, true),
                point(5.0, 6.0, 4000, 1, false)
            )
        val d = parse(xml)
        val marks = d.getElementsByTagNameNS(Kml.NAMESPACE, "Placemark")
        var endpoints = 0
        for (i in 0..<marks.getLength()) {
            val mark = marks.item(i) as Element
            if (!mark.getAttribute("id").startsWith("track-")) continue
            endpoints++
            val expected = if (mark.getAttribute("id") == "track-start") "起点" else "终点"
            Assert.assertEquals(
                expected, mark.getElementsByTagNameNS(Kml.NAMESPACE, "name").item(0).getTextContent()
            )
            val coord: String? =
                mark.getElementsByTagNameNS(Kml.NAMESPACE, "coordinates").item(0).getTextContent().trim { it <= ' ' }
            Assert.assertEquals(
                if (expected == "起点") "121.00000000,31.00000000" else "121.20000000,31.20000000", coord
            )
            Assert.assertEquals(1, mark.getElementsByTagNameNS(Kml.NAMESPACE, "IconStyle").getLength().toLong())
            Assert.assertEquals(0, mark.getElementsByTagNameNS(Kml.NAMESPACE, "href").getLength().toLong())
        }
        Assert.assertEquals(2, endpoints.toLong())
    }

    @Test
    @Throws(Exception::class)
    fun oneValidPointHasCombinedEndpointAndEmptyHasNone() {
        val xml = export("one", point(31.0, 121.0, 0, 0, true))
        Assert.assertTrue(xml.contains("id=\"track-start-end\""))
        Assert.assertTrue(xml.contains("起点 / 终点"))
        Assert.assertFalse(export("empty").contains("id=\"track-"))
    }

    @Test
    fun formatSelectionRestoresWithLegacyAndUnknownFallback() {
        Assert.assertEquals(TrackExport.KML, TrackExport.Companion.restore("KML", false))
        Assert.assertEquals(TrackExport.CSV, TrackExport.Companion.restore(null, true))
        Assert.assertEquals(TrackExport.GPX, TrackExport.Companion.restore("future", false))
        Assert.assertEquals("application/vnd.google-earth.kml+xml", TrackExport.KML.mimeType)
        Assert.assertEquals(".kml", TrackExport.KML.extension)
    }
}

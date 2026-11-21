package com.meteorsss.startrace;

import static org.junit.Assert.*;

import java.io.*;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.*;

public class KmlTest {
  private TrackPoint point(double lat, double lon, long elapsed, int section, boolean valid) {
    return new TrackPoint(
        lat, lon, 123.456, 3, 2, 1700000000000L + elapsed, elapsed, section, valid);
  }

  private String export(String name, TrackPoint... points) throws Exception {
    StringWriter out = new StringWriter();
    Kml.Exporter writer = new Kml.Exporter(out, name);
    for (TrackPoint p : points) writer.add(p);
    writer.finish();
    return out.toString();
  }

  private Document parse(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
  }

  private int count(Document doc, String name) {
    return doc.getElementsByTagNameNS(Kml.NAMESPACE, name).getLength();
  }

  @Test
  public void standardKmlNamespaceEscapingAndUnicode() throws Exception {
    String name = "轨迹 & < > \" ' 🚶";
    Document d = parse(export(name, point(31, 121, 1000, 0, true)));
    assertEquals(Kml.NAMESPACE, d.getDocumentElement().getNamespaceURI());
    assertEquals(name, d.getElementsByTagNameNS(Kml.NAMESPACE, "name").item(0).getTextContent());
    assertEquals(1, count(d, "Document"));
    assertEquals(0, count(d, "NetworkLink"));
  }

  @Test
  public void longitudeFirstGroundClampedNoInventedAltitude() throws Exception {
    String xml =
        export(
            "test",
            point(31.12345678, 121.87654321, 1000, 0, true),
            point(31.2, 121.9, 2000, 0, true));
    Document d = parse(xml);
    assertEquals(1, count(d, "LineString"));
    assertEquals(2, count(d, "Point"));
    assertEquals(
        "clampToGround",
        d.getElementsByTagNameNS(Kml.NAMESPACE, "altitudeMode").item(0).getTextContent());
    assertEquals(
        "1", d.getElementsByTagNameNS(Kml.NAMESPACE, "tessellate").item(0).getTextContent());
    assertTrue(xml.contains("121.87654321,31.12345678"));
    assertFalse(xml.contains("123.456"));
    String[] coordinates =
        d.getElementsByTagNameNS(Kml.NAMESPACE, "coordinates")
            .item(0)
            .getTextContent()
            .trim()
            .split("\\s+");
    assertEquals(2, coordinates.length);
    for (String coordinate : coordinates) assertEquals(2, coordinate.split(",").length);
  }

  @Test
  public void singlePointIsPointNotInvalidOneVertexLine() throws Exception {
    Document d = parse(export("single", point(0, 0, 1000, 0, true)));
    assertEquals(2, count(d, "Point"));
    assertEquals(0, count(d, "LineString"));
  }

  @Test
  public void pauseAndRejectedPointPreserveSeparateSections() throws Exception {
    Document d =
        parse(
            export(
                "gaps",
                point(1, 2, 1000, 0, true),
                point(1.1, 2.1, 2000, 0, true),
                point(5, 6, 3000, 0, false),
                point(1.2, 2.2, 4000, 0, true),
                point(1.3, 2.3, 5000, 1, true),
                point(1.4, 2.4, 6000, 1, true)));
    assertEquals(5, count(d, "Placemark"));
    assertEquals(2, count(d, "LineString"));
    assertEquals(3, count(d, "Point"));
    assertFalse(d.getDocumentElement().getTextContent().contains("6.00000000,5.00000000"));
  }

  @Test
  public void invalidCoordinatesNeverEmittedAndBreakTheLine() throws Exception {
    String xml =
        export(
            "invalid",
            point(1, 2, 1000, 0, true),
            point(Double.NaN, 3, 2000, 0, true),
            point(1, 181, 3000, 0, true),
            point(1, 3, 4000, 0, true));
    assertFalse(xml.contains("NaN"));
    assertFalse(xml.contains("181.00000000"));
    assertEquals(4, count(parse(xml), "Point"));
  }

  @Test
  public void antimeridianAndMissingTimeNeverJoin() throws Exception {
    Document d =
        parse(
            export(
                "dateline",
                point(1, 179.9, 1000, 0, true),
                point(1, -179.9, 2000, 0, true),
                point(1, -179.8, 33000, 0, true),
                point(1, -179.7, 34000, 0, true)));
    assertEquals(5, count(d, "Placemark"));
    assertEquals(4, count(d, "Point"));
    assertEquals(1, count(d, "LineString"));
  }

  @Test
  public void emptyOrAllRejectedIsWellFormed() throws Exception {
    assertEquals(0, count(parse(export("empty")), "Placemark"));
    assertEquals(0, count(parse(export("rejected", point(1, 2, 0, 0, false))), "Placemark"));
  }

  @Test
  public void decimalSeparatorIsIndependentOfLocale() throws Exception {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      assertEquals("121.50000000,31.25000000\n", Kml.coordinate(point(31.25, 121.5, 1, 0, true)));
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  public void removesXmlControlsButPreservesValidWhitespace() throws Exception {
    String name = "A" + (char) 1 + "B" + (char) 0 + "C\nD\tE";
    Document d = parse(export(name));
    assertEquals(
        "ABC\nD\tE", d.getElementsByTagNameNS(Kml.NAMESPACE, "name").item(0).getTextContent());
  }

  @Test
  public void fullTrackIsNotPreviewSampledAndFinishIsIdempotent() throws Exception {
    StringWriter out = new StringWriter();
    Kml.Exporter exporter = new Kml.Exporter(out, "long");
    for (int i = 0; i < 6001; i++) exporter.add(point(31 + i * .000001, 121, 1000L * i, 0, true));
    exporter.finish();
    String complete = out.toString();
    exporter.finish();
    assertEquals(complete, out.toString());
    Document d = parse(complete);
    assertEquals(1, count(d, "LineString"));
    assertEquals(
        6001,
        d.getElementsByTagNameNS(Kml.NAMESPACE, "coordinates")
            .item(0)
            .getTextContent()
            .trim()
            .split("\\s+")
            .length);
  }

  @Test
  public void endpointsUseFirstAndLastValidPointsNotRejectedEdges() throws Exception {
    String xml =
        export(
            "endpoints",
            point(2, 3, 0, 0, false),
            point(31, 121, 1000, 0, true),
            point(31.1, 121.1, 2000, 0, true),
            point(31.2, 121.2, 3000, 1, true),
            point(5, 6, 4000, 1, false));
    Document d = parse(xml);
    NodeList marks = d.getElementsByTagNameNS(Kml.NAMESPACE, "Placemark");
    int endpoints = 0;
    for (int i = 0; i < marks.getLength(); i++) {
      Element mark = (Element) marks.item(i);
      if (!mark.getAttribute("id").startsWith("track-")) continue;
      endpoints++;
      String expected = mark.getAttribute("id").equals("track-start") ? "起点" : "终点";
      assertEquals(
          expected, mark.getElementsByTagNameNS(Kml.NAMESPACE, "name").item(0).getTextContent());
      String coord =
          mark.getElementsByTagNameNS(Kml.NAMESPACE, "coordinates").item(0).getTextContent().trim();
      assertEquals(
          expected.equals("起点") ? "121.00000000,31.00000000" : "121.20000000,31.20000000", coord);
      assertEquals(1, mark.getElementsByTagNameNS(Kml.NAMESPACE, "IconStyle").getLength());
      assertEquals(0, mark.getElementsByTagNameNS(Kml.NAMESPACE, "href").getLength());
    }
    assertEquals(2, endpoints);
  }

  @Test
  public void oneValidPointHasCombinedEndpointAndEmptyHasNone() throws Exception {
    String xml = export("one", point(31, 121, 0, 0, true));
    assertTrue(xml.contains("id=\"track-start-end\""));
    assertTrue(xml.contains("起点 / 终点"));
    assertFalse(export("empty").contains("id=\"track-"));
  }

  @Test
  public void formatSelectionRestoresWithLegacyAndUnknownFallback() {
    assertEquals(TrackExport.KML, TrackExport.restore("KML", false));
    assertEquals(TrackExport.CSV, TrackExport.restore(null, true));
    assertEquals(TrackExport.GPX, TrackExport.restore("future", false));
    assertEquals("application/vnd.google-earth.kml+xml", TrackExport.KML.mimeType);
    assertEquals(".kml", TrackExport.KML.extension);
  }
}

package com.meteorsss.startrace;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TrackGeometryTest {
  private TrackPoint p(double lon, int segment, boolean valid) {
    return new TrackPoint(30, lon, 10, 3, 1, 0, 0, segment, valid);
  }

  @Test
  public void breaksArePreserved() {
    assertEquals(
        2,
        TrackGeometry.segments(Arrays.asList(p(110, 0, true), p(111, 0, true), p(112, 1, true)))
            .size());
  }

  @Test
  public void noDateLineBridge() {
    assertEquals(
        2, TrackGeometry.segments(Arrays.asList(p(179, 0, true), p(-179, 0, true))).size());
  }

  @Test
  public void rejectedPointDoesNotGetConnected() {
    assertEquals(
        2,
        TrackGeometry.segments(Arrays.asList(p(110, 0, true), p(111, 0, false), p(112, 0, true)))
            .size());
  }

  @Test
  public void accuracyRingHasCorrectRadius() {
    List<double[]> ring = TrackGeometry.accuracyRing(30, 110, 20);
    assertEquals(49, ring.size());
    for (double[] point : ring) assertEquals(20, Geo.distance(30, 110, point[0], point[1]), .001);
    assertEquals(ring.get(0)[0], ring.get(48)[0], .000001);
  }

  @Test
  public void invalidRadiusHasNoCircle() {
    assertTrue(TrackGeometry.accuracyRing(30, 110, Double.NaN).isEmpty());
    assertTrue(TrackGeometry.accuracyRing(30, 110, -1).isEmpty());
  }
}

package com.meteorsss.startrace;

import java.util.*;

/** Pure geometry: preserve breaks and never join across the antimeridian. Input stays WGS84. */
public final class TrackGeometry {
  public static List<List<TrackPoint>> segments(List<TrackPoint> points) {
    List<List<TrackPoint>> out = new ArrayList<>();
    List<TrackPoint> current = null;
    TrackPoint previous = null;
    for (TrackPoint p : points) {
      if (!p.accepted || !Geo.valid(p.lat, p.lon)) {
        previous = null;
        current = null;
        continue;
      }
      if (previous == null
          || p.segment != previous.segment
          || Math.abs(p.lon - previous.lon) > 180) {
        current = new ArrayList<>();
        out.add(current);
      }
      current.add(p);
      previous = p;
    }
    return out;
  }

  public static List<double[]> accuracyRing(double lat, double lon, double radius) {
    if (!Geo.valid(lat, lon) || !Double.isFinite(radius) || radius < 0 || radius > 10000)
      return Collections.emptyList();
    List<double[]> ring = new ArrayList<>();
    double distance = radius / 6371008.8,
        latitude = Math.toRadians(lat),
        longitude = Math.toRadians(lon);
    for (int i = 0; i <= 48; i++) {
      double bearing = 2 * Math.PI * i / 48,
          la =
              Math.asin(
                  Math.sin(latitude) * Math.cos(distance)
                      + Math.cos(latitude) * Math.sin(distance) * Math.cos(bearing));
      double lo =
          longitude
              + Math.atan2(
                  Math.sin(bearing) * Math.sin(distance) * Math.cos(latitude),
                  Math.cos(distance) - Math.sin(latitude) * Math.sin(la));
      ring.add(new double[] {Math.toDegrees(la), ((Math.toDegrees(lo) + 540) % 360) - 180});
    }
    return ring;
  }

  private TrackGeometry() {}
}

package com.meteorsss.startrace;

public final class TrackPoint {
  public final double lat,
      lon,
      altitude,
      accuracy,
      speed,
      verticalAccuracy,
      mslAltitude,
      mslAccuracy,
      speedAccuracy;
  public final long time, elapsed;
  public final int segment;
  public final boolean accepted;

  public TrackPoint(
      double lat,
      double lon,
      double altitude,
      double accuracy,
      double speed,
      long time,
      long elapsed,
      int segment,
      boolean accepted) {
    this(
        lat,
        lon,
        altitude,
        accuracy,
        speed,
        time,
        elapsed,
        segment,
        accepted,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        Double.NaN);
  }

  public TrackPoint(
      double lat,
      double lon,
      double altitude,
      double accuracy,
      double speed,
      long time,
      long elapsed,
      int segment,
      boolean accepted,
      double verticalAccuracy,
      double mslAltitude,
      double mslAccuracy,
      double speedAccuracy) {
    this.lat = lat;
    this.lon = lon;
    this.altitude = altitude;
    this.accuracy = accuracy;
    this.speed = speed;
    this.time = time;
    this.elapsed = elapsed;
    this.segment = segment;
    this.accepted = accepted;
    this.verticalAccuracy = verticalAccuracy;
    this.mslAltitude = mslAltitude;
    this.mslAccuracy = mslAccuracy;
    this.speedAccuracy = speedAccuracy;
  }
}

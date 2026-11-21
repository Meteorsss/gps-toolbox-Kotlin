package com.meteorsss.startrace;

import java.util.*;

/** Streaming analysis of all raw points, not the downsampled map preview. */
public final class TrackAnalytics {
  public static final double MOVING_MPS = 0.8, MAX_VERTICAL_ERROR = 15;

  public static final class Result {
    public long activeMs, movingMs, stationaryMs, observedMs, unknownMs;
    public double distance,
        movingDistance,
        maxSpeed = Double.NaN,
        ascent = Double.NaN,
        descent = Double.NaN,
        minHeight = Double.NaN,
        maxHeight = Double.NaN;
    public int raw, accepted, sections, heightPoints;
    public boolean msl;

    public double averageKmh() {
      return activeMs > 0 ? distance / (activeMs / 1000.0) * 3.6 : Double.NaN;
    }

    public double movingKmh() {
      return movingMs > 0 ? movingDistance / (movingMs / 1000.0) * 3.6 : Double.NaN;
    }

    public double validPercent() {
      return raw > 0 ? 100.0 * accepted / raw : Double.NaN;
    }
  }

  private final Result result = new Result();
  private TrackPoint previous;
  private final double[] speeds = new double[3];
  private int speedCount, speedIndex;
  private final HeightFilter ellipsoid = new HeightFilter(), msl = new HeightFilter();

  public void add(TrackPoint p) {
    result.raw++;
    if (!p.accepted) {
      previous = null;
      speedCount = 0;
      ellipsoid.breakSegment();
      msl.breakSegment();
      return;
    }
    result.accepted++;
    boolean connected =
        previous != null
            && previous.segment == p.segment
            && p.elapsed > previous.elapsed
            && p.elapsed - previous.elapsed <= 30000;
    if (!connected) {
      result.sections++;
      speedCount = 0;
      ellipsoid.breakSegment();
      msl.breakSegment();
    }
    boolean reliableSpeed =
        Double.isFinite(p.speed)
            && p.speed >= 0
            && (!Double.isFinite(p.speedAccuracy) || p.speedAccuracy <= 3);
    if (reliableSpeed) {
      speeds[speedIndex] = p.speed;
      speedIndex = (speedIndex + 1) % 3;
      if (speedCount < 3) speedCount++;
      if (speedCount == 3) {
        double[] sorted = speeds.clone();
        Arrays.sort(sorted);
        result.maxSpeed =
            Double.isFinite(result.maxSpeed) ? Math.max(result.maxSpeed, sorted[1]) : sorted[1];
      }
    } else speedCount = 0;
    if (connected) {
      long dt = p.elapsed - previous.elapsed;
      result.observedMs += dt;
      boolean previousReliable =
          Double.isFinite(previous.speed)
              && previous.speed >= 0
              && (!Double.isFinite(previous.speedAccuracy) || previous.speedAccuracy <= 3);
      if (reliableSpeed && previousReliable) {
        if (p.speed >= MOVING_MPS && previous.speed >= MOVING_MPS) {
          result.movingMs += dt;
          result.movingDistance += (p.speed + previous.speed) * 0.5 * dt / 1000.0;
        } else if (p.speed < MOVING_MPS && previous.speed < MOVING_MPS) result.stationaryMs += dt;
      }
    }
    ellipsoid.add(p.altitude, p.verticalAccuracy, p.elapsed);
    msl.add(p.mslAltitude, p.mslAccuracy, p.elapsed);
    previous = p;
  }

  public Result finish(double recordedDistance, long activeMs) {
    Result out = new Result();
    out.distance = Math.max(0, recordedDistance);
    out.activeMs = Math.max(0, activeMs);
    out.raw = result.raw;
    out.accepted = result.accepted;
    out.sections = result.sections;
    out.maxSpeed = result.maxSpeed;
    out.observedMs = Math.min(out.activeMs, result.observedMs);
    out.movingMs = Math.min(out.activeMs, result.movingMs);
    out.stationaryMs = Math.min(Math.max(0, out.activeMs - out.movingMs), result.stationaryMs);
    out.movingDistance = result.movingDistance;
    out.unknownMs = Math.max(0, out.activeMs - out.movingMs - out.stationaryMs);
    HeightFilter selected = msl.count >= 3 && msl.count >= ellipsoid.count * 0.8 ? msl : ellipsoid;
    out.msl = selected == msl;
    out.heightPoints = selected.count;
    if (selected.filteredCount >= 2) {
      out.ascent = selected.up;
      out.descent = selected.down;
      out.minHeight = selected.minimum;
      out.maxHeight = selected.maximum;
    }
    return out;
  }

  private static final class HeightFilter {
    private final double[] window = new double[5];
    private int size, index;
    private long lastTime;
    private double lastRaw = Double.NaN, anchor = Double.NaN, anchorError, filtered = Double.NaN;
    int count, filteredCount;
    double up, down, minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;

    void breakSegment() {
      size = 0;
      index = 0;
      lastTime = 0;
      lastRaw = Double.NaN;
      anchor = Double.NaN;
      filtered = Double.NaN;
    }

    void add(double height, double error, long elapsed) {
      if (!Double.isFinite(height)
          || !Double.isFinite(error)
          || error < 0
          || error > MAX_VERTICAL_ERROR) {
        breakSegment();
        return;
      }
      if (lastTime > 0 && (elapsed <= lastTime || elapsed - lastTime > 30000)) {
        breakSegment();
      }
      if (Double.isFinite(lastRaw)
          && Math.abs(height - lastRaw) > Math.max(20, 8 * (elapsed - lastTime) / 1000.0)) {
        breakSegment();
        return;
      }
      count++;
      lastRaw = height;
      lastTime = elapsed;
      window[index] = height;
      index = (index + 1) % 5;
      if (size < 5) size++;
      if (size < 3) return;
      double[] values = Arrays.copyOf(window, size);
      Arrays.sort(values);
      double median = values[size / 2];
      filtered = Double.isFinite(filtered) ? filtered + 0.45 * (median - filtered) : median;
      filteredCount++;
      minimum = Math.min(minimum, filtered);
      maximum = Math.max(maximum, filtered);
      if (!Double.isFinite(anchor)) {
        anchor = filtered;
        anchorError = error;
        return;
      }
      double threshold = Math.max(4, Math.max(anchorError, error) * 1.5),
          change = filtered - anchor;
      if (Math.abs(change) >= threshold) {
        if (change > 0) up += change;
        else down -= change;
        anchor = filtered;
        anchorError = error;
      }
    }
  }
}

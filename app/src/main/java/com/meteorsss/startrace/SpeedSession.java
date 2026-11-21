package com.meteorsss.startrace;

/**
 * In-memory foreground speedometer session: no recorder, database, saved-point cadence or history.
 */
public final class SpeedSession {
  public static final class Snapshot {
    public final double distance, maxMps, averageMps;
    public final long activeMs;
    public final int validPoints;

    private Snapshot(
        double distance, double maxMps, double averageMps, long activeMs, int validPoints) {
      this.distance = distance;
      this.maxMps = maxMps;
      this.averageMps = averageMps;
      this.activeMs = activeMs;
      this.validPoints = validPoints;
    }
  }

  private long activeSince, accumulated, notBefore, lastElapsed = -1;
  private boolean visible = true;
  private int validPoints;
  private double distance, maxMps = Double.NaN;
  private TrackPoint previous, anchor;

  public SpeedSession(long nowElapsed) {
    activeSince = notBefore = nowElapsed;
  }

  public void add(TrackPoint p, boolean mock, long nowElapsed) {
    if (!visible || p.elapsed < notBefore || p.elapsed <= lastElapsed) return;
    if (p.elapsed > nowElapsed) {
      disconnect();
      return;
    }
    lastElapsed = p.elapsed;
    if (mock
        || !p.accepted
        || !TrackFilter.usable(p, nowElapsed)
        || !TrackFilter.plausible(previous, p)) {
      disconnect();
      return;
    }
    if (previous != null && p.elapsed - previous.elapsed > 30000) disconnect();
    double step = TrackFilter.distanceContribution(anchor, p);
    distance += step;
    if (anchor == null || step > 0 || p.elapsed - anchor.elapsed > 30000) anchor = p;
    previous = p;
    validPoints++;
    if (Double.isFinite(p.speed)
        && p.speed >= 0
        && (!Double.isFinite(p.speedAccuracy) || (p.speedAccuracy >= 0 && p.speedAccuracy <= 3)))
      maxMps = Double.isFinite(maxMps) ? Math.max(maxMps, p.speed) : p.speed;
  }

  /** Missing, rejected or background positions must not form a fictitious connecting leg. */
  public void disconnect() {
    previous = null;
    anchor = null;
  }

  public void pause(long nowElapsed) {
    if (!visible) return;
    accumulated += Math.max(0, nowElapsed - activeSince);
    visible = false;
    disconnect();
  }

  public void resume(long nowElapsed) {
    if (visible) return;
    visible = true;
    activeSince = notBefore = nowElapsed;
    disconnect();
  }

  public Snapshot snapshot(long nowElapsed) {
    long activeMs = accumulated + (visible ? Math.max(0, nowElapsed - activeSince) : 0);
    double average = validPoints >= 2 && activeMs > 0 ? distance / (activeMs / 1000.0) : Double.NaN;
    return new Snapshot(distance, maxMps, average, activeMs, validPoints);
  }
}

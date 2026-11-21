package com.meteorsss.startrace;

import static org.junit.Assert.*;

import org.junit.Test;

public class SpeedSessionTest {
  private TrackPoint point(long elapsed, double lat, double speed) {
    return new TrackPoint(
        lat,
        121,
        Double.NaN,
        3,
        speed,
        1700000000000L + elapsed,
        elapsed,
        0,
        true,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        .1);
  }

  private void add(SpeedSession session, long elapsed, double lat, double speed) {
    session.add(point(elapsed, lat, speed), false, elapsed);
  }

  @Test
  public void enteringIsFreshAndWaitingIsNotARecorderDependency() {
    SpeedSession.Snapshot s = new SpeedSession(1000).snapshot(3000);
    assertEquals(0, s.distance, 0);
    assertEquals(0, s.validPoints);
    assertTrue(Double.isNaN(s.maxMps));
    assertTrue(Double.isNaN(s.averageMps));
    assertEquals(2000, s.activeMs);
  }

  @Test
  public void firstFreshSpeedProducesAnIndependentPeak() {
    SpeedSession session = new SpeedSession(1000);
    add(session, 1000, 31, 5);
    SpeedSession.Snapshot s = session.snapshot(2000);
    assertEquals(5, s.maxMps, 0);
    assertEquals(18, s.maxMps * 3.6, .001);
    assertTrue(Double.isNaN(s.averageMps));
    assertEquals(1, s.validPoints);
  }

  @Test
  public void distanceAndAverageUseOnlyThisSession() {
    SpeedSession session = new SpeedSession(1000);
    add(session, 1000, 31, 12);
    add(session, 2000, 31.0001, 12);
    double expected = Geo.distance(31, 121, 31.0001, 121);
    SpeedSession.Snapshot s = session.snapshot(3000);
    assertEquals(expected, s.distance, .001);
    assertEquals(expected / 2, s.averageMps, .001);
    assertEquals(12, s.maxMps, 0);
  }

  @Test
  public void repeatedRefreshesNeverDoubleCountOneFix() {
    SpeedSession session = new SpeedSession(1000);
    add(session, 1000, 31, 1);
    session.add(point(1000, 32, 100), false, 1500);
    assertEquals(1, session.snapshot(2000).validPoints);
    assertEquals(1, session.snapshot(2000).maxMps, 0);
  }

  @Test
  public void preEntryFixIsNotPartOfNewSession() {
    SpeedSession session = new SpeedSession(5000);
    session.add(point(4000, 31, 20), false, 5000);
    assertEquals(0, session.snapshot(5000).validPoints);
    add(session, 6000, 32, 2);
    assertEquals(0, session.snapshot(6000).distance, 0);
    assertEquals(2, session.snapshot(6000).maxMps, 0);
  }

  @Test
  public void mockPositionNeitherCountsNorConnectsAcrossBreak() {
    SpeedSession session = new SpeedSession(1000);
    add(session, 1000, 31, 2);
    session.add(point(2000, 31.0001, 100), true, 2000);
    add(session, 3000, 31.0002, 2);
    assertEquals(0, session.snapshot(3000).distance, 0);
    assertEquals(2, session.snapshot(3000).maxMps, 0);
    assertEquals(2, session.snapshot(3000).validPoints);
  }

  @Test
  public void lowAccuracyPositionDoesNotCreateDistanceOrPeak() {
    SpeedSession session = new SpeedSession(1000);
    TrackPoint poor = new TrackPoint(31, 121, 0, 100, 40, 0, 1000, 0, true);
    session.add(poor, false, 1000);
    assertEquals(0, session.snapshot(2000).validPoints);
    assertTrue(Double.isNaN(session.snapshot(2000).maxMps));
  }

  @Test
  public void unreliableSpeedDoesNotBecomeMaximum() {
    SpeedSession session = new SpeedSession(1000);
    session.add(
        new TrackPoint(
            31, 121, 0, 3, 100, 0, 1000, 0, true, Double.NaN, Double.NaN, Double.NaN, 20),
        false,
        1000);
    assertTrue(Double.isNaN(session.snapshot(1000).maxMps));
    add(session, 2000, 31.0001, 2);
    assertEquals(2, session.snapshot(2000).maxMps, 0);
  }

  @Test
  public void staleAndFutureFixesAreIgnoredWithoutPoisoningNextFix() {
    SpeedSession session = new SpeedSession(1000);
    session.add(point(1000, 31, 20), false, 20000);
    session.add(point(50000, 32, 20), false, 20000);
    add(session, 21000, 31, 2);
    assertEquals(1, session.snapshot(21000).validPoints);
    assertEquals(0, session.snapshot(21000).distance, 0);
    assertEquals(2, session.snapshot(21000).maxMps, 0);
  }

  @Test
  public void implausibleJumpIsNotMeasuredAsMovement() {
    SpeedSession session = new SpeedSession(1000);
    add(session, 1000, 31, 2);
    add(session, 2000, 32, 2);
    assertEquals(1, session.snapshot(2000).validPoints);
    assertEquals(0, session.snapshot(2000).distance, 0);
  }

  @Test
  public void gpsGapOrExplicitDisconnectHasNoBridge() {
    SpeedSession session = new SpeedSession(1000);
    add(session, 1000, 31, 2);
    add(session, 41000, 31.001, 2);
    assertEquals(0, session.snapshot(41000).distance, 0);
    session.disconnect();
    add(session, 42000, 31.0011, 2);
    assertEquals(0, session.snapshot(42000).distance, 0);
    add(session, 43000, 31.0012, 2);
    assertTrue(session.snapshot(43000).distance > 0);
  }

  @Test
  public void backgroundTimeAndMovementAreNotCounted() {
    SpeedSession session = new SpeedSession(1000);
    add(session, 1000, 31, 2);
    add(session, 2000, 31.0001, 2);
    double distance = session.snapshot(2000).distance;
    session.pause(3000);
    session.pause(4000);
    add(session, 5000, 31.0005, 30);
    assertEquals(2000, session.snapshot(10000).activeMs);
    session.resume(10000);
    session.resume(11000);
    session.add(point(9000, 31.0009, 20), false, 10000);
    add(session, 11000, 31.001, 2);
    assertEquals(distance, session.snapshot(11000).distance, 0);
    assertEquals(3000, session.snapshot(11000).activeMs);
    assertEquals(2, session.snapshot(11000).maxMps, 0);
  }

  @Test
  public void newSessionHasNoPreviousTotals() {
    SpeedSession previous = new SpeedSession(1000);
    add(previous, 1000, 31, 8);
    add(previous, 2000, 31.0001, 8);
    assertTrue(previous.snapshot(2000).distance > 0);
    SpeedSession fresh = new SpeedSession(3000);
    assertEquals(0, fresh.snapshot(3000).distance, 0);
    assertEquals(0, fresh.snapshot(3000).validPoints);
    assertTrue(Double.isNaN(fresh.snapshot(3000).maxMps));
  }

  @Test
  public void stationaryValidPositionsReportZeroNotUnknown() {
    SpeedSession session = new SpeedSession(1000);
    add(session, 1000, 31, 0);
    add(session, 2000, 31, 0);
    SpeedSession.Snapshot s = session.snapshot(3000);
    assertEquals(0, s.distance, 0);
    assertEquals(0, s.maxMps, 0);
    assertEquals(0, s.averageMps, 0);
    assertTrue(new SpeedSession(5000).snapshot(4000).activeMs >= 0);
  }
}

package com.meteorsss.startrace;

import static org.junit.Assert.*;

import org.junit.Test;

public class TrackAnalyticsTest {
  private TrackPoint p(
      int t, double height, double vAcc, double speed, int segment, boolean accepted) {
    return new TrackPoint(
        30 + t * .00001,
        110,
        height,
        3,
        speed,
        t * 1000L,
        t * 1000L,
        segment,
        accepted,
        vAcc,
        Double.NaN,
        Double.NaN,
        0.1);
  }

  @Test
  public void noDataIsNotZeroHeight() {
    TrackAnalytics.Result r = new TrackAnalytics().finish(0, 10000);
    assertTrue(Double.isNaN(r.ascent));
    assertTrue(Double.isNaN(r.maxSpeed));
    assertEquals(10000, r.unknownMs);
  }

  @Test
  public void averageExcludesUserPauseViaActiveDuration() {
    TrackAnalytics.Result r = new TrackAnalytics().finish(1000, 100000);
    assertEquals(36, r.averageKmh(), .001);
  }

  @Test
  public void constantMovingSpeedAndTime() {
    TrackAnalytics a = new TrackAnalytics();
    for (int i = 1; i <= 11; i++) a.add(p(i, 10, 2, 2, 0, true));
    TrackAnalytics.Result r = a.finish(20, 10000);
    assertEquals(10000, r.movingMs);
    assertEquals(7.2, r.movingKmh(), .001);
    assertEquals(2, r.maxSpeed, 0);
    assertEquals(0, r.unknownMs);
  }

  @Test
  public void stationaryJitterDoesNotBecomeClimb() {
    TrackAnalytics a = new TrackAnalytics();
    for (int i = 1; i <= 100; i++) a.add(p(i, 100 + (i % 2 == 0 ? .8 : -.8), 3, 0, 0, true));
    TrackAnalytics.Result r = a.finish(0, 99000);
    assertEquals(0, r.ascent, 0);
    assertEquals(0, r.descent, 0);
    assertEquals(99000, r.stationaryMs);
  }

  @Test
  public void ascentAndDescentAreFiltered() {
    TrackAnalytics a = new TrackAnalytics();
    for (int i = 1; i <= 101; i++)
      a.add(p(i, 100 + (i <= 51 ? i - 1 : 101 - i) * 2, 2, 2, 0, true));
    TrackAnalytics.Result r = a.finish(200, 100000);
    assertTrue(r.ascent > 85 && r.ascent < 105);
    assertTrue(r.descent > 75 && r.descent < 105);
  }

  @Test
  public void missingVerticalQualityIsUnknown() {
    TrackAnalytics a = new TrackAnalytics();
    for (int i = 1; i < 20; i++) a.add(p(i, i * 10, Double.NaN, 1, 0, true));
    assertTrue(Double.isNaN(a.finish(20, 18000).ascent));
  }

  @Test
  public void poorVerticalQualityIsUnknown() {
    TrackAnalytics a = new TrackAnalytics();
    for (int i = 1; i < 20; i++) a.add(p(i, i * 2, 80, 1, 0, true));
    assertTrue(Double.isNaN(a.finish(20, 18000).ascent));
  }

  @Test
  public void segmentBreakDoesNotCountHeightJump() {
    TrackAnalytics a = new TrackAnalytics();
    for (int i = 1; i <= 20; i++) a.add(p(i, i <= 10 ? 10 : 200, 2, 1, i <= 10 ? 0 : 1, true));
    TrackAnalytics.Result r = a.finish(20, 19000);
    assertEquals(0, r.ascent, 0);
    assertEquals(2, r.sections);
    assertEquals(18000, r.movingMs);
  }

  @Test
  public void gapDoesNotCountMissingTravel() {
    TrackAnalytics a = new TrackAnalytics();
    a.add(p(1, 10, 2, 2, 0, true));
    a.add(p(60, 200, 2, 2, 0, true));
    TrackAnalytics.Result r = a.finish(0, 59000);
    assertEquals(0, r.movingMs);
    assertEquals(59000, r.unknownMs);
  }

  @Test
  public void rejectedPointsBreakContinuity() {
    TrackAnalytics a = new TrackAnalytics();
    a.add(p(1, 10, 2, 2, 0, true));
    a.add(p(2, 20, 2, 2, 0, false));
    a.add(p(3, 30, 2, 2, 0, true));
    TrackAnalytics.Result r = a.finish(0, 2000);
    assertEquals(3, r.raw);
    assertEquals(2, r.accepted);
    assertEquals(0, r.movingMs);
  }

  @Test
  public void medianRejectsSingleSpeedSpike() {
    TrackAnalytics a = new TrackAnalytics();
    double[] speeds = {2, 2, 90, 2, 2};
    for (int i = 0; i < speeds.length; i++) a.add(p(i + 1, 10, 2, speeds[i], 0, true));
    assertEquals(2, a.finish(10, 4000).maxSpeed, 0);
  }

  @Test
  public void mslAndEllipsoidNeverMix() {
    TrackAnalytics a = new TrackAnalytics();
    for (int i = 1; i < 20; i++)
      a.add(new TrackPoint(30, 110, 150, 3, 2, i * 1000, i * 1000, 0, true, 2, 100, 2, .1));
    TrackAnalytics.Result r = a.finish(30, 18000);
    assertTrue(r.msl);
    assertEquals(100, r.minHeight, 0);
    assertEquals(0, r.ascent, 0);
  }

  @Test
  public void snapshotsDoNotMutateAccumulator() {
    TrackAnalytics a = new TrackAnalytics();
    for (int i = 1; i <= 5; i++) a.add(p(i, 10, 2, 2, 0, true));
    a.finish(5, 1000);
    assertEquals(4000, a.finish(8, 4000).movingMs);
  }
}

package com.meteorsss.startrace;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecordingCadenceTest {
  @Test
  public void supportedIntervalsAndInvalidFallback() {
    assertArrayEquals(new int[] {1, 2, 3, 5, 10, 15}, RecordingCadence.SECONDS);
    for (int value : RecordingCadence.SECONDS)
      assertEquals(value, RecordingCadence.sanitize(value));
    assertEquals(1, RecordingCadence.sanitize(0));
    assertEquals(1, RecordingCadence.sanitize(30));
  }

  @Test
  public void everyIntervalThrottlesWithoutInventingPoints() {
    for (int value : RecordingCadence.SECONDS) {
      RecordingCadence cadence = new RecordingCadence();
      assertTrue(cadence.shouldSave(1000, value));
      assertFalse(cadence.shouldSave(1000 + value * 1000L - 1, value));
      assertTrue(cadence.shouldSave(1000 + value * 1000L, value));
      assertFalse(cadence.shouldSave(1000 + value * 1000L, value));
    }
  }

  @Test
  public void missingFixesSkipSlotsNotDuplicateLastLocation() {
    RecordingCadence cadence = new RecordingCadence();
    assertTrue(cadence.shouldSave(0, 3));
    assertTrue(cadence.shouldSave(10000, 3));
    assertFalse(cadence.shouldSave(12000, 3));
    assertTrue(cadence.shouldSave(14000, 3));
  }

  @Test
  public void resetForResumeOrChangeAllowsFirstNewFix() {
    RecordingCadence cadence = new RecordingCadence();
    assertTrue(cadence.shouldSave(1000, 10));
    assertFalse(cadence.shouldSave(2000, 10));
    cadence.reset();
    assertTrue(cadence.shouldSave(2000, 1));
  }

  @Test
  public void timestampsMustBeMonotonicAndNonnegative() {
    RecordingCadence cadence = new RecordingCadence();
    assertFalse(cadence.shouldSave(-1, 1));
    assertTrue(cadence.shouldSave(5000, 1));
    assertFalse(cadence.shouldSave(4000, 1));
    assertTrue(cadence.shouldSave(6000, 1));
  }
}

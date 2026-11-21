package com.meteorsss.startrace;

/**
 * Monotonic location timestamps control saved points, not wall-clock timers or fabricated fixes.
 */
public final class RecordingCadence {
  public static final int[] SECONDS = {1, 2, 3, 5, 10, 15};
  private long lastSaved = -1;

  public static int sanitize(int seconds) {
    for (int value : SECONDS) if (value == seconds) return seconds;
    return 1;
  }

  public boolean shouldSave(long elapsedMs, int seconds) {
    if (elapsedMs < 0 || (lastSaved >= 0 && elapsedMs <= lastSaved)) return false;
    if (lastSaved >= 0 && elapsedMs - lastSaved < sanitize(seconds) * 1000L) return false;
    lastSaved = elapsedMs;
    return true;
  }

  public void reset() {
    lastSaved = -1;
  }
}

package com.meteorsss.startrace;

import static org.junit.Assert.*;

import org.junit.Test;

public class ThemeModeTest {
  @Test
  public void manualModesIgnoreSystem() {
    assertFalse(ThemeMode.DARK.isLight(false));
    assertFalse(ThemeMode.DARK.isLight(true));
    assertTrue(ThemeMode.LIGHT.isLight(false));
    assertTrue(ThemeMode.LIGHT.isLight(true));
  }

  @Test
  public void systemModeFollowsAppearance() {
    assertTrue(ThemeMode.SYSTEM.isLight(false));
    assertFalse(ThemeMode.SYSTEM.isLight(true));
  }

  @Test
  public void legacyManualChoiceIsPreserved() {
    assertEquals(ThemeMode.LIGHT, ThemeMode.restore(null, true, true));
    assertEquals(ThemeMode.DARK, ThemeMode.restore(null, true, false));
  }

  @Test
  public void explicitModeWinsAndNewInstallFollowsSystem() {
    assertEquals(ThemeMode.SYSTEM, ThemeMode.restore("SYSTEM", true, false));
    assertEquals(ThemeMode.LIGHT, ThemeMode.restore("LIGHT", true, false));
    assertEquals(ThemeMode.SYSTEM, ThemeMode.restore(null, false, false));
    assertEquals(ThemeMode.SYSTEM, ThemeMode.restore("unknown", false, false));
  }
}

package com.meteorsss.startrace;

/** Explicit modes rather than persisting the current system appearance. */
public enum ThemeMode {
  DARK("手动深色"),
  LIGHT("手动浅色"),
  SYSTEM("跟随系统");
  public final String label;

  ThemeMode(String label) {
    this.label = label;
  }

  public boolean isLight(boolean systemDark) {
    return this == LIGHT || (this == SYSTEM && !systemDark);
  }

  public static ThemeMode restore(String value, boolean hasLegacy, boolean legacyLight) {
    if (value != null) {
      try {
        return valueOf(value);
      } catch (IllegalArgumentException ignored) {
      }
    }
    return hasLegacy ? (legacyLight ? LIGHT : DARK) : SYSTEM;
  }
}

package com.meteorsss.startrace;

/** Formats selected by the user; raw measurements are only included in CSV. */
public enum TrackExport {
  GPX("application/gpx+xml", ".gpx"),
  KML("application/vnd.google-earth.kml+xml", ".kml"),
  CSV("text/csv", ".csv");
  public final String mimeType, extension;

  TrackExport(String mimeType, String extension) {
    this.mimeType = mimeType;
    this.extension = extension;
  }

  public static TrackExport restore(String name, boolean legacyRaw) {
    if (name != null) {
      try {
        return valueOf(name);
      } catch (IllegalArgumentException ignored) {
      }
    }
    return legacyRaw ? CSV : GPX;
  }
}

package com.meteorsss.startrace

/** Formats selected by the user; raw measurements are only included in CSV.  */
enum class TrackExport(val mimeType: String, val extension: String) {
    GPX("application/gpx+xml", ".gpx"),
    KML("application/vnd.google-earth.kml+xml", ".kml"),
    CSV("text/csv", ".csv");

    companion object {
        fun restore(name: String, legacyRaw: Boolean): TrackExport {
            if (name != null) {
                try {
                    return valueOf(name)
                } catch (ignored: IllegalArgumentException) {
                }
            }
            return if (legacyRaw) CSV else GPX
        }
    }
}

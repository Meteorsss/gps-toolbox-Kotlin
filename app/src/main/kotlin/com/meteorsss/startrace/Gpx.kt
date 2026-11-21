package com.meteorsss.startrace

import java.lang.Double
import java.time.Instant
import java.util.*
import kotlin.String

object Gpx {
    fun escape(s: String): String? {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun header(name: String): String {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version=\"1.1\" creator=\"StarTrace\""
                + " xmlns=\"http://www.topografix.com/GPX/1/1\""
                + " xmlns:st=\"https://github.com/Meteorsss/gps-toolbox/xmlns/1\"><trk><name>"
                + escape(name)
                + "</name>\n")
    }

    fun point(p: TrackPoint): String? {
        val altitude: String? =
            if (Double.isFinite(p.altitude)) String.format(
                Locale.US,
                "<st:ellipsoidHeight>%.3f</st:ellipsoidHeight>",
                p.altitude
            ) else
                ""
        val speed: String? =
            if (Double.isFinite(p.speed)) String.format(Locale.US, "<st:speedMps>%.3f</st:speedMps>", p.speed) else
                ""
        return String.format(
            Locale.US,
            "<trkpt lat=\"%.8f\""
                    + " lon=\"%.8f\"><time>%s</time><extensions><st:accuracyMeters>%.2f</st:accuracyMeters>%s%s</extensions></trkpt>\n",
            p.lat,
            p.lon,
            Instant.ofEpochMilli(p.time),
            p.accuracy,
            altitude,
            speed
        )
    }

    fun footer(): String {
        return "</trk></gpx>\n"
    }
}

package com.meteorsss.startrace

import java.io.IOException
import java.io.Writer
import java.util.*
import java.util.function.IntConsumer
import kotlin.math.abs

/** Streaming KML 2.2: WGS84, ground-clamped geometries, no invented altitude or gap filling.  */
object Kml {
    const val NAMESPACE: String = "http://www.opengis.net/kml/2.2"

    fun escape(text: String?): String? {
        val clean: StringBuilder? = StringBuilder()
        if (text == null) return ""
        text.codePoints()
            .forEach(IntConsumer { c: Int ->
                // XML 1.0 valid characters only, including supplementary Unicode such as emoji.
                if (c == 9 || c == 10 || c == 13 || (c >= 32 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD)
                    || (c >= 0x10000 && c <= 0x10FFFF)
                ) clean!!.appendCodePoint(c)
            })
        return Gpx.escape(clean.toString())
    }

    fun coordinate(p: TrackPoint?): String? {
        return String.format(Locale.US, "%.8f,%.8f\n", p!!.lon, p.lat)
    }

    class Exporter(private val out: Writer, name: String?) {
        private var first: TrackPoint? = null
        private var previous: TrackPoint? = null
        private var trackStart: TrackPoint? = null
        private var trackEnd: TrackPoint? = null
        private var accepted: Long = 0
        private var line = false
        private var finished = false
        private var sections = 0

        init {
            out.write(
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<kml xmlns=\""
                        + NAMESPACE
                        + "\"><Document><name>"
                        + escape(name)
                        + "</name><description>WGS84 有效轨迹；保留断点，贴地显示，不使用椭球高作为海拔。"
                        + "逐点时间及测量值请使用 GPX / CSV 导出。</description>\n"
                        + "<Style id=\"track\"><LineStyle><color>ffaaba00</color><width>4</width>"
                        + "</LineStyle></Style>\n")
            )
        }

        @Throws(IOException::class)
        fun add(p: TrackPoint) {
            check(!finished) { "KML export already finished" }
            if (!p.accepted || !Geo.valid(p.lat, p.lon)) {
                endSection()
                return
            }
            if (previous != null
                && (p.segment != previous!!.segment || abs(p.lon - previous!!.lon) > 180 || p.elapsed <= previous!!.elapsed || p.elapsed - previous!!.elapsed > 30000)
            ) endSection()
            if (trackStart == null) trackStart = p
            trackEnd = p
            accepted++
            if (first == null) {
                first = p
            } else {
                if (!line) {
                    startPlacemark()
                    out.write(
                        "<LineString><tessellate>1</tessellate>"
                                + "<altitudeMode>clampToGround</altitudeMode><coordinates>\n"
                    )
                    out.write(coordinate(first))
                    line = true
                }
                out.write(coordinate(p))
            }
            previous = p
        }

        @Throws(IOException::class)
        private fun startPlacemark() {
            out.write("<Placemark><name>片段 " + (++sections) + "</name><styleUrl>#track</styleUrl>\n")
        }

        @Throws(IOException::class)
        private fun endSection() {
            if (first == null) return
            if (line) out.write("</coordinates></LineString></Placemark>\n")
            else {
                startPlacemark()
                out.write(
                    ("<Point><altitudeMode>clampToGround</altitudeMode><coordinates>"
                            + coordinate(first)
                            + "</coordinates></Point></Placemark>\n")
                )
            }
            first = null
            previous = null
            line = false
        }

        @Throws(IOException::class)
        private fun endpoint(id: String, name: String, color: String, point: TrackPoint?) {
            out.write(
                ("<Placemark id=\""
                        + id
                        + "\"><name>"
                        + name
                        + "</name><description>有效轨迹的"
                        + name
                        + "；WGS84，贴地显示。</description>"
                        + "<Style><IconStyle><color>"
                        + color
                        + "</color><scale>1.2</scale></IconStyle>"
                        + "<LabelStyle><color>"
                        + color
                        + "</color><scale>1.1</scale></LabelStyle></Style>"
                        + "<Point><altitudeMode>clampToGround</altitudeMode><coordinates>"
                        + coordinate(point)
                        + "</coordinates></Point></Placemark>\n")
            )
        }

        @Throws(IOException::class)
        fun finish() {
            if (finished) return
            endSection()
            if (accepted == 1L) endpoint("track-start-end", "起点 / 终点", "ff35abf5", trackStart)
            else if (accepted > 1) {
                endpoint("track-start", "起点", "ff35abf5", trackStart)
                endpoint("track-end", "终点", "ff755ceb", trackEnd)
            }
            out.write("</Document></kml>\n")
            finished = true
        }
    }
}

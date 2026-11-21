package com.meteorsss.startrace

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.meteorsss.startrace.Kml.Exporter
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.*
import kotlin.math.ceil
import kotlin.math.max

class Store internal constructor(context: Context?, name: String?) : SQLiteOpenHelper(context, name, null, 2) {
    class Track {
        var id: Long = 0
        var start: Long = 0
        var end: Long = 0
        var duration: Long = 0
        var name: String? = null
        var status: String? = null
        var distance: Double = 0.0
        var points: Int = 0
        var raw: Int = 0
    }

    class Place {
        var id: Long = 0
        var time: Long = 0
        var lat: Double = 0.0
        var lon: Double = 0.0
        var name: String? = null
        var note: String? = null
    }

    constructor(context: Context?) : this(context, "startrace.db")

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            ("CREATE TABLE tracks(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,start INTEGER"
                    + " NOT NULL,end INTEGER NOT NULL DEFAULT 0,duration INTEGER NOT NULL DEFAULT"
                    + " 0,distance REAL NOT NULL DEFAULT 0,status TEXT NOT NULL)")
        )
        db.execSQL(
            ("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT,track INTEGER NOT NULL REFERENCES"
                    + " tracks(id) ON DELETE CASCADE,lat REAL NOT NULL,lon REAL NOT NULL,altitude"
                    + " REAL,accuracy REAL,speed REAL,time INTEGER NOT NULL,elapsed INTEGER NOT"
                    + " NULL,segment INTEGER NOT NULL,accepted INTEGER NOT NULL,mock INTEGER NOT"
                    + " NULL,vertical_accuracy REAL,msl_altitude REAL,msl_accuracy REAL,speed_accuracy"
                    + " REAL)")
        )
        db.execSQL("CREATE INDEX points_track ON points(track,id)")
        db.execSQL(
            "CREATE TABLE places(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,note TEXT NOT"
                    + " NULL,lat REAL NOT NULL,lon REAL NOT NULL,time INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE points ADD COLUMN vertical_accuracy REAL")
            db.execSQL("ALTER TABLE points ADD COLUMN msl_altitude REAL")
            db.execSQL("ALTER TABLE points ADD COLUMN msl_accuracy REAL")
            db.execSQL("ALTER TABLE points ADD COLUMN speed_accuracy REAL")
        }
    }

    @Synchronized
    fun recoverInterrupted() {
        getWritableDatabase()
            .execSQL(
                "UPDATE tracks SET status='interrupted',end=COALESCE((SELECT MAX(time) FROM points"
                        + " WHERE track=tracks.id),start) WHERE status IN ('recording','paused')"
            )
    }

    @Synchronized
    fun begin(name: String?): Long {
        val v: ContentValues = ContentValues()
        v!!.put("name", name)
        v.put("start", System.currentTimeMillis())
        v.put("status", "recording")
        return getWritableDatabase().insertOrThrow("tracks", null, v)
    }

    @Synchronized
    fun update(id: Long, duration: Long, distance: Double, status: String?) {
        val v: ContentValues = ContentValues()
        v!!.put("duration", duration)
        v.put("distance", distance)
        v.put("status", status)
        if (status != "recording" && status != "paused") v.put("end", System.currentTimeMillis())
        getWritableDatabase().update("tracks", v, "id=?", arrayOf<String>("" + id))
    }

    @Synchronized
    fun add(id: Long, p: TrackPoint, mock: Boolean) {
        val v: ContentValues = ContentValues()
        v!!.put("track", id)
        v.put("lat", p.lat)
        v.put("lon", p.lon)
        if (java.lang.Double.isFinite(p.altitude)) v.put("altitude", p.altitude)
        if (java.lang.Double.isFinite(p.accuracy)) v.put("accuracy", p.accuracy)
        if (java.lang.Double.isFinite(p.speed)) v.put("speed", p.speed)
        v.put("time", p.time)
        v.put("elapsed", p.elapsed)
        v.put("segment", p.segment)
        v.put("accepted", if (p.accepted) 1 else 0)
        v.put("mock", if (mock) 1 else 0)
        if (java.lang.Double.isFinite(p.verticalAccuracy)) v.put("vertical_accuracy", p.verticalAccuracy)
        if (java.lang.Double.isFinite(p.mslAltitude)) v.put("msl_altitude", p.mslAltitude)
        if (java.lang.Double.isFinite(p.mslAccuracy)) v.put("msl_accuracy", p.mslAccuracy)
        if (java.lang.Double.isFinite(p.speedAccuracy)) v.put("speed_accuracy", p.speedAccuracy)
        getWritableDatabase().insertOrThrow("points", null, v)
    }

    @Synchronized
    fun tracks(): MutableList<Track> {
        val out: MutableList<Track> = ArrayList<Track>()
        getReadableDatabase()
            .rawQuery(
                ("SELECT t.*, (SELECT COUNT(*) FROM points p WHERE p.track=t.id AND accepted=1) AS"
                        + " good, (SELECT COUNT(*) FROM points p WHERE p.track=t.id) AS total FROM"
                        + " tracks t ORDER BY id DESC"),
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val t = Track()
                    t.id = c.getLong(0)
                    t.name = c.getString(1)
                    t.start = c.getLong(2)
                    t.end = c.getLong(3)
                    t.duration = c.getLong(4)
                    t.distance = c.getDouble(5)
                    t.status = c.getString(6)
                    t.points = c.getInt(7)
                    t.raw = c.getInt(8)
                    out.add(t)
                }
            }
        return out
    }

    @Synchronized
    fun track(id: Long): Track? {
        for (t in tracks()) if (t.id == id) return t
        return null
    }

    private fun point(c: Cursor): TrackPoint {
        return TrackPoint(
            c.getDouble(c.getColumnIndexOrThrow("lat")),
            c.getDouble(c.getColumnIndexOrThrow("lon")),
            nullable(c, "altitude"),
            nullable(c, "accuracy"),
            nullable(c, "speed"),
            c.getLong(c.getColumnIndexOrThrow("time")),
            c.getLong(c.getColumnIndexOrThrow("elapsed")),
            c.getInt(c.getColumnIndexOrThrow("segment")),
            c.getInt(c.getColumnIndexOrThrow("accepted")) == 1,
            nullable(c, "vertical_accuracy"),
            nullable(c, "msl_altitude"),
            nullable(c, "msl_accuracy"),
            nullable(c, "speed_accuracy")
        )
    }

    private fun nullable(c: Cursor, name: String): Double {
        val i = c.getColumnIndexOrThrow(name)
        return if (c.isNull(i)) Double.NaN else c.getDouble(i)
    }

    @Synchronized
    fun analyze(id: Long, activeMs: Long, distance: Double): TrackAnalytics.Result {
        val analysis = TrackAnalytics()
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM points WHERE track=? ORDER BY id",
                arrayOf<String>(id.toString())
            ).use { c ->
                while (c.moveToNext()) analysis.add(point(c))
            }
        return analysis.finish(distance, activeMs)
    }

    @Synchronized
    fun preview(id: Long, max: Int): MutableList<TrackPoint> {
        val points: MutableList<TrackPoint> = ArrayList<TrackPoint>()
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM points WHERE track=? AND accepted=1 ORDER BY id",
                arrayOf<String>("" + id)
            ).use { c ->
                val count = c.getCount()
                val stride = max(1, ceil(count / max.toDouble()).toInt())
                var lastSegment = -1
                var i = 0
                while (c.moveToNext()) {
                    val p = point(c)
                    if (i % stride == 0 || i == count - 1 || p.segment != lastSegment) {
                        if (i > 0 && p.segment != lastSegment && i % stride != 0) {
                            c.moveToPrevious()
                            points.add(point(c))
                            c.moveToNext()
                        }
                        points.add(p)
                    }
                    lastSegment = p.segment
                    i++
                }
            }
        return points
    }

    @Synchronized
    fun rename(id: Long, name: String?) {
        val v: ContentValues = ContentValues()
        v!!.put("name", name)
        getWritableDatabase().update("tracks", v, "id=?", arrayOf<String>("" + id))
    }

    @Synchronized
    fun delete(id: Long) {
        getWritableDatabase().delete("tracks", "id=?", arrayOf<String>("" + id))
    }

    @Synchronized
    fun savePlace(name: String?, note: String?, lat: Double, lon: Double): Long {
        require(Geo.valid(lat, lon)) { "Invalid coordinates" }
        val v: ContentValues = ContentValues()
        v!!.put("name", name)
        v.put("note", note)
        v.put("lat", lat)
        v.put("lon", lon)
        v.put("time", System.currentTimeMillis())
        return getWritableDatabase().insertOrThrow("places", null, v)
    }

    @Synchronized
    fun places(): MutableList<Place> {
        val list: MutableList<Place> = ArrayList<Place>()
        getReadableDatabase().rawQuery("SELECT * FROM places ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                val p = Place()
                p.id = c.getLong(0)
                p.name = c.getString(1)
                p.note = c.getString(2)
                p.lat = c.getDouble(3)
                p.lon = c.getDouble(4)
                p.time = c.getLong(5)
                list.add(p)
            }
        }
        return list
    }

    @Synchronized
    fun updatePlace(id: Long, name: String?, note: String) {
        val title = if (name == null) "" else name.trim { it <= ' ' }
        require(!title.isEmpty()) { "地点名称不能为空" }
        require(!(title.length > 100 || note == null || note.length > 2000)) { "名称最多100字，备注最多2000字" }
        val values = ContentValues()
        values.put("name", title)
        values.put("note", note)
        check(
            getWritableDatabase().update("places", values, "id=?", arrayOf<String>(id.toString()))
                    == 1
        ) { "地点已不存在，请返回列表刷新" }
    }

    @Synchronized
    fun deletePlace(id: Long) {
        getWritableDatabase().delete("places", "id=?", arrayOf<String>("" + id))
    }

    private fun optional(value: Double): String? {
        return if (java.lang.Double.isFinite(value)) value.toString() else ""
    }

    @Synchronized
    @Throws(IOException::class)
    fun export(id: Long, stream: OutputStream?, raw: Boolean) {
        export(id, stream, if (raw) TrackExport.CSV else TrackExport.GPX)
    }

    @Synchronized
    @Throws(IOException::class)
    fun export(id: Long, stream: OutputStream?, format: TrackExport?) {
        val raw = format == TrackExport.CSV
        val t = track(id)
        if (t == null) throw IOException("轨迹不存在")
        BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8)).use { out ->
            getReadableDatabase()
                .rawQuery(
                    ("SELECT * FROM points WHERE track=? "
                            + (if (format == TrackExport.GPX) "AND accepted=1 " else "")
                            + "ORDER BY id"),
                    arrayOf<String>("" + id)
                ).use { c ->
                    val kml = if (format == TrackExport.KML) Exporter(out, t.name) else null
                    if (raw) out.write(
                        "latitude_wgs84,longitude_wgs84,ellipsoid_height_m,accuracy_m,speed_mps,utc,elapsed_ms,segment,accepted,mock,vertical_accuracy_m,msl_altitude_m,msl_accuracy_m,speed_accuracy_mps\n"
                    )
                    else if (kml == null) out.write(Gpx.header(t.name!!))
                    var segment = -1
                    while (c.moveToNext()) {
                        val p = point(c)
                        if (kml != null) {
                            // Include rejected/mock rows in the cursor to preserve a break even in legacy data.
                            if (c.getInt(c.getColumnIndexOrThrow("mock")) == 1) kml.add(
                                TrackPoint(
                                    p.lat,
                                    p.lon,
                                    p.altitude,
                                    p.accuracy,
                                    p.speed,
                                    p.time,
                                    p.elapsed,
                                    p.segment,
                                    false
                                )
                            )
                            else kml.add(p)
                        } else if (raw) {
                            out.write(
                                String.format(
                                    Locale.US,
                                    "%.8f,%.8f,%s,%.2f,%s,%s,%d,%d,%s,%s,%s,%s,%s,%s\n",
                                    p.lat,
                                    p.lon,
                                    if (java.lang.Double.isFinite(p.altitude)) p.altitude.toString() else "",
                                    p.accuracy,
                                    if (java.lang.Double.isFinite(p.speed)) p.speed.toString() else "",
                                    Instant.ofEpochMilli(p.time),
                                    p.elapsed,
                                    p.segment,
                                    p.accepted,
                                    c.getInt(c.getColumnIndexOrThrow("mock")) == 1,
                                    optional(p.verticalAccuracy),
                                    optional(p.mslAltitude),
                                    optional(p.mslAccuracy),
                                    optional(p.speedAccuracy)
                                )
                            )
                        } else {
                            if (segment != p.segment) {
                                if (segment != -1) out.write("</trkseg>\n")
                                out.write("<trkseg>\n")
                                segment = p.segment
                            }
                            out.write(Gpx.point(p))
                        }
                    }
                    if (kml != null) kml.finish()
                    else if (!raw) {
                        if (segment != -1) out.write("</trkseg>\n")
                        out.write(Gpx.footer())
                    }
                }
        }
    }
}

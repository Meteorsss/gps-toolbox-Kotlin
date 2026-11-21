package com.meteorsss.startrace;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Store extends SQLiteOpenHelper {
  public static final class Track {
    public long id, start, end, duration;
    public String name, status;
    public double distance;
    public int points, raw;
  }

  public static final class Place {
    public long id, time;
    public double lat, lon;
    public String name, note;
  }

  public Store(Context context) {
    this(context, "startrace.db");
  }

  Store(Context context, String name) {
    super(context, name, null, 2);
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE tracks(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,start INTEGER"
            + " NOT NULL,end INTEGER NOT NULL DEFAULT 0,duration INTEGER NOT NULL DEFAULT"
            + " 0,distance REAL NOT NULL DEFAULT 0,status TEXT NOT NULL)");
    db.execSQL(
        "CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT,track INTEGER NOT NULL REFERENCES"
            + " tracks(id) ON DELETE CASCADE,lat REAL NOT NULL,lon REAL NOT NULL,altitude"
            + " REAL,accuracy REAL,speed REAL,time INTEGER NOT NULL,elapsed INTEGER NOT"
            + " NULL,segment INTEGER NOT NULL,accepted INTEGER NOT NULL,mock INTEGER NOT"
            + " NULL,vertical_accuracy REAL,msl_altitude REAL,msl_accuracy REAL,speed_accuracy"
            + " REAL)");
    db.execSQL("CREATE INDEX points_track ON points(track,id)");
    db.execSQL(
        "CREATE TABLE places(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,note TEXT NOT"
            + " NULL,lat REAL NOT NULL,lon REAL NOT NULL,time INTEGER NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE points ADD COLUMN vertical_accuracy REAL");
      db.execSQL("ALTER TABLE points ADD COLUMN msl_altitude REAL");
      db.execSQL("ALTER TABLE points ADD COLUMN msl_accuracy REAL");
      db.execSQL("ALTER TABLE points ADD COLUMN speed_accuracy REAL");
    }
  }

  public synchronized void recoverInterrupted() {
    getWritableDatabase()
        .execSQL(
            "UPDATE tracks SET status='interrupted',end=COALESCE((SELECT MAX(time) FROM points"
                + " WHERE track=tracks.id),start) WHERE status IN ('recording','paused')");
  }

  public synchronized long begin(String name) {
    ContentValues v = new ContentValues();
    v.put("name", name);
    v.put("start", System.currentTimeMillis());
    v.put("status", "recording");
    return getWritableDatabase().insertOrThrow("tracks", null, v);
  }

  public synchronized void update(long id, long duration, double distance, String status) {
    ContentValues v = new ContentValues();
    v.put("duration", duration);
    v.put("distance", distance);
    v.put("status", status);
    if (!status.equals("recording") && !status.equals("paused"))
      v.put("end", System.currentTimeMillis());
    getWritableDatabase().update("tracks", v, "id=?", new String[] {"" + id});
  }

  public synchronized void add(long id, TrackPoint p, boolean mock) {
    ContentValues v = new ContentValues();
    v.put("track", id);
    v.put("lat", p.lat);
    v.put("lon", p.lon);
    if (Double.isFinite(p.altitude)) v.put("altitude", p.altitude);
    if (Double.isFinite(p.accuracy)) v.put("accuracy", p.accuracy);
    if (Double.isFinite(p.speed)) v.put("speed", p.speed);
    v.put("time", p.time);
    v.put("elapsed", p.elapsed);
    v.put("segment", p.segment);
    v.put("accepted", p.accepted ? 1 : 0);
    v.put("mock", mock ? 1 : 0);
    if (Double.isFinite(p.verticalAccuracy)) v.put("vertical_accuracy", p.verticalAccuracy);
    if (Double.isFinite(p.mslAltitude)) v.put("msl_altitude", p.mslAltitude);
    if (Double.isFinite(p.mslAccuracy)) v.put("msl_accuracy", p.mslAccuracy);
    if (Double.isFinite(p.speedAccuracy)) v.put("speed_accuracy", p.speedAccuracy);
    getWritableDatabase().insertOrThrow("points", null, v);
  }

  public synchronized List<Track> tracks() {
    List<Track> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT t.*, (SELECT COUNT(*) FROM points p WHERE p.track=t.id AND accepted=1) AS"
                    + " good, (SELECT COUNT(*) FROM points p WHERE p.track=t.id) AS total FROM"
                    + " tracks t ORDER BY id DESC",
                null)) {
      while (c.moveToNext()) {
        Track t = new Track();
        t.id = c.getLong(0);
        t.name = c.getString(1);
        t.start = c.getLong(2);
        t.end = c.getLong(3);
        t.duration = c.getLong(4);
        t.distance = c.getDouble(5);
        t.status = c.getString(6);
        t.points = c.getInt(7);
        t.raw = c.getInt(8);
        out.add(t);
      }
    }
    return out;
  }

  public synchronized Track track(long id) {
    for (Track t : tracks()) if (t.id == id) return t;
    return null;
  }

  private TrackPoint point(Cursor c) {
    return new TrackPoint(
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
        nullable(c, "speed_accuracy"));
  }

  private double nullable(Cursor c, String name) {
    int i = c.getColumnIndexOrThrow(name);
    return c.isNull(i) ? Double.NaN : c.getDouble(i);
  }

  public synchronized TrackAnalytics.Result analyze(long id, long activeMs, double distance) {
    TrackAnalytics analysis = new TrackAnalytics();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM points WHERE track=? ORDER BY id",
                new String[] {Long.toString(id)})) {
      while (c.moveToNext()) analysis.add(point(c));
    }
    return analysis.finish(distance, activeMs);
  }

  public synchronized List<TrackPoint> preview(long id, int max) {
    List<TrackPoint> points = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM points WHERE track=? AND accepted=1 ORDER BY id",
                new String[] {"" + id})) {
      int count = c.getCount(),
          stride = Math.max(1, (int) Math.ceil(count / (double) max)),
          lastSegment = -1;
      for (int i = 0; c.moveToNext(); i++) {
        TrackPoint p = point(c);
        if (i % stride == 0 || i == count - 1 || p.segment != lastSegment) {
          if (i > 0 && p.segment != lastSegment && i % stride != 0) {
            c.moveToPrevious();
            points.add(point(c));
            c.moveToNext();
          }
          points.add(p);
        }
        lastSegment = p.segment;
      }
    }
    return points;
  }

  public synchronized void rename(long id, String name) {
    ContentValues v = new ContentValues();
    v.put("name", name);
    getWritableDatabase().update("tracks", v, "id=?", new String[] {"" + id});
  }

  public synchronized void delete(long id) {
    getWritableDatabase().delete("tracks", "id=?", new String[] {"" + id});
  }

  public synchronized long savePlace(String name, String note, double lat, double lon) {
    if (!Geo.valid(lat, lon)) throw new IllegalArgumentException("Invalid coordinates");
    ContentValues v = new ContentValues();
    v.put("name", name);
    v.put("note", note);
    v.put("lat", lat);
    v.put("lon", lon);
    v.put("time", System.currentTimeMillis());
    return getWritableDatabase().insertOrThrow("places", null, v);
  }

  public synchronized List<Place> places() {
    List<Place> list = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM places ORDER BY id DESC", null)) {
      while (c.moveToNext()) {
        Place p = new Place();
        p.id = c.getLong(0);
        p.name = c.getString(1);
        p.note = c.getString(2);
        p.lat = c.getDouble(3);
        p.lon = c.getDouble(4);
        p.time = c.getLong(5);
        list.add(p);
      }
    }
    return list;
  }

  public synchronized void updatePlace(long id, String name, String note) {
    String title = name == null ? "" : name.trim();
    if (title.isEmpty()) throw new IllegalArgumentException("地点名称不能为空");
    if (title.length() > 100 || note == null || note.length() > 2000)
      throw new IllegalArgumentException("名称最多100字，备注最多2000字");
    ContentValues values = new ContentValues();
    values.put("name", title);
    values.put("note", note);
    if (getWritableDatabase().update("places", values, "id=?", new String[] {Long.toString(id)})
        != 1) throw new IllegalStateException("地点已不存在，请返回列表刷新");
  }

  public synchronized void deletePlace(long id) {
    getWritableDatabase().delete("places", "id=?", new String[] {"" + id});
  }

  private String optional(double value) {
    return Double.isFinite(value) ? Double.toString(value) : "";
  }

  public synchronized void export(long id, OutputStream stream, boolean raw) throws IOException {
    export(id, stream, raw ? TrackExport.CSV : TrackExport.GPX);
  }

  public synchronized void export(long id, OutputStream stream, TrackExport format)
      throws IOException {
    boolean raw = format == TrackExport.CSV;
    Track t = track(id);
    if (t == null) throw new IOException("轨迹不存在");
    try (Writer out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
        Cursor c =
            getReadableDatabase()
                .rawQuery(
                    "SELECT * FROM points WHERE track=? "
                        + (format == TrackExport.GPX ? "AND accepted=1 " : "")
                        + "ORDER BY id",
                    new String[] {"" + id})) {
      Kml.Exporter kml = format == TrackExport.KML ? new Kml.Exporter(out, t.name) : null;
      if (raw)
        out.write(
            "latitude_wgs84,longitude_wgs84,ellipsoid_height_m,accuracy_m,speed_mps,utc,elapsed_ms,segment,accepted,mock,vertical_accuracy_m,msl_altitude_m,msl_accuracy_m,speed_accuracy_mps\n");
      else if (kml == null) out.write(Gpx.header(t.name));
      int segment = -1;
      while (c.moveToNext()) {
        TrackPoint p = point(c);
        if (kml != null) {
          // Include rejected/mock rows in the cursor to preserve a break even in legacy data.
          if (c.getInt(c.getColumnIndexOrThrow("mock")) == 1)
            kml.add(
                new TrackPoint(
                    p.lat,
                    p.lon,
                    p.altitude,
                    p.accuracy,
                    p.speed,
                    p.time,
                    p.elapsed,
                    p.segment,
                    false));
          else kml.add(p);
        } else if (raw) {
          out.write(
              String.format(
                  Locale.US,
                  "%.8f,%.8f,%s,%.2f,%s,%s,%d,%d,%s,%s,%s,%s,%s,%s\n",
                  p.lat,
                  p.lon,
                  Double.isFinite(p.altitude) ? Double.toString(p.altitude) : "",
                  p.accuracy,
                  Double.isFinite(p.speed) ? Double.toString(p.speed) : "",
                  java.time.Instant.ofEpochMilli(p.time),
                  p.elapsed,
                  p.segment,
                  p.accepted,
                  c.getInt(c.getColumnIndexOrThrow("mock")) == 1,
                  optional(p.verticalAccuracy),
                  optional(p.mslAltitude),
                  optional(p.mslAccuracy),
                  optional(p.speedAccuracy)));
        } else {
          if (segment != p.segment) {
            if (segment != -1) out.write("</trkseg>\n");
            out.write("<trkseg>\n");
            segment = p.segment;
          }
          out.write(Gpx.point(p));
        }
      }
      if (kml != null) kml.finish();
      else if (!raw) {
        if (segment != -1) out.write("</trkseg>\n");
        out.write(Gpx.footer());
      }
    }
  }
}

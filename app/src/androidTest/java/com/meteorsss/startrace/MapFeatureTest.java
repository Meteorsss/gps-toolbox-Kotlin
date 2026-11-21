package com.meteorsss.startrace;

import static org.junit.Assert.*;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MapFeatureTest {
  private Context context() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  private void await(
      ActivityScenario<MapActivity> scenario,
      java.util.function.Predicate<MapActivity> check,
      String label) {
    long deadline = SystemClock.elapsedRealtime() + 45000;
    boolean[] ready = {false};
    while (SystemClock.elapsedRealtime() < deadline) {
      scenario.onActivity(a -> ready[0] = check.test(a));
      if (ready[0]) return;
      SystemClock.sleep(100);
    }
    fail("Timed out: " + label);
  }

  private long fixture() {
    App app = (App) context().getApplicationContext();
    long id = app.store.begin("Synthetic map test");
    for (int i = 0; i < 20; i++)
      app.store.add(
          id,
          new TrackPoint(
              37.7749 + i * .00012,
              -122.4194 + i * .00008,
              100 + i,
              3,
              2,
              1700000000000L + i * 1000,
              1000 + i * 1000,
              i < 10 ? 0 : 1,
              true,
              2,
              70 + i,
              2,
              .1),
          false);
    app.store.update(id, 19000, 280, "finished");
    return id;
  }

  private void shot(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    SystemClock.sleep(800);
    Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    if (image != null) {
      File dir = new File(context().getExternalFilesDir(null), "screenshots");
      dir.mkdirs();
      try (OutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
        image.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      image.recycle();
    }
  }

  @Test
  public void migrationPreservesVersionOneDataAndUnknownHeight() {
    String name = "migration-test.db";
    context().deleteDatabase(name);
    File file = context().getDatabasePath(name);
    file.getParentFile().mkdirs();
    try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
      db.execSQL(
          "CREATE TABLE tracks(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,start"
              + " INTEGER NOT NULL,end INTEGER NOT NULL DEFAULT 0,duration INTEGER NOT NULL DEFAULT"
              + " 0,distance REAL NOT NULL DEFAULT 0,status TEXT NOT NULL)");
      db.execSQL(
          "CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT,track INTEGER NOT NULL,lat REAL"
              + " NOT NULL,lon REAL NOT NULL,altitude REAL,accuracy REAL,speed REAL,time INTEGER"
              + " NOT NULL,elapsed INTEGER NOT NULL,segment INTEGER NOT NULL,accepted INTEGER NOT"
              + " NULL,mock INTEGER NOT NULL)");
      db.execSQL(
          "CREATE TABLE places(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,note TEXT"
              + " NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,time INTEGER NOT NULL)");
      db.execSQL("INSERT INTO tracks VALUES(1,'old',1000,9000,8000,50,'finished')");
      db.execSQL("INSERT INTO points VALUES(1,1,30,110,100,3,2,1000,1000,0,1,0)");
      db.execSQL("INSERT INTO places VALUES(1,'saved','note',30,110,1000)");
      db.setVersion(1);
    }
    try (Store store = new Store(context(), name)) {
      assertEquals(2, store.getReadableDatabase().getVersion());
      assertEquals("old", store.track(1).name);
      assertEquals(50, store.track(1).distance, 0);
      assertEquals("saved", store.places().get(0).name);
      assertTrue(Double.isNaN(store.preview(1, 100).get(0).verticalAccuracy));
      assertTrue(Double.isNaN(store.analyze(1, 8000, 50).ascent));
    } finally {
      context().deleteDatabase(name);
    }
  }

  @Test
  public void mapConsentDefaultsOffAndOfflineLifecyclePreservesTrack() throws Exception {
    MapPolicy.allow(context(), false);
    assertFalse(MapPolicy.allowed(context()));
    long id = fixture();
    try (ActivityScenario<MapActivity> scenario =
        ActivityScenario.launch(
            new Intent(context(), MapActivity.class)
                .putExtra("track_id", id)
                .putExtra("test_local_style", true))) {
      await(
          scenario,
          a -> a.readyForTests() && a.trackPointsForTests() == 20,
          "local map and track ready");
      scenario.onActivity(a -> {
        assertFalse(a.onlineForTests());
        assertEquals(2, a.trackSegmentsForTests());
      });
      shot("map-local-track");
      scenario.onActivity(a -> assertTrue(a.cameraZoomForTests() > 3));
      scenario.recreate();
      await(
          scenario,
          a -> a.readyForTests() && a.trackPointsForTests() == 20 && a.cameraZoomForTests() > 3,
          "recreated map retains camera");
      scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
      scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
      await(scenario, MapActivity::readyForTests, "resumed map");
      assertFalse(MapPolicy.allowed(context()));
    } finally {
      ((App) context().getApplicationContext()).store.delete(id);
    }
  }

  @Test
  public void newVerticalFieldsSurviveStorageAndCsv() throws Exception {
    App app = (App) context().getApplicationContext();
    long id = fixture();
    try {
      TrackPoint p = app.store.preview(id, 100).get(0);
      assertEquals(2, p.verticalAccuracy, 0);
      assertEquals(70, p.mslAltitude, 0);
      assertEquals(.1, p.speedAccuracy, 0);
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      app.store.export(id, stream, true);
      assertTrue(stream.toString("UTF-8").contains("msl_altitude_m"));
      assertTrue(app.store.analyze(id, 19000, 280).msl);
    } finally {
      app.store.delete(id);
    }
  }

  private TextView button(View view, String label) {
    if (view instanceof TextView && label.contentEquals(((TextView) view).getText()))
      return (TextView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = button(group.getChildAt(i), label);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Test
  public void historicalMapRecenterRemainsLiveAfterRecreation() {
    InstrumentationRegistry.getInstrumentation()
        .getUiAutomation()
        .grantRuntimePermission(
            context().getPackageName(), android.Manifest.permission.ACCESS_COARSE_LOCATION);
    InstrumentationRegistry.getInstrumentation()
        .getUiAutomation()
        .grantRuntimePermission(
            context().getPackageName(), android.Manifest.permission.ACCESS_FINE_LOCATION);
    MapPolicy.allow(context(), false);
    long id = fixture();
    try (ActivityScenario<MapActivity> scenario =
        ActivityScenario.launch(
            new Intent(context(), MapActivity.class)
                .putExtra("track_id", id)
                .putExtra("test_local_style", true))) {
      await(scenario, a -> a.readyForTests() && a.trackPointsForTests() == 20, "historical map");
      scenario.onActivity(a -> {
        assertFalse(a.followingForTests());
        TextView recenter = button(a.getWindow().getDecorView(), "回到当前位置");
        assertNotNull(recenter);
        recenter.performClick();
        assertTrue(a.followingForTests());
      });
      scenario.recreate();
      await(
          scenario,
          a -> a.readyForTests() && a.trackPointsForTests() == 20,
          "recentered map recreated");
      scenario.onActivity(a -> assertTrue(a.followingForTests()));
      scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
      scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
      scenario.onActivity(a -> assertTrue(a.followingForTests()));
      assertFalse(MapPolicy.allowed(context()));
    } finally {
      ((App) context().getApplicationContext()).store.delete(id);
    }
  }

  @Test
  public void liveMapStatisticsRefreshAndSettleAfterRecordingStops() {
    MapPolicy.allow(context(), false);
    App app = (App) context().getApplicationContext();
    long id = fixture();
    TrackService previous = TrackService.active;
    // Synthetic in-memory recorder state only: no real GPS recording or user coordinates.
    TrackService live = new TrackService();
    live.trackId = id;
    live.paused = true;
    live.distance = 100;
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> TrackService.active = live);
    try (ActivityScenario<MapActivity> scenario =
        ActivityScenario.launch(
            new Intent(context(), MapActivity.class).putExtra("test_local_style", true))) {
      await(scenario, a -> a.readyForTests() && a.trackPointsForTests() == 20, "live map");
      scenario.onActivity(a -> {
        TextView statistics = button(a.getWindow().getDecorView(), "详细统计");
        assertNotNull(statistics);
        statistics.performClick();
        assertTrue(a.statisticsForTests().contains(Geo.meters(100)));
        live.distance = 200;
      });
      await(
          scenario,
          a -> a.statisticsForTests().contains(Geo.meters(200)),
          "statistics update without reopening");
      scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
      InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> live.distance = 300);
      scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
      await(
          scenario,
          a -> a.statisticsForTests().contains(Geo.meters(300)),
          "statistics refresh on resume");
      app.store.update(id, 19000, 450, "finished");
      scenario.onActivity(a -> TrackService.active = null);
      await(
          scenario,
          a -> a.statisticsForTests().contains(Geo.meters(450)),
          "final persisted statistics");
      scenario.onActivity(MapActivity::closeStatisticsForTests);
      await(scenario, a -> a.statisticsForTests().isEmpty(), "statistics dialog dismissed");
    } finally {
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(() -> TrackService.active = previous);
      app.store.delete(id);
    }
  }

  @Test
  public void onlineOpenFreeMapLoadsWithConsent() throws Exception {
    MapPolicy.allow(context(), true);
    long id = fixture();
    try (ActivityScenario<MapActivity> scenario =
        ActivityScenario.launch(
            new Intent(context(), MapActivity.class).putExtra("track_id", id))) {
      await(
          scenario,
          a -> a.readyForTests() && a.trackPointsForTests() == 20 && a.renderedForTests(),
          "OpenFreeMap native style rendering");
      scenario.onActivity(a -> assertTrue(a.onlineForTests()));
      shot("map-openfreemap-track");
    } finally {
      MapPolicy.allow(context(), false);
      ((App) context().getApplicationContext()).store.delete(id);
    }
  }
}

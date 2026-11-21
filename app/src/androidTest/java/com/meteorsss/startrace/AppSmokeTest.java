package com.meteorsss.startrace;

import static org.junit.Assert.*;

import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AppSmokeTest {
  private void shell(String command) throws Exception {
    try (ParcelFileDescriptor p =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(p)) {
      while (in.read() != -1) {}
    }
  }

  private void screenshot(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    SystemClock.sleep(700);
    Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    if (image != null) {
      File dir =
          new File(
              InstrumentationRegistry.getInstrumentation()
                  .getTargetContext()
                  .getExternalFilesDir(null),
              "screenshots");
      dir.mkdirs();
      try (OutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
        image.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      image.recycle();
    }
  }

  @Test
  public void allPagesAndBothThemesRender() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        Preferences.theme(a, ThemeMode.DARK);
        a.buildRoot();
      });
      String[] expected = {"此刻，在哪里", "头顶的卫星", "把走过的路留下", "随手可用"};
      for (int i = 0; i < 4; i++) {
        final int page = i;
        scenario.onActivity(a -> {
          a.goPage(page);
          assertTrue(a.visibleText().contains(expected[page]));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        screenshot("dark-" + i);
      }
      scenario.onActivity(a -> {
        a.goPage(3);
        a.navigate("compass");
      });
      awaitActivity(scenario, a -> a.hasWindowFocus(), "compass window focus");
      shell("input keyevent 4");
      awaitActivity(scenario, a -> a.mode.isEmpty(), "predictive back completes");
      scenario.onActivity(a -> {
        assertEquals("", a.mode);
        assertTrue(a.visibleText().contains("随手可用"));
      });
      String[] modes = {"settings", "places", "compass", "speed", "diagnostics"};
      for (String mode : modes) {
        scenario.onActivity(a -> a.navigate(mode));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        screenshot(mode);
      }
      scenario.onActivity(a -> {
        a.goPage(0);
        a.theme();
        assertTrue(a.u.light);
      });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      screenshot("light-home");
      scenario.recreate();
      scenario.onActivity(a -> {
        assertTrue(a.u.light);
        a.theme();
      });
    }
  }

  @Test
  public void storageExportAndRecoveryPreserveData() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Store db = ((App) context.getApplicationContext()).store;
    long id = db.begin("A&B 测试");
    try {
      db.add(id, new TrackPoint(31, 121, 42, 5, 1, 1700000000000L, 1000, 0, true), false);
      db.add(
          id,
          new TrackPoint(31.1, 121.1, Double.NaN, 90, Double.NaN, 1700000001000L, 2000, 1, false),
          true);
      db.add(id, new TrackPoint(31.2, 121.2, 43, 4, 2, 1700000040000L, 41000, 2, true), false);
      db.update(id, 1000, 5, "recording");
      db.recoverInterrupted();
      assertEquals("interrupted", db.track(id).status);
      assertEquals(2, db.track(id).points);
      ByteArrayOutputStream gpx = new ByteArrayOutputStream();
      db.export(id, gpx, false);
      String text = gpx.toString("UTF-8");
      assertTrue(text.contains("A&amp;B"));
      assertEquals(2, text.split("<trkseg>", -1).length - 1);
      assertFalse(text.contains("31.10000000"));
      ByteArrayOutputStream csv = new ByteArrayOutputStream();
      db.export(id, csv, true);
      assertEquals(5, csv.toString("UTF-8").split("\n", -1).length);
      long place = db.savePlace("Test", "note", 31, 121);
      assertTrue(db.places().stream().anyMatch(p -> p.id == place));
      db.deletePlace(place);
    } finally {
      db.delete(id);
    }
    assertNull(db.track(id));
  }

  @Test
  public void foregroundRecordingPausesResumesAndStops() throws Exception {
    String pkg = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
    shell("pm grant " + pkg + " android.permission.ACCESS_COARSE_LOCATION");
    shell("pm grant " + pkg + " android.permission.ACCESS_FINE_LOCATION");
    shell("pm grant " + pkg + " android.permission.POST_NOTIFICATIONS");
    shell("cmd location set-location-enabled true");
    long[] id = {-1};
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(
          a -> a.startForegroundService(new Intent(a, TrackService.class).setAction("START")));
      SystemClock.sleep(1500);
      scenario.onActivity(a -> {
        assertNotNull(TrackService.active);
        id[0] = TrackService.active.trackId;
        assertTrue(id[0] > 0);
        a.startService(new Intent(a, TrackService.class).setAction("PAUSE"));
      });
      SystemClock.sleep(500);
      scenario.onActivity(a -> {
        assertTrue(TrackService.active.paused);
        a.startService(new Intent(a, TrackService.class).setAction("RESUME"));
      });
      SystemClock.sleep(500);
      scenario.onActivity(a -> {
        assertFalse(TrackService.active.paused);
        a.startService(new Intent(a, TrackService.class).setAction("STOP"));
      });
      SystemClock.sleep(500);
      scenario.onActivity(a -> {
        assertNull(TrackService.active);
        assertEquals("finished", a.app.store.track(id[0]).status);
        a.app.store.delete(id[0]);
      });
    } finally {
      Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
      c.stopService(new Intent(c, TrackService.class));
      if (id[0] > 0) ((App) c.getApplicationContext()).store.delete(id[0]);
    }
  }

  private void grantLocation() throws Exception {
    String pkg = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
    shell("pm grant " + pkg + " android.permission.ACCESS_COARSE_LOCATION");
    shell("pm grant " + pkg + " android.permission.ACCESS_FINE_LOCATION");
    shell("cmd location set-location-enabled true");
  }

  private <T extends android.view.View> T find(android.view.View view, Class<T> type) {
    if (type.isInstance(view)) return type.cast(view);
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        T child = find(group.getChildAt(i), type);
        if (child != null) return child;
      }
    }
    return null;
  }

  private android.widget.EditText field(android.view.View view, String description) {
    if (view instanceof android.widget.EditText
        && description.contentEquals(
            view.getContentDescription() == null ? "" : view.getContentDescription()))
      return (android.widget.EditText) view;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        android.widget.EditText found = field(group.getChildAt(i), description);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Test
  public void compassFramesAreIndependentAndStopWhenHidden() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      CompassView[] view = {null};
      long[] before = {0}, after = {0};
      scenario.onActivity(a -> {
        a.navigate("compass");
        view[0] = find(a.body, CompassView.class);
        assertNotNull(view[0]);
        assertNotEquals(android.view.View.LAYER_TYPE_SOFTWARE, view[0].getLayerType());
      });
      SystemClock.sleep(300);
      scenario.onActivity(a -> {
        assertTrue(view[0].hasScheduledFrame());
        before[0] = view[0].renderedFrames();
      });
      SystemClock.sleep(1200);
      scenario.onActivity(a -> {
        after[0] = view[0].renderedFrames();
        assertTrue(
            "Compass must redraw independently of 2Hz page tick", after[0] - before[0] >= 12);
      });
      System.out.println("COMPASS_DRAW_FRAMES_IN_1200MS=" + (after[0] - before[0]));
      scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(() -> {
            assertFalse(view[0].hasScheduledFrame());
            assertFalse(
                ((App)
                        InstrumentationRegistry.getInstrumentation()
                            .getTargetContext()
                            .getApplicationContext())
                    .engine.compassSubscribed());
            before[0] = view[0].renderedFrames();
          });
      SystemClock.sleep(300);
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(() -> assertEquals(before[0], view[0].renderedFrames()));
      scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
      scenario.onActivity(a -> {
        assertTrue(view[0].hasScheduledFrame());
        a.goPage(0);
        assertFalse(view[0].hasScheduledFrame());
        assertFalse(a.e.compassSubscribed());
      });
    }
  }

  @Test
  public void dashboardCoordinateSystemsAndSatelliteBreakdown() throws Exception {
    grantLocation();
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        android.location.Location l = new android.location.Location("gps");
        l.setLatitude(39.911954);
        l.setLongitude(116.377817);
        l.setAccuracy(4);
        l.setSpeed(10);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        a.e.onLocationChanged(l);
        a.goPage(0);
        assertTrue(a.visibleText().indexOf("当前速度") < a.visibleText().indexOf("水平精度估计"));
        assertTrue(a.visibleText().contains("36.0"));
        a.setCoordinateSystem(false);
        assertTrue(a.visibleText().contains("39.9119540"));
        a.setCoordinateSystem(true);
        assertTrue(a.visibleText().contains("39.9133455"));
        assertEquals(39.911954, a.e.location.getLatitude(), 0);
      });
      awaitActivity(
          scenario, a -> a.hasWindowFocus(), "clipboard requires foreground window focus");
      scenario.onActivity(a -> {
        a.e.location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        a.copyHere();
      });
      awaitActivity(
          scenario,
          a -> {
            android.content.ClipboardManager cb =
                a.getSystemService(android.content.ClipboardManager.class);
            return cb.hasPrimaryClip()
                && cb.getPrimaryClip() != null
                && cb.getPrimaryClip().getItemAt(0).getText().toString().contains("GCJ-02");
          },
          "GCJ-02 clipboard contents");
      screenshot("gcj02-dashboard");
      scenario.recreate();
      scenario.onActivity(a -> {
        assertTrue(a.gcj());
        a.setCoordinateSystem(false);
        java.util.List<GnssEngine.Satellite> entries = new java.util.ArrayList<>();
        for (int i = 1; i <= 30; i++) {
          GnssEngine.Satellite s = new GnssEngine.Satellite();
          s.system = i <= 20 ? 5 : 1;
          s.id = i;
          s.cn0 = 45 - i;
          s.used = i % 2 == 0;
          s.azimuth = i * 12;
          s.elevation = 45;
          entries.add(s);
        }
        GnssEngine.Satellite second = new GnssEngine.Satellite();
        second.system = 5;
        second.id = 1;
        second.cn0 = 20;
        second.used = true;
        entries.add(second);
        a.e.acceptSatelliteSnapshot(entries);
        a.goPage(1);
        assertEquals(30, a.e.uniqueCount(false));
        assertEquals(16, a.e.uniqueCount(true));
        assertTrue(a.visibleText().contains("11 / 20"));
        assertTrue(a.visibleText().contains("5 / 10"));
        assertTrue(a.visibleText().contains("16 / 31"));
        assertTrue(a.visibleText().contains("全部频率信号明细"));
      });
      screenshot("satellite-breakdown-fixture");
      scenario.onActivity(a -> a.e.acceptSatelliteSnapshot(java.util.Collections.emptyList()));
    }
  }

  private void awaitActivity(
      ActivityScenario<MainActivity> scenario,
      java.util.function.Predicate<MainActivity> check,
      String message) {
    long deadline = SystemClock.elapsedRealtime() + 10000;
    boolean[] ready = {false};
    while (SystemClock.elapsedRealtime() < deadline) {
      scenario.onActivity(a -> ready[0] = check.test(a));
      if (ready[0]) return;
      SystemClock.sleep(50);
    }
    fail("Timed out: " + message);
  }

  @Test
  public void editPlacePreservesCoordinatesAndPersists() throws Exception {
    final long[] id = {-1}, created = {0};
    final android.app.AlertDialog[] dialog = {null};
    final Store.Place[] original = {null};
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        id[0] = a.app.store.savePlace("旧名称", "旧备注", 31.23, 121.47);
        original[0] = a.app.store.places().stream().filter(x -> x.id == id[0]).findFirst().get();
        created[0] = original[0].time;
        a.navigate("places");
        dialog[0] = a.editPlaceDialog(original[0]);
      });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      awaitActivity(
          scenario,
          a -> dialog[0].getWindow().getDecorView().hasWindowFocus(),
          "edit dialog displayed");
      scenario.onActivity(a -> {
        field(dialog[0].getWindow().getDecorView(), "地点名称").setText("未保存");
        dialog[0].getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
      });
      awaitActivity(scenario, a -> !dialog[0].isShowing(), "cancel dialog closes");
      scenario.onActivity(a -> {
        assertEquals(
            "旧名称", a.app.store.places().stream().filter(x -> x.id == id[0]).findFirst().get().name);
        dialog[0] = a.editPlaceDialog(original[0]);
      });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      awaitActivity(
          scenario,
          a -> dialog[0].getWindow().getDecorView().hasWindowFocus(),
          "second edit dialog displayed");
      scenario.onActivity(a -> {
        field(dialog[0].getWindow().getDecorView(), "地点名称").setText("   ");
        dialog[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
      });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(a -> {
        assertTrue(dialog[0].isShowing());
        assertNotNull(field(dialog[0].getWindow().getDecorView(), "地点名称").getError());
        field(dialog[0].getWindow().getDecorView(), "地点名称").setText(" 停车点 ");
        field(dialog[0].getWindow().getDecorView(), "地点备注").setText("地下二层\n靠近电梯");
        dialog[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
      });
      awaitActivity(scenario, a -> !dialog[0].isShowing(), "save dialog closes");
      scenario.onActivity(a -> {
        Store.Place saved =
            a.app.store.places().stream().filter(x -> x.id == id[0]).findFirst().get();
        assertEquals("停车点", saved.name);
        assertEquals("地下二层\n靠近电梯", saved.note);
        assertEquals(original[0].lat, saved.lat, 0);
        assertEquals(original[0].lon, saved.lon, 0);
        assertEquals(created[0], saved.time);
        try (Store reopened = new Store(a)) {
          assertEquals(
              "停车点", reopened.places().stream().filter(x -> x.id == id[0]).findFirst().get().name);
        }
        assertTrue(a.visibleText().contains("停车点"));
      });
      screenshot("edited-place");
      scenario.recreate();
      scenario.onActivity(a -> {
        Store.Place saved =
            a.app.store.places().stream().filter(x -> x.id == id[0]).findFirst().get();
        a.app.store.updatePlace(saved.id, saved.name, "");
        assertEquals(
            "", a.app.store.places().stream().filter(x -> x.id == id[0]).findFirst().get().note);
        a.app.store.deletePlace(id[0]);
      });
    } finally {
      if (dialog[0] != null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> dialog[0].dismiss());
      if (id[0] > 0)
        ((App)
                InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getApplicationContext())
            .store.deletePlace(id[0]);
    }
  }

  @Test
  public void realGnssStatusCallbackPreservesMultiFrequencyEntries() throws Exception {
    grantLocation();
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        android.location.GnssStatus.Builder builder = new android.location.GnssStatus.Builder();
        int[] systems = {4, 4, 4, 4, 5, 5, 5}, ids = {194, 194, 195, 195, 6, 6, 6};
        float[]
            frequencies =
                {
                  1575420000f,
                  1176450000f,
                  1575420000f,
                  1176450000f,
                  1561100000f,
                  1575420000f,
                  1176450000f
                },
            cn0 = {35, 25, 40, 0, 30, 38, 0};
        boolean[] used = {true, true, true, false, true, true, false};
        for (int i = 0; i < ids.length; i++)
          builder.addSatellite(
              systems[i],
              ids[i],
              cn0[i],
              45,
              90,
              true,
              true,
              used[i],
              true,
              frequencies[i],
              false,
              0);
        android.location.GnssStatus status = builder.build();
        a.e.acceptGnssStatus(status);
        assertEquals(7, status.getSatelliteCount());
        assertEquals(7, a.e.currentSatellites().size());
        assertEquals(7, a.e.satelliteStats().total.entries());
        assertEquals(5, a.e.satelliteStats().total.usedEntries());
        assertEquals(3, a.e.uniqueCount(false));
        assertEquals(3, a.e.uniqueCount(true));
        assertEquals(5, a.e.satelliteStats().total.trackedEntries());
        assertEquals(4, a.e.satelliteStats().system(4).entries());
        assertEquals(3, a.e.satelliteStats().system(4).usedEntries());
        assertEquals(2, a.e.satelliteStats().system(4).received());
        assertEquals(
            3,
            a.e.currentSatellites().stream()
                .filter(s -> s.system == 5 && s.id == 6 && s.hasFrequency)
                .count());
        a.goPage(1);
        String text = a.visibleText();
        assertTrue(text.contains("5 / 7"));
        assertTrue(text.contains("3 / 3"));
        assertTrue(text.contains("3 / 4"));
        assertTrue(text.contains("2 / 3"));
        assertTrue(text.contains("参与定位 / 系统上报"));
        a.setGroupSignals(false);
        assertFalse(a.groupSignals());
        assertEquals(7, a.e.currentSatellites().size());
        a.setGroupSignals(true);
        assertTrue(a.groupSignals());
        a.e.acceptGnssStatus(new android.location.GnssStatus.Builder().build());
        assertEquals(0, a.e.satelliteStats().total.entries());
        assertEquals(0, a.e.uniqueCount(false));
        a.e.satelliteAt = SystemClock.elapsedRealtime() - 16000;
        a.render();
        assertTrue(a.visibleText().contains("暂无可用快照"));
        assertTrue(a.visibleText().contains("— / —"));
      });
    }
  }
}

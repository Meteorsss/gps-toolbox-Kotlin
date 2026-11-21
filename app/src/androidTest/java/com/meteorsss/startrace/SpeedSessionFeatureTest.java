package com.meteorsss.startrace;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.location.Location;
import android.os.*;
import android.view.Gravity;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SpeedSessionFeatureTest {
  private App app() {
    return (App)
        InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
  }

  @Before
  public void enableLocation() throws Exception {
    android.app.UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    ui.grantRuntimePermission(
        app().getPackageName(), android.Manifest.permission.ACCESS_COARSE_LOCATION);
    ui.grantRuntimePermission(
        app().getPackageName(), android.Manifest.permission.ACCESS_FINE_LOCATION);
    ParcelFileDescriptor fd = ui.executeShellCommand("cmd location set-location-enabled true");
    try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      while (in.read() != -1) {}
    }
  }

  private Location fix(double latitude, float speed) {
    Location location = new Location("gps");
    location.setLatitude(latitude);
    location.setLongitude(121);
    location.setAccuracy(3);
    location.setSpeed(speed);
    location.setSpeedAccuracyMetersPerSecond(.1f);
    location.setTime(1700000000000L + SystemClock.elapsedRealtime());
    location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
    return location;
  }

  private void enter(ActivityScenario<MainActivity> scenario) {
    scenario.onActivity(a -> {
      // These tests deliver synthetic fixes through the production callback; do not mix in the
      // emulator GPS provider's own stationary coordinates while exercising realistic jump filters.
      a.getSystemService(android.location.LocationManager.class).removeUpdates(a.e);
      a.e.location = null;
      a.navigate("speed");
      assertNotNull(a.speedStats());
    });
  }

  private void sample(ActivityScenario<MainActivity> scenario, double latitude, float speed) {
    // Activity recreation/resume may have registered the provider again; isolate each fixture.
    scenario.onActivity(
        a -> a.getSystemService(android.location.LocationManager.class).removeUpdates(a.e));
    SystemClock.sleep(1050);
    scenario.onActivity(a -> {
      a.e.onLocationChanged(fix(latitude, speed));
      a.refresh();
    });
  }

  private void shot(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    SystemClock.sleep(500);
    android.graphics.Bitmap image =
        InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    if (image == null) return;
    File folder = new File(app().getExternalFilesDir(null), "screenshots");
    folder.mkdirs();
    try (OutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
      image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      image.recycle();
    }
  }

  @Test
  public void entersAndMeasuresWithoutStartingOrSavingATrack() throws Exception {
    App app = app();
    int tracks = app.store.tracks().size();
    assertNull(TrackService.active);
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      enter(scenario);
      sample(scenario, 31, 3);
      sample(scenario, 31.00004, 5);
      sample(scenario, 31.00008, 4);
      scenario.onActivity(a -> {
        SpeedSession.Snapshot s = a.speedStats();
        assertTrue(s.validPoints >= 3);
        assertTrue(s.distance > 0);
        assertTrue(Double.isFinite(s.averageMps));
        assertEquals(5, s.maxMps, 0);
        TextView stats = a.body.findViewWithTag("large-speed-session-stats");
        TextView current = a.body.findViewWithTag("large-speed-current");
        TextView mps = a.body.findViewWithTag("large-speed-current-mps");
        assertTrue(stats.getText().toString().contains("自动统计中"));
        assertTrue(stats.getText().toString().contains("最高速度  18.0 km/h"));
        assertFalse(stats.getText().toString().contains("未开始记录"));
        assertEquals("14.4", current.getText().toString());
        assertEquals("4.00 m/s", mps.getText().toString());
        assertEquals(Gravity.CENTER, current.getGravity());
        assertEquals(Gravity.CENTER, mps.getGravity());
        assertNull(TrackService.active);
        assertNull(a.e.recorder);
        assertEquals(tracks, app.store.tracks().size());
      });
      shot("independent-speed-session-dual-units");
    } finally {
      app.engine.location = null;
    }
  }

  @Test
  public void backAndTabNavigationClearAndReentryStartsAtZero() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      enter(scenario);
      sample(scenario, 31, 4);
      sample(scenario, 31.00004, 4);
      scenario.onActivity(a -> {
        assertTrue(a.speedStats().distance > 0);
        a.navigate("");
        assertNull(a.speedStats());
      });
      enter(scenario);
      scenario.onActivity(a -> {
        assertEquals(0, a.speedStats().distance, 0);
        assertEquals(0, a.speedStats().validPoints);
        assertTrue(Double.isNaN(a.speedStats().maxMps));
        a.goPage(0);
        assertNull(a.speedStats());
      });
      enter(scenario);
      sample(scenario, 32, 2);
      scenario.onActivity(a -> {
        assertEquals(2, a.speedStats().maxMps, 0);
        assertEquals(0, a.speedStats().distance, 0);
      });
    } finally {
      app().engine.location = null;
    }
  }

  @Test
  public void recreationAndBackgroundDoNotResetOrConnectHiddenMovement() {
    double[] distance = {0};
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      enter(scenario);
      sample(scenario, 31, 4);
      sample(scenario, 31.00004, 4);
      scenario.onActivity(a -> distance[0] = a.speedStats().distance);
      scenario.recreate();
      scenario.onActivity(a -> {
        assertEquals(distance[0], a.speedStats().distance, .001);
        assertEquals(4, a.speedStats().maxMps, 0);
        a.theme();
        assertEquals(distance[0], a.speedStats().distance, .001);
      });
      scenario.moveToState(Lifecycle.State.CREATED);
      SystemClock.sleep(1000);
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(() -> app().engine.onLocationChanged(fix(32, 40)));
      scenario.moveToState(Lifecycle.State.RESUMED);
      sample(scenario, 32.00004, 2);
      scenario.onActivity(a -> {
        assertEquals(distance[0], a.speedStats().distance, .001);
        assertEquals(4, a.speedStats().maxMps, 0);
        a.navigate("");
        assertNull(a.speedStats());
      });
    } finally {
      app().engine.location = null;
    }
  }

  @Test
  public void mockAndInvalidFixesCannotCreateSessionStatistics() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      enter(scenario);
      SystemClock.sleep(50);
      scenario.onActivity(a -> {
        Location mock = fix(31, 50);
        mock.setMock(true);
        a.e.onLocationChanged(mock);
        a.refresh();
        assertEquals(0, a.speedStats().validPoints);
        assertTrue(Double.isNaN(a.speedStats().maxMps));
      });
      SystemClock.sleep(50);
      scenario.onActivity(a -> {
        Location poor = fix(31, 40);
        poor.setAccuracy(1000);
        a.e.onLocationChanged(poor);
        a.refresh();
        assertEquals(0, a.speedStats().validPoints);
        assertEquals(0, a.speedStats().distance, 0);
      });
      sample(scenario, 31.00004, 3);
      scenario.onActivity(a -> {
        assertEquals(3, a.speedStats().maxMps, 0);
        assertEquals(0, a.speedStats().distance, 0);
      });
    } finally {
      app().engine.location = null;
    }
  }
}

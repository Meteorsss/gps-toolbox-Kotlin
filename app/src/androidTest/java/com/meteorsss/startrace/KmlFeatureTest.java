package com.meteorsss.startrace;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.*;
import org.junit.runner.RunWith;
import org.w3c.dom.Document;

@RunWith(AndroidJUnit4.class)
public class KmlFeatureTest {
  private Context context() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  private Document parse(File file) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().parse(file);
  }

  @Test
  public void kmlExportPickerStateAndSavedFilePreserveTrack() throws Exception {
    App app = (App) context().getApplicationContext();
    long id = app.store.begin("Synthetic KML & 中文 <test>");
    File directory = new File(context().getExternalFilesDir(null), "export-fixtures");
    assertTrue(directory.isDirectory() || directory.mkdirs());
    File file = new File(directory, "synthetic-track.kml");
    if (file.exists()) assertTrue(file.delete());
    try {
      for (int i = 0; i < 6; i++) {
        boolean mock = i == 2;
        app.store.add(
            id,
            new TrackPoint(
                mock ? 39 : 31 + i * .0001,
                mock ? 139 : 121 + i * .0001,
                50,
                3,
                2,
                1700000000000L + i * 1000,
                1000 + i * 1000,
                i < 4 ? 0 : 1,
                true),
            mock);
      }
      app.store.update(id, 5000, 50, "finished");
      try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
        scenario.onActivity(a -> {
          a.selectedTrack = id;
          a.navigate("track");
          assertTrue(a.visibleText().contains("导出 KML"));
          Intent picker = a.prepareExport(app.store.track(id), TrackExport.KML);
          assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.getAction());
          assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE));
          assertEquals("application/vnd.google-earth.kml+xml", picker.getType());
          assertEquals("startrace-" + id + ".kml", picker.getStringExtra(Intent.EXTRA_TITLE));
        });
        scenario.recreate();
        // Exercise the actual save callback after recreation, without relying on DocumentsUI.
        scenario.onActivity(a ->
            a.onActivityResult(5, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(file))));
        CountDownLatch done = new CountDownLatch(1);
        app.io.execute(done::countDown);
        assertTrue("export task completed", done.await(30, TimeUnit.SECONDS));
        assertTrue(file.isFile());
        Document xml = parse(file);
        assertEquals(Kml.NAMESPACE, xml.getDocumentElement().getNamespaceURI());
        assertEquals(
            "Synthetic KML & 中文 <test>",
            xml.getElementsByTagNameNS(Kml.NAMESPACE, "name").item(0).getTextContent());
        assertEquals(5, xml.getElementsByTagNameNS(Kml.NAMESPACE, "Placemark").getLength());
        assertEquals(2, xml.getElementsByTagNameNS(Kml.NAMESPACE, "LineString").getLength());
        assertEquals(3, xml.getElementsByTagNameNS(Kml.NAMESPACE, "Point").getLength());
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertFalse(text.contains("139.00000000,39.00000000"));
        assertFalse(text.contains("50.000"));
        assertTrue(text.contains("121.00000000,31.00000000"));
        assertTrue(text.contains("id=\"track-start\"><name>起点</name>"));
        assertTrue(text.contains("id=\"track-end\"><name>终点</name>"));
        // Existing formats and persisted source measurements are unaffected.
        ByteArrayOutputStream csv = new ByteArrayOutputStream();
        app.store.export(id, csv, TrackExport.CSV);
        assertEquals(7, csv.toString("UTF-8").trim().split("\\n").length);
        assertTrue(csv.toString("UTF-8").contains("139.00000000"));
        ByteArrayOutputStream gpx = new ByteArrayOutputStream();
        app.store.export(id, gpx, TrackExport.GPX);
        assertTrue(gpx.toString("UTF-8").contains("<gpx"));
        assertEquals(6, app.store.preview(id, 100).size());
        // Cancellation must not keep a stale pending export that later overwrites a file.
        long length = file.length();
        scenario.onActivity(a -> {
          a.prepareExport(app.store.track(id), TrackExport.KML);
          a.onActivityResult(5, Activity.RESULT_CANCELED, null);
          a.onActivityResult(5, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(file)));
        });
        CountDownLatch settled = new CountDownLatch(1);
        app.io.execute(settled::countDown);
        assertTrue(settled.await(30, TimeUnit.SECONDS));
        assertEquals(length, file.length());
      }
    } finally {
      app.store.delete(id);
    }
  }

  private void assertCentered(MainActivity a) {
    TextView label = a.body.findViewWithTag("dashboard-speed-label");
    TextView value = a.body.findViewWithTag("dashboard-speed-value");
    LinearLayout row = a.body.findViewWithTag("dashboard-speed-row");
    assertNotNull(label);
    assertNotNull(value);
    assertNotNull(row);
    assertTrue(row.getWidth() > 0);
    assertEquals(Gravity.CENTER, label.getGravity());
    assertEquals(Gravity.CENTER, row.getGravity());
    assertEquals(Gravity.CENTER, value.getGravity());
    View unit = row.getChildAt(1);
    assertEquals(row.getWidth() / 2.0, (value.getLeft() + unit.getRight()) / 2.0, a.u.dp(2));
    LinearLayout hero = (LinearLayout) row.getParent();
    assertEquals(hero.getWidth() / 2.0, (row.getLeft() + row.getRight()) / 2.0, a.u.dp(2));
    assertEquals(hero.getWidth() / 2.0, (label.getLeft() + label.getRight()) / 2.0, a.u.dp(2));
  }

  private void screenshot(String name) throws Exception {
    android.graphics.Bitmap image =
        InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    if (image == null) return;
    File dir = new File(context().getExternalFilesDir(null), "screenshots");
    assertTrue(dir.isDirectory() || dir.mkdirs());
    try (OutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      image.recycle();
    }
  }

  @Test
  public void dashboardSpeedIsCenteredInBothThemesAndAfterRecreation() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      for (int i = 0; i < 2; i++) {
        scenario.onActivity(a -> a.goPage(0));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(250);
        scenario.onActivity(this::assertCentered);
        screenshot("centered-speed-" + i);
        scenario.onActivity(MainActivity::theme);
      }
      scenario.recreate();
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(this::assertCentered);
    }
  }
}

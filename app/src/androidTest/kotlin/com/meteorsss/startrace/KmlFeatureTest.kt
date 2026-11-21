package com.meteorsss.startrace

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.ActivityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class KmlFeatureTest {
    private fun context(): Context? {
        return InstrumentationRegistry.getInstrumentation().getTargetContext()
    }

    @Throws(Exception::class)
    private fun parse(file: File): Document? {
        val factory = DocumentBuilderFactory.newInstance()
        factory!!.setNamespaceAware(true)
        return factory.newDocumentBuilder().parse(file)
    }

    @Test
    @Throws(Exception::class)
    fun kmlExportPickerStateAndSavedFilePreserveTrack() {
        val app = context()!!.getApplicationContext() as App
        val id = app.store.begin("Synthetic KML & 中文 <test>")
        val directory: File? = File(context()!!.getExternalFilesDir(null), "export-fixtures")
        Assert.assertTrue(directory!!.isDirectory() || directory.mkdirs())
        val file = File(directory, "synthetic-track.kml")
        if (file.exists()) Assert.assertTrue(file.delete())
        try {
            for (i in 0..5) {
                val mock = i == 2
                app.store.add(
                    id,
                    TrackPoint(
                        if (mock) 39.0 else 31 + i * .0001,
                        if (mock) 139.0 else 121 + i * .0001,
                        50.0,
                        3.0,
                        2.0,
                        1700000000000L + i * 1000,
                        (1000 + i * 1000).toLong(),
                        if (i < 4) 0 else 1,
                        true
                    ),
                    mock
                )
            }
            app.store.update(id, 5000, 50.0, "finished")
            ActivityScenario.launch<MainActivity?>(MainActivity::class.java).use { scenario ->
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    a!!.selectedTrack = id
                    a.navigate("track")
                    Assert.assertTrue(a.visibleText().contains("导出 KML"))
                    val picker = a.prepareExport(app.store.track(id), TrackExport.KML)
                    Assert.assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker!!.getAction())
                    Assert.assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE))
                    Assert.assertEquals("application/vnd.google-earth.kml+xml", picker.getType())
                    Assert.assertEquals("startrace-" + id + ".kml", picker.getStringExtra(Intent.EXTRA_TITLE))
                })
                scenario.recreate()
                // Exercise the actual save callback after recreation, without relying on DocumentsUI.
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    a!!.onActivityResult(
                        5,
                        Activity.RESULT_OK,
                        Intent().setData(Uri.fromFile(file))
                    )
                })
                val done = CountDownLatch(1)
                app.io.execute(Runnable { done.countDown() })
                Assert.assertTrue("export task completed", done.await(30, TimeUnit.SECONDS))
                Assert.assertTrue(file.isFile())
                val xml = parse(file)
                Assert.assertEquals(Kml.NAMESPACE, xml!!.getDocumentElement().getNamespaceURI())
                Assert.assertEquals(
                    "Synthetic KML & 中文 <test>",
                    xml.getElementsByTagNameNS(Kml.NAMESPACE, "name").item(0).getTextContent()
                )
                Assert.assertEquals(5, xml.getElementsByTagNameNS(Kml.NAMESPACE, "Placemark").getLength().toLong())
                Assert.assertEquals(2, xml.getElementsByTagNameNS(Kml.NAMESPACE, "LineString").getLength().toLong())
                Assert.assertEquals(3, xml.getElementsByTagNameNS(Kml.NAMESPACE, "Point").getLength().toLong())
                val text = String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                Assert.assertFalse(text.contains("139.00000000,39.00000000"))
                Assert.assertFalse(text.contains("50.000"))
                Assert.assertTrue(text.contains("121.00000000,31.00000000"))
                Assert.assertTrue(text.contains("id=\"track-start\"><name>起点</name>"))
                Assert.assertTrue(text.contains("id=\"track-end\"><name>终点</name>"))
                // Existing formats and persisted source measurements are unaffected.
                val csv = ByteArrayOutputStream()
                app.store.export(id, csv, TrackExport.CSV)
                Assert.assertEquals(
                    7,
                    csv.toString("UTF-8").trim { it <= ' ' }.split("\\n".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray().size.toLong())
                Assert.assertTrue(csv.toString("UTF-8").contains("139.00000000"))
                val gpx = ByteArrayOutputStream()
                app.store.export(id, gpx, TrackExport.GPX)
                Assert.assertTrue(gpx.toString("UTF-8").contains("<gpx"))
                Assert.assertEquals(6, app.store.preview(id, 100).size.toLong())
                // Cancellation must not keep a stale pending export that later overwrites a file.
                val length = file.length()
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    a!!.prepareExport(app.store.track(id), TrackExport.KML)
                    a.onActivityResult(5, Activity.RESULT_CANCELED, null)
                    a.onActivityResult(5, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
                })
                val settled = CountDownLatch(1)
                app.io.execute(Runnable { settled.countDown() })
                Assert.assertTrue(settled.await(30, TimeUnit.SECONDS))
                Assert.assertEquals(length, file.length())
            }
        } finally {
            app.store.delete(id)
        }
    }

    private fun assertCentered(a: MainActivity) {
        val label = a.body.findViewWithTag<TextView>("dashboard-speed-label")
        val value = a.body.findViewWithTag<TextView>("dashboard-speed-value")
        val row = a.body.findViewWithTag<LinearLayout>("dashboard-speed-row")
        Assert.assertNotNull(label)
        Assert.assertNotNull(value)
        Assert.assertNotNull(row)
        Assert.assertTrue(row!!.getWidth() > 0)
        Assert.assertEquals(Gravity.CENTER.toLong(), label!!.getGravity().toLong())
        Assert.assertEquals(Gravity.CENTER.toLong(), row.getGravity().toLong())
        Assert.assertEquals(Gravity.CENTER.toLong(), value!!.getGravity().toLong())
        val unit = row.getChildAt(1)
        Assert.assertEquals(
            row.getWidth() / 2.0,
            (value.getLeft() + unit!!.getRight()) / 2.0,
            a.u.dp(2f).toDouble()
        )
        val hero: LinearLayout? = row.getParent() as LinearLayout
        Assert.assertEquals(hero!!.getWidth() / 2.0, (row.getLeft() + row.getRight()) / 2.0, a.u.dp(2f).toDouble())
        Assert.assertEquals(
            hero.getWidth() / 2.0,
            (label.getLeft() + label.getRight()) / 2.0,
            a.u.dp(2f).toDouble()
        )
    }

    @Throws(Exception::class)
    private fun screenshot(name: String) {
        val image =
            InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot()
        if (image == null) return
        val dir = File(context()!!.getExternalFilesDir(null), "screenshots")
        Assert.assertTrue(dir.isDirectory() || dir.mkdirs())
        try {
            FileOutputStream(File(dir, name + ".png")).use { out ->
                image.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            image.recycle()
        }
    }

    @Test
    @Throws(Exception::class)
    fun dashboardSpeedIsCenteredInBothThemesAndAfterRecreation() {
        ActivityScenario.launch<MainActivity?>(MainActivity::class.java).use { scenario ->
            for (i in 0..1) {
                scenario.onActivity(ActivityAction { a: MainActivity? -> a!!.goPage(0) })
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                SystemClock.sleep(250)
                scenario.onActivity(ActivityAction { a: MainActivity? -> this.assertCentered(a!!) })
                screenshot("centered-speed-" + i)
                scenario.onActivity(ActivityAction { obj: MainActivity? -> obj!!.theme() })
            }
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity(ActivityAction { a: MainActivity? -> this.assertCentered(a!!) })
        }
    }
}

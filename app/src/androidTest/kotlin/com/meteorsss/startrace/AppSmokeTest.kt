package com.meteorsss.startrace

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.location.GnssStatus
import android.location.Location
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.ActivityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meteorsss.startrace.GnssEngine.Satellite
import com.meteorsss.startrace.Store.Place
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.function.Predicate

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @Throws(Exception::class)
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation()
            .executeShellCommand(command).use { p ->
                ParcelFileDescriptor.AutoCloseInputStream(p).use { `in` ->
                    while (`in`.read() != -1) {
                    }
                }
            }
    }

    @Throws(Exception::class)
    private fun screenshot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(700)
        val image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot()
        if (image != null) {
            val dir =
                File(
                    InstrumentationRegistry.getInstrumentation()
                        .getTargetContext()
                        .getExternalFilesDir(null),
                    "screenshots"
                )
            dir.mkdirs()
            FileOutputStream(File(dir, name + ".png")).use { out ->
                image.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            image.recycle()
        }
    }

    @Test
    @Throws(Exception::class)
    fun allPagesAndBothThemesRender() {
        ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { scenario ->
            scenario.onActivity(ActivityAction { a: MainActivity ->
                Preferences.theme(a, ThemeMode.DARK)
                a!!.buildRoot()
            })
            val expected: Array<String>? = arrayOf<String>("此刻，在哪里", "头顶的卫星", "把走过的路留下", "随手可用")
            for (i in 0..3) {
                val page = i
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    a!!.goPage(page)
                    Assert.assertTrue(a.visibleText().contains(expected!![page]!!))
                })
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                screenshot("dark-" + i)
            }
            scenario.onActivity(ActivityAction { a: MainActivity ->
                a!!.goPage(3)
                a.navigate("compass")
            })
            awaitActivity(scenario, Predicate { a: MainActivity -> a!!.hasWindowFocus() }, "compass window focus")
            shell("input keyevent 4")
            awaitActivity(scenario, Predicate { a: MainActivity -> a!!.mode.isEmpty() }, "predictive back completes")
            scenario.onActivity(ActivityAction { a: MainActivity ->
                Assert.assertEquals("", a!!.mode)
                Assert.assertTrue(a.visibleText().contains("随手可用"))
            })
            val modes: Array<String>? = arrayOf<String>("settings", "places", "compass", "speed", "diagnostics")
            for (mode in modes!!) {
                scenario.onActivity(ActivityAction { a: MainActivity -> a!!.navigate(mode) })
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                screenshot(mode!!)
            }
            scenario.onActivity(ActivityAction { a: MainActivity ->
                a!!.goPage(0)
                a.theme()
                Assert.assertTrue(a.u.light)
            })
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screenshot("light-home")
            scenario.recreate()
            scenario.onActivity(ActivityAction { a: MainActivity ->
                Assert.assertTrue(a!!.u.light)
                a.theme()
            })
        }
    }

    @Test
    @Throws(Exception::class)
    fun storageExportAndRecoveryPreserveData() {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val db = (context.getApplicationContext() as App).store
        val id = db.begin("A&B 测试")
        try {
            db.add(id, TrackPoint(31.0, 121.0, 42.0, 5.0, 1.0, 1700000000000L, 1000, 0, true), false)
            db.add(
                id,
                TrackPoint(31.1, 121.1, Double.NaN, 90.0, Double.NaN, 1700000001000L, 2000, 1, false),
                true
            )
            db.add(id, TrackPoint(31.2, 121.2, 43.0, 4.0, 2.0, 1700000040000L, 41000, 2, true), false)
            db.update(id, 1000, 5.0, "recording")
            db.recoverInterrupted()
            Assert.assertEquals("interrupted", db.track(id)!!.status)
            Assert.assertEquals(2, db.track(id)!!.points.toLong())
            val gpx = ByteArrayOutputStream()
            db.export(id, gpx, false)
            val text = gpx.toString("UTF-8")
            Assert.assertTrue(text.contains("A&amp;B"))
            Assert.assertEquals(2, (text.split("<trkseg>".toRegex()).toTypedArray().size - 1).toLong())
            Assert.assertFalse(text.contains("31.10000000"))
            val csv = ByteArrayOutputStream()
            db.export(id, csv, true)
            Assert.assertEquals(5, csv.toString("UTF-8").split("\n".toRegex()).toTypedArray().size.toLong())
            val place = db.savePlace("Test", "note", 31.0, 121.0)
            Assert.assertTrue(db.places().stream().anyMatch { p: Place? -> p!!.id == place })
            db.deletePlace(place)
        } finally {
            db.delete(id)
        }
        Assert.assertNull(db.track(id))
    }

    @Test
    @Throws(Exception::class)
    fun foregroundRecordingPausesResumesAndStops() {
        val pkg = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()
        shell("pm grant " + pkg + " android.permission.ACCESS_COARSE_LOCATION")
        shell("pm grant " + pkg + " android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant " + pkg + " android.permission.POST_NOTIFICATIONS")
        shell("cmd location set-location-enabled true")
        val id = longArrayOf(-1)
        try {
            ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { scenario ->
                scenario.onActivity(
                    ActivityAction { a: MainActivity ->
                        a!!.startForegroundService(
                            Intent(
                                a,
                                TrackService::class.java
                            ).setAction("START")
                        )
                    })
                SystemClock.sleep(1500)
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    Assert.assertNotNull(TrackService.Companion.active)
                    id[0] = TrackService.Companion.active!!.trackId
                    Assert.assertTrue(id[0] > 0)
                    a!!.startService(Intent(a, TrackService::class.java).setAction("PAUSE"))
                })
                SystemClock.sleep(500)
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    Assert.assertTrue(TrackService.Companion.active!!.paused)
                    a!!.startService(Intent(a, TrackService::class.java).setAction("RESUME"))
                })
                SystemClock.sleep(500)
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    Assert.assertFalse(TrackService.Companion.active!!.paused)
                    a!!.startService(Intent(a, TrackService::class.java).setAction("STOP"))
                })
                SystemClock.sleep(500)
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    Assert.assertNull(TrackService.Companion.active)
                    Assert.assertEquals("finished", a!!.app.store.track(id[0])!!.status)
                    a.app.store.delete(id[0])
                })
            }
        } finally {
            val c = InstrumentationRegistry.getInstrumentation().getTargetContext()
            c.stopService(Intent(c, TrackService::class.java))
            if (id[0] > 0) (c.getApplicationContext() as App).store.delete(id[0])
        }
    }

    @Throws(Exception::class)
    private fun grantLocation() {
        val pkg = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()
        shell("pm grant " + pkg + " android.permission.ACCESS_COARSE_LOCATION")
        shell("pm grant " + pkg + " android.permission.ACCESS_FINE_LOCATION")
        shell("cmd location set-location-enabled true")
    }

    private fun <T : View> find(view: View?, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) {
            val group = view
            for (i in 0..<group.getChildCount()) {
                val child = find<T>(group.getChildAt(i), type)
                if (child != null) return child
            }
        }
        return null
    }

    private fun field(view: View?, description: String): EditText? {
        if (view is EditText
            && description.contentEquals(
                if (view.getContentDescription() == null) "" else view.getContentDescription()
            )
        ) return view as EditText?
        if (view is ViewGroup) {
            val group = view
            for (i in 0..<group.getChildCount()) {
                val found = field(group.getChildAt(i), description)
                if (found != null) return found
            }
        }
        return null
    }

    @Test
    @Throws(Exception::class)
    fun compassFramesAreIndependentAndStopWhenHidden() {
        ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { scenario ->
            val view: Array<CompassView?>? = arrayOf<CompassView?>(null)
            val before: LongArray? = longArrayOf(0)
            val after: LongArray? = longArrayOf(0)
            scenario.onActivity(ActivityAction { a: MainActivity ->
                a!!.navigate("compass")
                view!![0] = find<CompassView>(a.body, CompassView::class.java)
                Assert.assertNotNull(view[0])
                Assert.assertNotEquals(View.LAYER_TYPE_SOFTWARE.toLong(), view[0]!!.getLayerType().toLong())
            })
            SystemClock.sleep(300)
            scenario.onActivity(ActivityAction { a: MainActivity ->
                Assert.assertTrue(view!![0]!!.hasScheduledFrame())
                before!![0] = view[0]!!.renderedFrames()
            })
            SystemClock.sleep(1200)
            scenario.onActivity(ActivityAction { a: MainActivity ->
                after!![0] = view!![0]!!.renderedFrames()
                Assert.assertTrue(
                    "Compass must redraw independently of 2Hz page tick", after[0] - before!![0] >= 12
                )
            })
            println("COMPASS_DRAW_FRAMES_IN_1200MS=" + (after!![0] - before!![0]))
            scenario.moveToState(Lifecycle.State.CREATED)
            InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(Runnable {
                    Assert.assertFalse(view!![0]!!.hasScheduledFrame())
                    Assert.assertFalse(
                        (InstrumentationRegistry.getInstrumentation()
                            .getTargetContext()
                            .getApplicationContext() as App)
                            .engine.compassSubscribed()
                    )
                    before[0] = view[0]!!.renderedFrames()
                })
            SystemClock.sleep(300)
            InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(Runnable { Assert.assertEquals(before[0], view!![0]!!.renderedFrames()) })
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity(ActivityAction { a: MainActivity ->
                Assert.assertTrue(view!![0]!!.hasScheduledFrame())
                a!!.goPage(0)
                Assert.assertFalse(view[0]!!.hasScheduledFrame())
                Assert.assertFalse(a.e.compassSubscribed())
            })
        }
    }

    @Test
    @Throws(Exception::class)
    fun dashboardCoordinateSystemsAndSatelliteBreakdown() {
        grantLocation()
        ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { scenario ->
            scenario.onActivity(ActivityAction { a: MainActivity ->
                val l = Location("gps")
                l.setLatitude(39.911954)
                l.setLongitude(116.377817)
                l.setAccuracy(4f)
                l.setSpeed(10f)
                l.setTime(System.currentTimeMillis())
                l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos())
                a!!.e.onLocationChanged(l)
                a.goPage(0)
                Assert.assertTrue(a.visibleText().indexOf("当前速度") < a.visibleText().indexOf("水平精度估计"))
                Assert.assertTrue(a.visibleText().contains("36.0"))
                a.setCoordinateSystem(false)
                Assert.assertTrue(a.visibleText().contains("39.9119540"))
                a.setCoordinateSystem(true)
                Assert.assertTrue(a.visibleText().contains("39.9133455"))
                Assert.assertEquals(39.911954, a.e.location!!.getLatitude(), 0.0)
            })
            awaitActivity(
                scenario,
                Predicate { a: MainActivity -> a!!.hasWindowFocus() },
                "clipboard requires foreground window focus"
            )
            scenario.onActivity(ActivityAction { a: MainActivity ->
                a!!.e.location!!.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos())
                a.copyHere()
            })
            awaitActivity(
                scenario,
                Predicate { a: MainActivity ->
                    val cb =
                        a!!.getSystemService<ClipboardManager>(ClipboardManager::class.java)
                    cb!!.hasPrimaryClip()
                            && cb.getPrimaryClip() != null && cb.getPrimaryClip()!!.getItemAt(0).getText()
                        .toString().contains("GCJ-02")
                },
                "GCJ-02 clipboard contents"
            )
            screenshot("gcj02-dashboard")
            scenario.recreate()
            scenario.onActivity(ActivityAction { a: MainActivity ->
                Assert.assertTrue(a!!.gcj())
                a.setCoordinateSystem(false)
                val entries: MutableList<Satellite> = ArrayList<Satellite>()
                for (i in 1..30) {
                    val s = Satellite()
                    s.system = if (i <= 20) 5 else 1
                    s.id = i
                    s.cn0 = (45 - i).toFloat()
                    s.used = i % 2 == 0
                    s.azimuth = (i * 12).toFloat()
                    s.elevation = 45f
                    entries.add(s)
                }
                val second = Satellite()
                second.system = 5
                second.id = 1
                second.cn0 = 20f
                second.used = true
                entries.add(second)
                a.e.acceptSatelliteSnapshot(entries)
                a.goPage(1)
                Assert.assertEquals(30, a.e.uniqueCount(false).toLong())
                Assert.assertEquals(16, a.e.uniqueCount(true).toLong())
                Assert.assertTrue(a.visibleText().contains("11 / 20"))
                Assert.assertTrue(a.visibleText().contains("5 / 10"))
                Assert.assertTrue(a.visibleText().contains("16 / 31"))
                Assert.assertTrue(a.visibleText().contains("全部频率信号明细"))
            })
            screenshot("satellite-breakdown-fixture")
            scenario.onActivity(ActivityAction { a: MainActivity -> a!!.e.acceptSatelliteSnapshot(mutableListOf<Satellite>()) })
        }
    }

    private fun awaitActivity(
        scenario: ActivityScenario<MainActivity>,
        check: Predicate<MainActivity>,
        message: String
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        val ready = booleanArrayOf(false)
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(ActivityAction { a: MainActivity -> ready[0] = check.test(a) })
            if (ready[0]) return
            SystemClock.sleep(50)
        }
        Assert.fail("Timed out: " + message)
    }

    @Test
    @Throws(Exception::class)
    fun editPlacePreservesCoordinatesAndPersists() {
        val id = longArrayOf(-1)
        val created = longArrayOf(0)
        val dialog = arrayOf<AlertDialog?>(null)
        val original = arrayOf<Place?>(null)
        try {
            ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { scenario ->
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    id[0] = a!!.app.store.savePlace("旧名称", "旧备注", 31.23, 121.47)
                    original[0] =
                        a.app.store.places().stream().filter { x: Place? -> x!!.id == id[0] }.findFirst().get()
                    created[0] = original[0]!!.time
                    a.navigate("places")
                    dialog[0] = a.editPlaceDialog(original[0])
                })
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                awaitActivity(
                    scenario,
                    Predicate { a: MainActivity -> dialog[0]!!.getWindow()!!.getDecorView().hasWindowFocus() },
                    "edit dialog displayed"
                )
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    field(dialog[0]!!.getWindow()!!.getDecorView(), "地点名称")!!.setText("未保存")
                    dialog[0]!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                })
                awaitActivity(
                    scenario,
                    Predicate { a: MainActivity -> !dialog[0]!!.isShowing() },
                    "cancel dialog closes"
                )
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    Assert.assertEquals(
                        "旧名称",
                        a!!.app.store.places().stream().filter { x: Place? -> x!!.id == id[0] }.findFirst().get().name
                    )
                    dialog[0] = a.editPlaceDialog(original[0])
                })
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                awaitActivity(
                    scenario,
                    Predicate { a: MainActivity -> dialog[0]!!.getWindow()!!.getDecorView().hasWindowFocus() },
                    "second edit dialog displayed"
                )
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    field(dialog[0]!!.getWindow()!!.getDecorView(), "地点名称")!!.setText("   ")
                    dialog[0]!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                })
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    Assert.assertTrue(dialog[0]!!.isShowing())
                    Assert.assertNotNull(field(dialog[0]!!.getWindow()!!.getDecorView(), "地点名称")!!.getError())
                    field(dialog[0]!!.getWindow()!!.getDecorView(), "地点名称")!!.setText(" 停车点 ")
                    field(dialog[0]!!.getWindow()!!.getDecorView(), "地点备注")!!.setText("地下二层\n靠近电梯")
                    dialog[0]!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                })
                awaitActivity(
                    scenario,
                    Predicate { a: MainActivity -> !dialog[0]!!.isShowing() },
                    "save dialog closes"
                )
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    val saved: Place? =
                        a!!.app.store.places().stream().filter { x: Place -> x.id == id[0] }.findFirst().get()
                    Assert.assertEquals("停车点", saved!!.name)
                    Assert.assertEquals("地下二层\n靠近电梯", saved.note)
                    Assert.assertEquals(original[0]!!.lat, saved.lat, 0.0)
                    Assert.assertEquals(original[0]!!.lon, saved.lon, 0.0)
                    Assert.assertEquals(created[0], saved.time)
                    Store(a).use { reopened ->
                        Assert.assertEquals(
                            "停车点",
                            reopened.places().stream().filter { x: Place? -> x!!.id == id[0] }.findFirst().get().name
                        )
                    }
                    Assert.assertTrue(a.visibleText().contains("停车点"))
                })
                screenshot("edited-place")
                scenario.recreate()
                scenario.onActivity(ActivityAction { a: MainActivity ->
                    val saved: Place? =
                        a!!.app.store.places().stream().filter { x: Place -> x.id == id[0] }.findFirst().get()
                    a.app.store.updatePlace(saved!!.id, saved.name, "")
                    Assert.assertEquals(
                        "",
                        a.app.store.places().stream().filter { x: Place? -> x!!.id == id[0] }.findFirst().get().note
                    )
                    a.app.store.deletePlace(id[0])
                })
            }
        } finally {
            if (dialog[0] != null) InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(Runnable { dialog[0]!!.dismiss() })
            if (id[0] > 0) (InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getApplicationContext() as App)
                .store.deletePlace(id[0])
        }
    }

    @Test
    @Throws(Exception::class)
    fun realGnssStatusCallbackPreservesMultiFrequencyEntries() {
        grantLocation()
        ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { scenario ->
            scenario.onActivity(ActivityAction { a: MainActivity ->
                val builder = GnssStatus.Builder()
                val systems: IntArray? = intArrayOf(4, 4, 4, 4, 5, 5, 5)
                val ids: IntArray? = intArrayOf(194, 194, 195, 195, 6, 6, 6)
                val frequencies: FloatArray? =
                    floatArrayOf(
                        1575420000f,
                        1176450000f,
                        1575420000f,
                        1176450000f,
                        1561100000f,
                        1575420000f,
                        1176450000f
                    )
                val cn0: FloatArray? = floatArrayOf(35f, 25f, 40f, 0f, 30f, 38f, 0f)
                val used: BooleanArray? = booleanArrayOf(true, true, true, false, true, true, false)
                for (i in ids!!.indices) builder.addSatellite(
                    systems!![i],
                    ids[i],
                    cn0!![i],
                    45f,
                    90f,
                    true,
                    true,
                    used!![i],
                    true,
                    frequencies!![i],
                    false,
                    0f
                )
                val status = builder.build()
                a!!.e.acceptGnssStatus(status)
                Assert.assertEquals(7, status!!.getSatelliteCount().toLong())
                Assert.assertEquals(7, a.e.currentSatellites().size.toLong())
                Assert.assertEquals(7, a.e.satelliteStats().total.entries().toLong())
                Assert.assertEquals(5, a.e.satelliteStats().total.usedEntries().toLong())
                Assert.assertEquals(3, a.e.uniqueCount(false).toLong())
                Assert.assertEquals(3, a.e.uniqueCount(true).toLong())
                Assert.assertEquals(5, a.e.satelliteStats().total.trackedEntries().toLong())
                Assert.assertEquals(4, a.e.satelliteStats().system(4).entries().toLong())
                Assert.assertEquals(3, a.e.satelliteStats().system(4).usedEntries().toLong())
                Assert.assertEquals(2, a.e.satelliteStats().system(4).received().toLong())
                Assert.assertEquals(
                    3,
                    a.e.currentSatellites().stream()
                        .filter { s: Satellite? -> s!!.system == 5 && s.id == 6 && s.hasFrequency }
                        .count())
                a.goPage(1)
                val text = a.visibleText()
                Assert.assertTrue(text!!.contains("5 / 7"))
                Assert.assertTrue(text.contains("3 / 3"))
                Assert.assertTrue(text.contains("3 / 4"))
                Assert.assertTrue(text.contains("2 / 3"))
                Assert.assertTrue(text.contains("参与定位 / 系统上报"))
                a.setGroupSignals(false)
                Assert.assertFalse(a.groupSignals())
                Assert.assertEquals(7, a.e.currentSatellites().size.toLong())
                a.setGroupSignals(true)
                Assert.assertTrue(a.groupSignals())
                a.e.acceptGnssStatus(GnssStatus.Builder().build())
                Assert.assertEquals(0, a.e.satelliteStats().total.entries().toLong())
                Assert.assertEquals(0, a.e.uniqueCount(false).toLong())
                a.e.satelliteAt = SystemClock.elapsedRealtime() - 16000
                a.render()
                Assert.assertTrue(a.visibleText().contains("暂无可用快照"))
                Assert.assertTrue(a.visibleText().contains("— / —"))
            })
        }
    }
}

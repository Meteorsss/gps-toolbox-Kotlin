package com.meteorsss.startrace

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.location!!.Location
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowInsetsController
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.ActivityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class RecordingOptionsTest {
    private fun app(): App {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext() as App
    }

    @Throws(Exception::class)
    private fun shell(command: String) {
        val fd =
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { `in` ->
            while (`in`.read() != -1) {
            }
        }
    }

    @Throws(Exception::class)
    private fun shot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(800) // Wait for the composed frame after recreation.
        val bitmap =
            InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot()
        if (bitmap == null) return
        val folder = File(app().getExternalFilesDir(null), "screenshots")
        folder.mkdirs()
        try {
            FileOutputStream(File(folder, name + ".png")).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun awaitTheme(
        scenario: ActivityScenario<MainActivity?>, light: Boolean, systemDark: Boolean
    ) {
        val end = SystemClock.elapsedRealtime() + 15000
        val matches = booleanArrayOf(false)
        while (SystemClock.elapsedRealtime() < end) {
            scenario.onActivity(ActivityAction { a: MainActivity? ->
                matches[0] =
                    a!!.u.light == light && (((a.getResources().getConfiguration().uiMode
                            and Configuration.UI_MODE_NIGHT_MASK)
                            == Configuration.UI_MODE_NIGHT_YES)
                            == systemDark) && (((a.getWindow().getInsetsController()!!.getSystemBarsAppearance()
                            and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                            != 0)
                            == light)
            })
            if (matches[0]) return
            SystemClock.sleep(100)
        }
        Assert.fail("system appearance did not update")
    }

    private fun fix(elapsed: Long, latitude: Double): Location {
        val l = Location("gps")
        l.setLatitude(latitude)
        l.setLongitude(121.0)
        l.setTime(1700000000000L + elapsed)
        l.setElapsedRealtimeNanos(elapsed * 1000000L)
        l.setAccuracy(3f)
        l.setSpeed(2f)
        l.setSpeedAccuracyMetersPerSecond(.1f)
        return l
    }

    @Test
    fun serviceActuallyThrottlesChangesAndPreservesRawBreaks() {
        val app = app()
        val id = app.store.begin("Synthetic interval test")
        val previous: TrackService? = TrackService.Companion.active
        val saved = Preferences.interval(app)
        val live = TrackService()
        try {
            InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(Runnable {
                    live.setIntervalSeconds(3)
                    live.prepareForTests(app, id)
                    TrackService.Companion.active = live
                    val base = SystemClock.elapsedRealtime() - 12000
                    for (i in 0..6) live.fixForTests(fix(base + i * 1000, 31 + i * .00001))
                    Assert.assertEquals(3, app.store.track(id).raw.toLong())
                    Assert.assertEquals(3, app.store.track(id).points.toLong())
                    Assert.assertEquals(base + 6000, app.store.preview(id, 100).get(2).elapsed)
                    Preferences.interval(app, 1)
                    live.setIntervalSeconds(Preferences.interval(app))
                    live.fixForTests(fix(base + 7000, 31.00007))
                    Assert.assertEquals(4, app.store.track(id).raw.toLong())
                    Assert.assertNotEquals(
                        app.store.preview(id, 100).get(2).segment.toLong(),
                        app.store.preview(id, 100).get(3).segment.toLong()
                    )
                    Assert.assertEquals(1, Preferences.interval(app).toLong())
                    // A rejected callback between two saved timestamps must still prevent a bridging line.
                    live.setIntervalSeconds(3)
                    live.fixForTests(fix(base + 8000, 31.00008))
                    val poor = fix(base + 9000, 31.00009)
                    poor.setAccuracy(1000f)
                    live.fixForTests(poor)
                    live.fixForTests(fix(base + 11000, 31.00011))
                    Assert.assertEquals(6, app.store.track(id).raw.toLong())
                    val points = app.store.preview(id, 100)
                    Assert.assertNotEquals(points.get(4)!!.segment.toLong(), points.get(5)!!.segment.toLong())
                    val count = app.store.track(id).raw.toLong()
                    live.paused = true
                    live.fixForTests(fix(base + 12000, 31.00012))
                    Assert.assertEquals(count, app.store.track(id).raw.toLong())
                })
        } finally {
            InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(Runnable {
                    TrackService.Companion.active = previous
                    Preferences.interval(app, saved)
                })
            app.store.delete(id)
        }
    }

    @Test
    @Throws(Exception::class)
    fun largeSpeedStartsIndependentlyAndDoesNotReadTrackTotals() {
        val app = app()
        val id = app.store.begin("Synthetic independent speed test")
        val previous: TrackService? = TrackService.Companion.active
        val live = TrackService()
        try {
            ActivityScenario.launch<MainActivity?>(MainActivity::class.java).use { scenario ->
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    live.prepareForTests(app, id)
                    live.distance = 1234.0
                    live.paused = true
                    TrackService.Companion.active = live
                    a!!.e.location = null
                    a.navigate("speed")
                    val stats = a.body.findViewWithTag<TextView>("large-speed-session-stats")
                    val current = a.body.findViewWithTag<TextView>("large-speed-current")
                    val mps = a.body.findViewWithTag<TextView?>("large-speed-current-mps")
                    Assert.assertNotNull(stats)
                    Assert.assertNotNull(current)
                    Assert.assertNotNull(mps)
                    Assert.assertEquals(Gravity.CENTER.toLong(), current!!.getGravity().toLong())
                    Assert.assertEquals(0.0, a.speedStats().distance, 0.0)
                    Assert.assertTrue(stats!!.getText().toString().contains("本次测速"))
                    Assert.assertFalse(stats.getText().toString().contains(Geo.meters(1234.0)))
                    live.distance = 2345.0
                    a.refresh()
                    Assert.assertEquals(0.0, a.speedStats().distance, 0.0)
                    Assert.assertFalse(stats.getText().toString().contains(Geo.meters(2345.0)))
                    a.navigate("")
                    Assert.assertNull(a.speedStats())
                    Assert.assertSame(live, TrackService.Companion.active)
                    Assert.assertTrue(live.paused)
                    a.navigate("speed")
                    Assert.assertEquals(0.0, a.speedStats().distance, 0.0)
                    Assert.assertEquals(0, a.speedStats().validPoints.toLong())
                    TrackService.Companion.active = null
                    a.refresh()
                    Assert.assertNotNull(a.speedStats())
                })
            }
        } finally {
            InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(Runnable { TrackService.Companion.active = previous })
            app.store.delete(id)
        }
    }

    @Test
    @Throws(Exception::class)
    fun threeThemeChoicesPersistAndSystemUpdatesAutomatically() {
        val app = app()
        val saved = Preferences.theme(app)
        val originalNight = app.getSystemService<UiModeManager?>(UiModeManager::class.java)!!.getNightMode()
        try {
            ActivityScenario.launch<MainActivity?>(MainActivity::class.java).use { scenario ->
                for (mode in ThemeMode.entries) {
                    scenario.onActivity(ActivityAction { a: MainActivity? ->
                        Preferences.theme(a, mode)
                        a!!.buildRoot()
                        a.navigate("settings")
                        Assert.assertTrue(a.visibleText().contains("色彩模式：" + mode.label))
                        Assert.assertEquals(mode, Preferences.theme(a))
                        Assert.assertEquals(Preferences.light(a), a.u.light)
                    })
                    scenario.recreate()
                    scenario.onActivity(ActivityAction { a: MainActivity? ->
                        Assert.assertEquals(mode, Preferences.theme(a))
                        Assert.assertEquals(Preferences.light(a), a!!.u.light)
                    })
                }
                shell("cmd uimode night yes")
                awaitTheme(scenario, false, true)
                shell("cmd uimode night no")
                awaitTheme(scenario, true, false)
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    Preferences.theme(a, ThemeMode.DARK)
                    a!!.buildRoot()
                })
                shell("cmd uimode night yes")
                awaitTheme(scenario, false, true)
                shell("cmd uimode night no")
                awaitTheme(scenario, false, false)
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    Preferences.theme(a, ThemeMode.LIGHT)
                    a!!.buildRoot()
                })
                shell("cmd uimode night yes")
                awaitTheme(scenario, true, true)
                scenario.onActivity(ActivityAction { a: MainActivity? -> a!!.navigate("settings") })
                shot("three-mode-settings")
            }
        } finally {
            val night =
                if (originalNight == UiModeManager.MODE_NIGHT_YES)
                    "yes"
                else
                    if (originalNight == UiModeManager.MODE_NIGHT_NO) "no" else "auto"
            shell("cmd uimode night " + night)
            Preferences.theme(app, saved)
        }
    }

    @Test
    fun mapUsesTheSameManualAndSystemThemePreference() {
        val app = app()
        val saved = Preferences.theme(app)
        try {
            for (mode in ThemeMode.entries) {
                Preferences.theme(app, mode)
                ActivityScenario.launch<MapActivity?>(
                    Intent(app, MapActivity::class.java).putExtra("test_local_style", true)
                ).use { scenario ->
                    scenario.onActivity(ActivityAction { a: MapActivity? ->
                        Assert.assertEquals(
                            Preferences.light(a),
                            a!!.lightForTests()
                        )
                    })
                }
            }
        } finally {
            Preferences.theme(app, saved)
        }
    }
}

package com.meteorsss.startrace

import android.Manifest
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.ActivityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class SpeedSessionFeatureTest {
    private fun app(): App {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext() as App
    }

    @Before
    @Throws(Exception::class)
    fun enableLocation() {
        val ui = InstrumentationRegistry.getInstrumentation().getUiAutomation()
        ui.grantRuntimePermission(
            app().getPackageName(), Manifest.permission.ACCESS_COARSE_LOCATION
        )
        ui.grantRuntimePermission(
            app().getPackageName(), Manifest.permission.ACCESS_FINE_LOCATION
        )
        val fd = ui.executeShellCommand("cmd location set-location-enabled true")
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { `in` ->
            while (`in`.read() != -1) {
            }
        }
    }

    private fun fix(latitude: Double, speed: Float): Location {
        val location = Location("gps")
        location.setLatitude(latitude)
        location.setLongitude(121.0)
        location.setAccuracy(3f)
        location.setSpeed(speed)
        location.setSpeedAccuracyMetersPerSecond(.1f)
        location.setTime(1700000000000L + SystemClock.elapsedRealtime())
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos())
        return location
    }

    private fun enter(scenario: ActivityScenario<MainActivity?>) {
        scenario.onActivity(ActivityAction { a: MainActivity? ->
            // These tests deliver synthetic fixes through the production callback; do not mix in the
            // emulator GPS provider's own stationary coordinates while exercising realistic jump filters.
            a!!.getSystemService<LocationManager?>(LocationManager::class.java)!!.removeUpdates(a.e)
            a.e.location = null
            a.navigate("speed")
            Assert.assertNotNull(a.speedStats())
        })
    }

    private fun sample(scenario: ActivityScenario<MainActivity?>, latitude: Double, speed: Float) {
        // Activity recreation/resume may have registered the provider again; isolate each fixture.
        scenario.onActivity(
            ActivityAction { a: MainActivity? ->
                a!!.getSystemService<LocationManager?>(LocationManager::class.java)!!.removeUpdates(a.e)
            })
        SystemClock.sleep(1050)
        scenario.onActivity(ActivityAction { a: MainActivity? ->
            a!!.e.onLocationChanged(fix(latitude, speed))
            a.refresh()
        })
    }

    @Throws(Exception::class)
    private fun shot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(500)
        val image =
            InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot()
        if (image == null) return
        val folder = File(app().getExternalFilesDir(null), "screenshots")
        folder.mkdirs()
        try {
            FileOutputStream(File(folder, name + ".png")).use { out ->
                image.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            image.recycle()
        }
    }

    @Test
    @Throws(Exception::class)
    fun entersAndMeasuresWithoutStartingOrSavingATrack() {
        val app = app()
        val tracks = app.store.tracks().size
        Assert.assertNull(TrackService.Companion.active)
        try {
            ActivityScenario.launch<MainActivity?>(MainActivity::class.java).use { scenario ->
                enter(scenario)
                sample(scenario, 31.0, 3f)
                sample(scenario, 31.00004, 5f)
                sample(scenario, 31.00008, 4f)
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    val s = a!!.speedStats()
                    Assert.assertTrue(s!!.validPoints >= 3)
                    Assert.assertTrue(s.distance > 0)
                    Assert.assertTrue(java.lang.Double.isFinite(s.averageMps))
                    Assert.assertEquals(5.0, s.maxMps, 0.0)
                    val stats = a.body.findViewWithTag<TextView>("large-speed-session-stats")
                    val current = a.body.findViewWithTag<TextView>("large-speed-current")
                    val mps = a.body.findViewWithTag<TextView>("large-speed-current-mps")
                    Assert.assertTrue(stats!!.getText().toString().contains("自动统计中"))
                    Assert.assertTrue(stats.getText().toString().contains("最高速度  18.0 km/h"))
                    Assert.assertFalse(stats.getText().toString().contains("未开始记录"))
                    Assert.assertEquals("14.4", current!!.getText().toString())
                    Assert.assertEquals("4.00 m/s", mps!!.getText().toString())
                    Assert.assertEquals(Gravity.CENTER.toLong(), current.getGravity().toLong())
                    Assert.assertEquals(Gravity.CENTER.toLong(), mps.getGravity().toLong())
                    Assert.assertNull(TrackService.Companion.active)
                    Assert.assertNull(a.e.recorder)
                    Assert.assertEquals(tracks.toLong(), app.store.tracks().size.toLong())
                })
                shot("independent-speed-session-dual-units")
            }
        } finally {
            app.engine.location = null
        }
    }

    @Test
    fun backAndTabNavigationClearAndReentryStartsAtZero() {
        try {
            ActivityScenario.launch<MainActivity?>(MainActivity::class.java).use { scenario ->
                enter(scenario)
                sample(scenario, 31.0, 4f)
                sample(scenario, 31.00004, 4f)
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    Assert.assertTrue(a!!.speedStats().distance > 0)
                    a.navigate("")
                    Assert.assertNull(a.speedStats())
                })
                enter(scenario)
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    Assert.assertEquals(0.0, a!!.speedStats().distance, 0.0)
                    Assert.assertEquals(0, a.speedStats().validPoints.toLong())
                    Assert.assertTrue(java.lang.Double.isNaN(a.speedStats().maxMps))
                    a.goPage(0)
                    Assert.assertNull(a.speedStats())
                })
                enter(scenario)
                sample(scenario, 32.0, 2f)
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    Assert.assertEquals(2.0, a!!.speedStats().maxMps, 0.0)
                    Assert.assertEquals(0.0, a.speedStats().distance, 0.0)
                })
            }
        } finally {
            app().engine.location = null
        }
    }

    @Test
    fun recreationAndBackgroundDoNotResetOrConnectHiddenMovement() {
        val distance = doubleArrayOf(0.0)
        try {
            ActivityScenario.launch<MainActivity?>(MainActivity::class.java).use { scenario ->
                enter(scenario)
                sample(scenario, 31.0, 4f)
                sample(scenario, 31.00004, 4f)
                scenario.onActivity(ActivityAction { a: MainActivity? -> distance[0] = a!!.speedStats().distance })
                scenario.recreate()
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    Assert.assertEquals(distance[0], a!!.speedStats().distance, .001)
                    Assert.assertEquals(4.0, a.speedStats().maxMps, 0.0)
                    a.theme()
                    Assert.assertEquals(distance[0], a.speedStats().distance, .001)
                })
                scenario.moveToState(Lifecycle.State.CREATED)
                SystemClock.sleep(1000)
                InstrumentationRegistry.getInstrumentation()
                    .runOnMainSync(Runnable { app().engine.onLocationChanged(fix(32.0, 40f)) })
                scenario.moveToState(Lifecycle.State.RESUMED)
                sample(scenario, 32.00004, 2f)
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    Assert.assertEquals(distance[0], a!!.speedStats().distance, .001)
                    Assert.assertEquals(4.0, a.speedStats().maxMps, 0.0)
                    a.navigate("")
                    Assert.assertNull(a.speedStats())
                })
            }
        } finally {
            app().engine.location = null
        }
    }

    @Test
    fun mockAndInvalidFixesCannotCreateSessionStatistics() {
        try {
            ActivityScenario.launch<MainActivity?>(MainActivity::class.java).use { scenario ->
                enter(scenario)
                SystemClock.sleep(50)
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    val mock = fix(31.0, 50f)
                    mock.setMock(true)
                    a!!.e.onLocationChanged(mock)
                    a.refresh()
                    Assert.assertEquals(0, a.speedStats().validPoints.toLong())
                    Assert.assertTrue(java.lang.Double.isNaN(a.speedStats().maxMps))
                })
                SystemClock.sleep(50)
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    val poor = fix(31.0, 40f)
                    poor.setAccuracy(1000f)
                    a!!.e.onLocationChanged(poor)
                    a.refresh()
                    Assert.assertEquals(0, a.speedStats().validPoints.toLong())
                    Assert.assertEquals(0.0, a.speedStats().distance, 0.0)
                })
                sample(scenario, 31.00004, 3f)
                scenario.onActivity(ActivityAction { a: MainActivity? ->
                    Assert.assertEquals(3.0, a!!.speedStats().maxMps, 0.0)
                    Assert.assertEquals(0.0, a.speedStats().distance, 0.0)
                })
            }
        } finally {
            app().engine.location = null
        }
    }
}

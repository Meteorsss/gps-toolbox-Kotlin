package com.meteorsss.startrace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.ActivityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.Double
import java.util.function.Predicate
import kotlin.Exception
import kotlin.Long
import kotlin.String
import kotlin.Throws
import kotlin.booleanArrayOf
import kotlin.io.use
import kotlin.use

@RunWith(AndroidJUnit4::class)
class MapFeatureTest {
    private fun context(): Context? {
        return InstrumentationRegistry.getInstrumentation().getTargetContext()
    }

    private fun await(
        scenario: ActivityScenario<MapActivity?>,
        check: Predicate<MapActivity?>,
        label: String
    ) {
        val deadline = SystemClock.elapsedRealtime() + 45000
        val ready = booleanArrayOf(false)
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(ActivityAction { a: MapActivity? -> ready[0] = check.test(a) })
            if (ready[0]) return
            SystemClock.sleep(100)
        }
        Assert.fail("Timed out: " + label)
    }

    private fun fixture(): Long {
        val app = context()!!.getApplicationContext() as App
        val id = app.store.begin("Synthetic map test")
        for (i in 0..19) app.store.add(
            id,
            TrackPoint(
                37.7749 + i * .00012,
                -122.4194 + i * .00008,
                (100 + i).toDouble(),
                3.0,
                2.0,
                1700000000000L + i * 1000,
                (1000 + i * 1000).toLong(),
                if (i < 10) 0 else 1,
                true,
                2.0,
                (70 + i).toDouble(),
                2.0,
                .1
            ),
            false
        )
        app.store.update(id, 19000, 280.0, "finished")
        return id
    }

    @Throws(Exception::class)
    private fun shot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(800)
        val image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot()
        if (image != null) {
            val dir = File(context()!!.getExternalFilesDir(null), "screenshots")
            dir.mkdirs()
            FileOutputStream(File(dir, name + ".png")).use { out ->
                image.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            image.recycle()
        }
    }

    @Test
    fun migrationPreservesVersionOneDataAndUnknownHeight() {
        val name = "migration-test.db"
        context()!!.deleteDatabase(name)
        val file = context()!!.getDatabasePath(name)
        file.getParentFile().mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                ("CREATE TABLE tracks(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,start"
                        + " INTEGER NOT NULL,end INTEGER NOT NULL DEFAULT 0,duration INTEGER NOT NULL DEFAULT"
                        + " 0,distance REAL NOT NULL DEFAULT 0,status TEXT NOT NULL)")
            )
            db.execSQL(
                ("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT,track INTEGER NOT NULL,lat REAL"
                        + " NOT NULL,lon REAL NOT NULL,altitude REAL,accuracy REAL,speed REAL,time INTEGER"
                        + " NOT NULL,elapsed INTEGER NOT NULL,segment INTEGER NOT NULL,accepted INTEGER NOT"
                        + " NULL,mock INTEGER NOT NULL)")
            )
            db.execSQL(
                "CREATE TABLE places(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,note TEXT"
                        + " NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,time INTEGER NOT NULL)"
            )
            db.execSQL("INSERT INTO tracks VALUES(1,'old',1000,9000,8000,50,'finished')")
            db.execSQL("INSERT INTO points VALUES(1,1,30,110,100,3,2,1000,1000,0,1,0)")
            db.execSQL("INSERT INTO places VALUES(1,'saved','note',30,110,1000)")
            db.setVersion(1)
        }
        try {
            Store(context(), name).use { store ->
                Assert.assertEquals(2, store.getReadableDatabase().getVersion().toLong())
                Assert.assertEquals("old", store.track(1).name)
                Assert.assertEquals(50.0, store.track(1).distance, 0.0)
                Assert.assertEquals("saved", store.places().get(0).name)
                Assert.assertTrue(Double.isNaN(store.preview(1, 100).get(0).verticalAccuracy))
                Assert.assertTrue(Double.isNaN(store.analyze(1, 8000, 50.0).ascent))
            }
        } finally {
            context()!!.deleteDatabase(name)
        }
    }

    @Test
    @Throws(Exception::class)
    fun mapConsentDefaultsOffAndOfflineLifecyclePreservesTrack() {
        MapPolicy.allow(context(), false)
        Assert.assertFalse(MapPolicy.allowed(context()))
        val id = fixture()
        try {
            ActivityScenario.launch<MapActivity?>(
                Intent(context(), MapActivity::class.java)
                    .putExtra("track_id", id)
                    .putExtra("test_local_style", true)
            ).use { scenario ->
                await(
                    scenario,
                    Predicate { a: MapActivity? -> a!!.readyForTests() && a.trackPointsForTests() == 20 },
                    "local map and track ready"
                )
                scenario.onActivity(ActivityAction { a: MapActivity? ->
                    Assert.assertFalse(a!!.onlineForTests())
                    Assert.assertEquals(2, a.trackSegmentsForTests().toLong())
                })
                shot("map-local-track")
                scenario.onActivity(ActivityAction { a: MapActivity? -> Assert.assertTrue(a!!.cameraZoomForTests() > 3) })
                scenario.recreate()
                await(
                    scenario,
                    Predicate { a: MapActivity? -> a!!.readyForTests() && a.trackPointsForTests() == 20 && a.cameraZoomForTests() > 3 },
                    "recreated map retains camera"
                )
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(scenario, Predicate { obj: Predicate<in T?>? -> obj.readyForTests() }, "resumed map")
                Assert.assertFalse(MapPolicy.allowed(context()))
            }
        } finally {
            (context()!!.getApplicationContext() as App).store.delete(id)
        }
    }

    @Test
    @Throws(Exception::class)
    fun newVerticalFieldsSurviveStorageAndCsv() {
        val app = context()!!.getApplicationContext() as App
        val id = fixture()
        try {
            val p = app.store.preview(id, 100).get(0)
            Assert.assertEquals(2.0, p.verticalAccuracy, 0.0)
            Assert.assertEquals(70.0, p.mslAltitude, 0.0)
            Assert.assertEquals(.1, p.speedAccuracy, 0.0)
            val stream: ByteArrayOutputStream? = ByteArrayOutputStream()
            app.store.export(id, stream, true)
            Assert.assertTrue(stream!!.toString("UTF-8").contains("msl_altitude_m"))
            Assert.assertTrue(app.store.analyze(id, 19000, 280.0).msl)
        } finally {
            app.store.delete(id)
        }
    }

    private fun button(view: View?, label: String): TextView? {
        if (view is TextView && label.contentEquals(view.getText())) return view
        if (view is ViewGroup) {
            val group = view
            for (i in 0..<group.getChildCount()) {
                val found = button(group.getChildAt(i), label)
                if (found != null) return found
            }
        }
        return null
    }

    @Test
    fun historicalMapRecenterRemainsLiveAfterRecreation() {
        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation()
            .grantRuntimePermission(
                context()!!.getPackageName(), Manifest.permission.ACCESS_COARSE_LOCATION
            )
        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation()
            .grantRuntimePermission(
                context()!!.getPackageName(), Manifest.permission.ACCESS_FINE_LOCATION
            )
        MapPolicy.allow(context(), false)
        val id = fixture()
        try {
            ActivityScenario.launch<MapActivity?>(
                Intent(context(), MapActivity::class.java)
                    .putExtra("track_id", id)
                    .putExtra("test_local_style", true)
            ).use { scenario ->
                await(
                    scenario,
                    Predicate { a: MapActivity? -> a!!.readyForTests() && a.trackPointsForTests() == 20 },
                    "historical map"
                )
                scenario.onActivity(ActivityAction { a: MapActivity? ->
                    Assert.assertFalse(a!!.followingForTests())
                    val recenter = button(a.getWindow().getDecorView(), "回到当前位置")
                    Assert.assertNotNull(recenter)
                    recenter!!.performClick()
                    Assert.assertTrue(a.followingForTests())
                })
                scenario.recreate()
                await(
                    scenario,
                    Predicate { a: MapActivity? -> a!!.readyForTests() && a.trackPointsForTests() == 20 },
                    "recentered map recreated"
                )
                scenario.onActivity(ActivityAction { a: MapActivity? -> Assert.assertTrue(a!!.followingForTests()) })
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity(ActivityAction { a: MapActivity? -> Assert.assertTrue(a!!.followingForTests()) })
                Assert.assertFalse(MapPolicy.allowed(context()))
            }
        } finally {
            (context()!!.getApplicationContext() as App).store.delete(id)
        }
    }

    @Test
    fun liveMapStatisticsRefreshAndSettleAfterRecordingStops() {
        MapPolicy.allow(context(), false)
        val app = context()!!.getApplicationContext() as App
        val id = fixture()
        val previous: TrackService? = TrackService.Companion.active
        // Synthetic in-memory recorder state only: no real GPS recording or user coordinates.
        val live = TrackService()
        live.trackId = id
        live.paused = true
        live.distance = 100.0
        InstrumentationRegistry.getInstrumentation().runOnMainSync(Runnable { TrackService.Companion.active = live })
        try {
            ActivityScenario.launch<MapActivity?>(
                Intent(context(), MapActivity::class.java).putExtra("test_local_style", true)
            ).use { scenario ->
                await(
                    scenario,
                    Predicate { a: MapActivity? -> a!!.readyForTests() && a.trackPointsForTests() == 20 },
                    "live map"
                )
                scenario.onActivity(ActivityAction { a: MapActivity? ->
                    val statistics = button(a!!.getWindow().getDecorView(), "详细统计")
                    Assert.assertNotNull(statistics)
                    statistics!!.performClick()
                    Assert.assertTrue(a.statisticsForTests().contains(Geo.meters(100.0)))
                    live.distance = 200.0
                })
                await(
                    scenario,
                    Predicate { a: MapActivity? -> a!!.statisticsForTests().contains(Geo.meters(200.0)) },
                    "statistics update without reopening"
                )
                scenario.moveToState(Lifecycle.State.CREATED)
                InstrumentationRegistry.getInstrumentation().runOnMainSync(Runnable { live.distance = 300.0 })
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(
                    scenario,
                    Predicate { a: MapActivity? -> a!!.statisticsForTests().contains(Geo.meters(300.0)) },
                    "statistics refresh on resume"
                )
                app.store.update(id, 19000, 450.0, "finished")
                scenario.onActivity(ActivityAction { a: MapActivity? -> TrackService.Companion.active = null })
                await(
                    scenario,
                    Predicate { a: MapActivity? -> a!!.statisticsForTests().contains(Geo.meters(450.0)) },
                    "final persisted statistics"
                )
                scenario.onActivity(ActivityAction { obj: MapActivity? -> obj!!.closeStatisticsForTests() })
                await(
                    scenario,
                    Predicate { a: MapActivity? -> a!!.statisticsForTests().isEmpty() },
                    "statistics dialog dismissed"
                )
            }
        } finally {
            InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(Runnable { TrackService.Companion.active = previous })
            app.store.delete(id)
        }
    }

    @Test
    @Throws(Exception::class)
    fun onlineOpenFreeMapLoadsWithConsent() {
        MapPolicy.allow(context(), true)
        val id = fixture()
        try {
            ActivityScenario.launch<MapActivity?>(
                Intent(context(), MapActivity::class.java).putExtra("track_id", id)
            ).use { scenario ->
                await(
                    scenario,
                    Predicate { a: MapActivity? -> a!!.readyForTests() && a.trackPointsForTests() == 20 && a.renderedForTests() },
                    "OpenFreeMap native style rendering"
                )
                scenario.onActivity(ActivityAction { a: MapActivity? -> Assert.assertTrue(a!!.onlineForTests()) })
                shot("map-openfreemap-track")
            }
        } finally {
            MapPolicy.allow(context(), false)
            (context()!!.getApplicationContext() as App).store.delete(id)
        }
    }
}

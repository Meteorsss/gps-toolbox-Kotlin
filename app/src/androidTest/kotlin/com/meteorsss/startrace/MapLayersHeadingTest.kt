package com.meteorsss.startrace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import java.io.File

/** All locations and tracks are synthetic. Online rendering uses only normal visible viewports. */
@RunWith(AndroidJUnit4::class)
class MapLayersHeadingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as App
    private val tracks = mutableListOf<Long>()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(label: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main { ready = check() }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("Timed out: $label")
    }

    private fun awaitMap(scenario: ActivityScenario<MapActivity>, online: Boolean = false, expectedPoints: Int = -1) {
        var current: MapActivity? = null
        scenario.onActivity { current = it }
        await("map rendered") { current!!.readyForTests() && (!online || current!!.renderedForTests()) &&
            (expectedPoints < 0 || current!!.trackPointsForTests() == expectedPoints) }
    }

    private fun assertCamera(expected: CameraPosition, actual: CameraPosition) {
        assertEquals(expected.target!!.latitude, actual.target!!.latitude, .000001)
        assertEquals(expected.target!!.longitude, actual.target!!.longitude, .000001)
        assertEquals(expected.zoom, actual.zoom, .000001)
        assertEquals(expected.bearing, actual.bearing, .000001)
        assertEquals(expected.tilt, actual.tilt, .000001)
    }

    @Before fun prepare() {
        for (permission in arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS)) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, permission)
        main {
            MapPolicy.allow(app, false)
            MapPolicy.select(app, MapBasemap.OSM_STANDARD)
            Preferences.backgroundLocation(app, false)
            app.stopService(Intent(app, LocationHoldService::class.java))
            app.stopService(Intent(app, TrackService::class.java))
        }
        await("services stopped") { LocationHoldService.active == null && TrackService.active == null }
    }

    @After fun cleanup() {
        main {
            MapPolicy.allow(app, false)
            MapPolicy.select(app, MapBasemap.OSM_STANDARD)
            Preferences.backgroundLocation(app, false)
            app.stopService(Intent(app, LocationHoldService::class.java))
            app.stopService(Intent(app, TrackService::class.java))
        }
        await("services stopped") { LocationHoldService.active == null && TrackService.active == null }
        tracks.forEach { app.store.delete(it) }
    }

    private fun sample(ageMs: Long = 0, latitude: Double = 37.7749): Location {
        // Keep the emulator GPS from replacing deterministic synthetic test fixes.
        app.getSystemService(LocationManager::class.java).removeUpdates(app.engine)
        return Location("gps").apply {
            this.latitude = latitude; longitude = -122.4194
            accuracy = 3f; speed = 3f; speedAccuracyMetersPerSecond = .1f
            bearing = 270f // Deliberately opposite the test phone heading.
            time = System.currentTimeMillis() - ageMs
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMs * 1000000
            app.engine.onLocationChanged(this)
        }
    }

    private fun fixture(): Long {
        val id = app.store.begin("Synthetic heading track")
        tracks += id
        for (i in 0..3) app.store.add(id, TrackPoint(37.7749 + i * .0001, -122.4194,
            10.0, 3.0, 3.0, 1700000000000L + i * 1000, 1000L + i * 1000,
            0, true, 1.0, 270.0, 1.0, .1), false)
        app.store.update(id, 3000, 33.0, "finished")
        return id
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun oldConsentDoesNotSilentlyAuthorizeNewProvider() {
        val prefs = app.getSharedPreferences("map-policy", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("online-consent-v1", true).remove("online-consent-v2").commit()
        assertFalse(MapPolicy.allowed(app))
        MapPolicy.allow(app, true)
        assertTrue(MapPolicy.allowed(app))
        MapPolicy.allow(app, false)
        assertFalse(MapPolicy.allowed(app))
    }

    @Test fun osmStandardAndOpenFreeMapSwitchWithoutResettingCameraTrackOrGnss() {
        val id = fixture()
        MapPolicy.allow(app, true)
        ActivityScenario.launch<MapActivity>(Intent(app, MapActivity::class.java).putExtra("track_id", id)).use { scenario ->
            awaitMap(scenario, true, 4)
            var starts = 0
            var fixAt = 0L
            val camera = CameraPosition.Builder().target(LatLng(37.775, -122.4194)).zoom(16.0).bearing(125.0).tilt(30.0).build()
            scenario.onActivity {
                sample(); starts = app.engine.subscriptionStarts; fixAt = app.engine.location!!.elapsedRealtimeNanos
                it.moveCameraForTests(camera)
                assertEquals(0, it.prefetchForTests())
                assertEquals(MapBasemap.OSM_STANDARD, it.basemapForTests())
                assertTrue(it.attributionForTests().contains("© OpenStreetMap contributors"))
                assertFalse(it.attributionForTests().contains("OpenFreeMap"))
                it.injectHeadingForTests(90.0)
            }
            shot("map-osm-standard-heading")
            scenario.onActivity { it.selectBasemapForTests(MapBasemap.OPEN_FREE_MAP) }
            awaitMap(scenario, true, 4)
            scenario.onActivity {
                assertCamera(camera, it.cameraForTests())
                assertEquals(4, it.trackPointsForTests())
                assertEquals(starts, app.engine.subscriptionStarts)
                assertEquals(fixAt, app.engine.location!!.elapsedRealtimeNanos)
                assertTrue(it.attributionForTests().contains("OpenFreeMap"))
                assertEquals(MapBasemap.OPEN_FREE_MAP, MapPolicy.selected(app))
                it.selectBasemapForTests(MapBasemap.OSM_STANDARD)
            }
            awaitMap(scenario, true, 4)
            scenario.onActivity {
                assertCamera(camera, it.cameraForTests())
                assertEquals(starts, app.engine.subscriptionStarts)
                it.revokeOnlineForTests()
            }
            awaitMap(scenario)
            scenario.onActivity {
                assertFalse(it.onlineForTests()); assertCamera(camera, it.cameraForTests())
                assertEquals(4, it.trackPointsForTests())
            }
            scenario.recreate()
            awaitMap(scenario)
            scenario.onActivity {
                assertCamera(camera, it.cameraForTests())
                assertEquals(MapBasemap.OSM_STANDARD, it.basemapForTests())
            }
        }
    }

    @Test fun phoneHeadingIsMapAlignedNotTravelBearingAndNeverMarksHistoricalEndpoint() {
        val id = fixture()
        ActivityScenario.launch<MapActivity>(Intent(app, MapActivity::class.java)
            .putExtra("track_id", id).putExtra("test_local_style", true)).use { scenario ->
            awaitMap(scenario, expectedPoints = 4)
            scenario.onActivity {
                sample()
                it.moveCameraForTests(CameraPosition.Builder().target(LatLng(37.7749, -122.4194)).zoom(17.0).bearing(90.0).build())
                it.injectHeadingForTests(90.0)
                assertTrue(it.headingVisibleForTests())
                assertEquals(90f, it.headingAngleForTests()!!, 0f)
                assertEquals("map", it.headingAlignmentForTests())
                assertEquals(270f, app.engine.location!!.bearing, 0f)
            }
            shot("map-rotated-phone-heading")
            scenario.onActivity {
                sample(ageMs = 20000)
                assertFalse(it.headingVisibleForTests())
                // Historical track/endpoints still exist, but are not used as current location.
                assertEquals(4, it.trackPointsForTests())
            }
            shot("map-stale-location-no-arrow")
        }
    }

    @Test fun headingFramesStopInBackgroundWhileLocationHoldContinues() {
        ActivityScenario.launch<MapActivity>(Intent(app, MapActivity::class.java).putExtra("test_local_style", true)).use { scenario ->
            awaitMap(scenario)
            var current: MapActivity? = null
            var frames = 0L
            var starts = 0
            scenario.onActivity {
                current = it; sample(); starts = app.engine.subscriptionStarts
                BackgroundLocation.enable(it)
            }
            await("hold service") { LocationHoldService.active != null }
            scenario.moveToState(Lifecycle.State.CREATED)
            main { frames = current!!.headingFramesForTests(); assertFalse(app.engine.compassSubscribed()) }
            SystemClock.sleep(1800)
            main {
                assertEquals(frames, current!!.headingFramesForTests())
                assertTrue(app.engine.locationSubscribed()); assertEquals(starts, app.engine.subscriptionStarts)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            await("heading resumes") { current!!.headingFramesForTests() > frames }
            scenario.onActivity { assertEquals(starts, app.engine.subscriptionStarts) }
        }
    }

    @Test fun switchingStylesDuringRecordingKeepsTheSameServiceAndSession() {
        Preferences.interval(app, 1)
        ActivityScenario.launch<MapActivity>(Intent(app, MapActivity::class.java).putExtra("test_local_style", true)).use { scenario ->
            awaitMap(scenario)
            scenario.onActivity { it.startForegroundService(Intent(it, TrackService::class.java).setAction("START")) }
            await("track started") { TrackService.active?.trackId?.let { it > 0 } == true }
            var recorder: TrackService? = null
            var id = -1L
            var starts = 0
            scenario.onActivity {
                recorder = TrackService.active; id = recorder!!.trackId; tracks += id
                starts = app.engine.subscriptionStarts
                sample(); it.selectBasemapForTests(MapBasemap.OPEN_FREE_MAP)
            }
            awaitMap(scenario)
            SystemClock.sleep(1100)
            scenario.onActivity {
                sample(latitude = 37.77493)
                assertSame(recorder, TrackService.active); assertEquals(id, TrackService.active!!.trackId)
                assertEquals(starts, app.engine.subscriptionStarts)
                it.selectBasemapForTests(MapBasemap.OSM_STANDARD)
            }
            awaitMap(scenario)
            scenario.onActivity {
                assertSame(recorder, TrackService.active); assertEquals(starts, app.engine.subscriptionStarts)
                it.startService(Intent(it, TrackService::class.java).setAction("STOP"))
            }
            await("track stopped") { TrackService.active == null }
            assertTrue(app.store.preview(id, 100).isNotEmpty())
        }
    }
}

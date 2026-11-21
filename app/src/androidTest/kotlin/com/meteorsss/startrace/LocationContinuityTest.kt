package com.meteorsss.startrace

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Synthetic fixes via the real callback; no actual user location is used or exported. */
@RunWith(AndroidJUnit4::class)
class LocationContinuityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as App
    private val tracks = mutableListOf<Long>()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            while (it.read() != -1) { }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(80)
        }
        fail(message)
    }

    @Before fun prepare() {
        for (permission in arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, permission)
        shell("cmd location set-location-enabled true")
        main {
            Preferences.backgroundLocation(app, false)
            app.stopService(Intent(app, LocationHoldService::class.java))
            app.stopService(Intent(app, TrackService::class.java))
            Preferences.interval(app, 1)
        }
        await("previous services stopped") { LocationHoldService.active == null && TrackService.active == null }
    }

    @After fun cleanup() {
        main {
            Preferences.backgroundLocation(app, false)
            app.stopService(Intent(app, LocationHoldService::class.java))
            app.stopService(Intent(app, TrackService::class.java))
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MapActivity>().forEach { it.finish() }
        }
        await("services stopped") { LocationHoldService.active == null && TrackService.active == null }
        tracks.forEach { app.store.delete(it) }
    }

    private fun sample(latitude: Double = 31.0, ageMs: Long = 0): Location {
        val fix = Location("gps")
        fix.latitude = latitude; fix.longitude = 121.0
        fix.accuracy = 3f; fix.speed = 4f; fix.speedAccuracyMetersPerSecond = .1f
        fix.time = 1700000000000L + SystemClock.elapsedRealtime() - ageMs
        fix.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMs * 1000000L
        app.getSystemService(LocationManager::class.java).removeUpdates(app.engine)
        app.engine.onLocationChanged(fix)
        return fix
    }

    private fun startTrack(scenario: ActivityScenario<MainActivity>): Long {
        scenario.onActivity { it.startForegroundService(Intent(it, TrackService::class.java).setAction("START")) }
        await("recording service started") { TrackService.active?.trackId?.let { it > 0 } == true }
        var id = -1L
        main { id = TrackService.active!!.trackId; tracks.add(id) }
        return id
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        FileOutputStream(File(directory, "$name.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun dashboardMapAndBackDoNotRestartGnssOrClearFix() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var initialStarts = 0
            var elapsed = 0L
            scenario.onActivity {
                sample()
                initialStarts = it.e.subscriptionStarts
                elapsed = it.e.location!!.elapsedRealtimeNanos
                it.openMap(-1)
            }
            await("map resumed") {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MapActivity }
            }
            main {
                assertTrue(app.engine.locationSubscribed())
                assertEquals(initialStarts, app.engine.subscriptionStarts)
                assertEquals(elapsed, app.engine.location!!.elapsedRealtimeNanos)
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MapActivity>().single().finish()
            }
            await("dashboard resumed") {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
            }
            scenario.onActivity {
                assertEquals(initialStarts, it.e.subscriptionStarts)
                assertEquals(elapsed, it.e.location!!.elapsedRealtimeNanos)
                assertTrue(it.e.fresh())
            }
        }
    }

    @Test fun recreationRetainsGnssSessionAndFix() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var starts = 0; var time = 0L
            scenario.onActivity { sample(); starts = it.e.subscriptionStarts; time = it.e.location!!.elapsedRealtimeNanos }
            scenario.recreate()
            scenario.onActivity {
                assertEquals(starts, it.e.subscriptionStarts)
                assertEquals(time, it.e.location!!.elapsedRealtimeNanos)
                assertTrue(it.e.fresh())
            }
        }
    }

    @Test fun lastFixSurvivesRealRestartButExpiredDataStaysExpired() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val fix = sample(ageMs = 20000)
                val starts = it.e.subscriptionStarts
                it.e.setVisible(false)
                assertFalse(it.e.locationSubscribed())
                it.e.setVisible(true)
                assertEquals(starts + 1, it.e.subscriptionStarts)
                assertEquals(fix.elapsedRealtimeNanos, it.e.location!!.elapsedRealtimeNanos)
                assertFalse(it.e.fresh())
                assertTrue(it.e.age() >= 20000)
            }
        }
    }

    @Test fun continuousLocationSurvivesHomeAndReturnWithoutCreatingTracks() {
        val count = app.store.tracks().size
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var starts = 0
            scenario.onActivity { assertTrue(BackgroundLocation.enable(it)); it.navigate("compass") }
            await("location hold started") { LocationHoldService.active != null }
            main { sample(); starts = app.engine.subscriptionStarts }
            shell("input keyevent KEYCODE_HOME")
            await("home stopped activities") { app.visibleActivityCount() == 0 }
            SystemClock.sleep(1900) // Longer than the intentional Activity handoff grace.
            main {
                assertTrue(app.engine.locationSubscribed())
                assertEquals(starts, app.engine.subscriptionStarts)
                assertFalse(app.engine.compassSubscribed())
                assertNull(TrackService.active)
                sample(31.00005)
                assertTrue(app.engine.fresh())
            }
            shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n ${app.packageName}/.MainActivity")
            await("dashboard visible again") { app.visibleActivityCount() > 0 }
            main {
                assertEquals(starts, app.engine.subscriptionStarts)
                assertEquals(31.00005, app.engine.location!!.latitude, .00000001)
                assertEquals(count, app.store.tracks().size)
            }
            scenario.onActivity { it.navigate("settings") }
            shot("background-location-settings")
        }
    }

    @Test fun actualRecorderKeepsSavingAfterActivityStopsWithoutAlwaysPermission() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val id = startTrack(scenario)
            var starts = 0; var raw = 0
            scenario.onActivity { sample(); starts = it.e.subscriptionStarts; raw = app.store.track(id)!!.raw }
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(1900)
            main {
                assertTrue(app.engine.locationSubscribed())
                assertEquals(starts, app.engine.subscriptionStarts)
                assertNotNull(TrackService.active)
                sample(31.00005)
                assertTrue(app.store.track(id)!!.raw > raw)
                assertEquals("recording", app.store.track(id)!!.status)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertEquals(starts, it.e.subscriptionStarts) }
        }
    }

    @Test fun liveLocationAndRecorderOwnTheirSubscriptionsIndependently() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { BackgroundLocation.enable(it) }
            await("hold active") { LocationHoldService.active != null }
            val id = startTrack(scenario)
            scenario.onActivity { BackgroundLocation.stop(it) }
            await("hold stopped") { LocationHoldService.active == null }
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(1900)
            main {
                assertTrue(app.engine.locationSubscribed())
                assertEquals(id, TrackService.active!!.trackId)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { BackgroundLocation.enable(it) }
            await("hold active again") { LocationHoldService.active != null }
            scenario.onActivity { it.startService(Intent(it, TrackService::class.java).setAction("STOP")) }
            await("track finished") { TrackService.active == null }
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(1900)
            main {
                assertTrue(app.engine.locationSubscribed())
                assertNotNull(LocationHoldService.active)
                assertEquals("finished", app.store.track(id)!!.status)
            }
        }
    }

    @Test fun notificationStopStopsOnlyOptedInLocationAndDoesNotRestartOnReturn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { BackgroundLocation.enable(it) }
            await("hold active") { LocationHoldService.active != null }
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(1900)
            main {
                val notification = app.getSystemService(NotificationManager::class.java).activeNotifications
                    .single { it.id == LocationHoldService.NOTIFICATION_ID }.notification
                notification.actions.single().actionIntent.send()
            }
            await("notification stopped location") {
                LocationHoldService.active == null && !app.engine.locationSubscribed()
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertFalse(Preferences.backgroundLocation(it))
                assertNull(LocationHoldService.active)
                assertTrue(it.e.locationSubscribed())
            }
        }
    }

    @Test fun permissionRationaleHasExplicitDeclineAndNoSilentBackgroundGrant() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val permissions = it.packageManager.getPackageInfo(it.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
                assertTrue(permissions.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                assertEquals("package:${it.packageName}", BackgroundLocation.settingsIntent(it).data.toString())
                val before = BackgroundLocation.alwaysAllowed(it)
                val dialog = BackgroundLocation.showAlwaysPermission(it)!!
                assertNotNull(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertEquals(before, BackgroundLocation.alwaysAllowed(it))
                assertFalse(Preferences.backgroundLocation(it))
                assertNull(LocationHoldService.active)
                it.navigate("settings")
                assertTrue(it.visibleText().contains("始终允许定位"))
                assertTrue(it.visibleText().contains("后台持续定位"))
            }
        }
    }
}

package com.meteorsss.startrace

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location!!.Location
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.meteorsss.startrace.GnssEngine.FixListener
import java.text.SimpleDateFormat
import java.util.*

class TrackService : Service() {
    private var app: App? = null
    var trackId: Long = -1
    var paused: Boolean = false
    var distance: Double = 0.0
    private var accumulated: Long = 0
    private var resumed: Long = 0
    private var lastElapsed: Long = -1
    private var segment = 0
    private var previous: TrackPoint? = null
    private var anchor: TrackPoint? = null
    private var ending = false
    private var unsavedBreak = false
    private val cadence = RecordingCadence()
    private var intervalSeconds = 1
    private val analytics = TrackAnalytics()

    fun insights(): TrackAnalytics.Result {
        return analytics.finish(distance, duration())
    }

    fun intervalSeconds(): Int {
        return intervalSeconds
    }

    fun setIntervalSeconds(seconds: Int) {
        val value: Int = RecordingCadence.Companion.sanitize(seconds)
        if (value == intervalSeconds) return
        intervalSeconds = value
        cadence.reset()
        unsavedBreak = false
        if (trackId >= 0) {
            segment++
            previous = null
            anchor = null
        }
    }

    fun prepareForTests(application: App, id: Long) {
        app = application
        trackId = id
        accumulated = 60000 // Synthetic fixture has one minute of prior active recording.
        resumed = SystemClock.elapsedRealtime()
    }

    fun fixForTests(location: Location) {
        onFix(location)
    }

    fun duration(): Long {
        return accumulated + (if (!paused && resumed > 0) SystemClock.elapsedRealtime() - resumed else 0)
    }

    override fun onCreate() {
        super.onCreate()
        app = getApplication() as App
        getSystemService<NotificationManager?>(NotificationManager::class.java)!!
            .createNotificationChannel(
                NotificationChannel("recording", "轨迹记录", NotificationManager.IMPORTANCE_LOW)
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = if (intent == null) "" else intent.getAction()
        if ("STOP" == action) {
            finish("finished")
            return START_NOT_STICKY
        }
        if ("PAUSE" == action) {
            pause()
            return START_NOT_STICKY
        }
        if ("RESUME" == action) {
            resume()
            return START_NOT_STICKY
        }
        if (trackId != -1L) return START_NOT_STICKY
        if (!app!!.engine.precise() || !app!!.engine.gpsEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            intervalSeconds = Preferences.interval(this)
            startForeground(11, notification()!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            trackId =
                app!!.store.begin(
                    "轨迹 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
                )
            resumed = SystemClock.elapsedRealtime()
            active = this
            app!!.engine.recorder = FixListener { location: Location? -> this.onFix(location!!) }
            app!!.engine.setRecording(true)
            app!!.main.post(ticker)
        } catch (e: RuntimeException) {
            if (trackId != -1L) app!!.store.update(trackId, duration(), distance, "interrupted")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private val ticker: Runnable = object : Runnable {
        override fun run() {
            if (ending || trackId == -1L) return
            if (!app!!.engine.precise()) {
                finish("interrupted")
                return
            }
            app!!.store.update(trackId, duration(), distance, if (paused) "paused" else "recording")
            getSystemService<NotificationManager?>(NotificationManager::class.java)!!.notify(11, notification())
            app!!.main.postDelayed(this, 5000)
        }
    }

    private fun onFix(location: Location) {
        if (paused || ending || trackId < 0) return
        val elapsed = location.getElapsedRealtimeNanos() / 1000000L
        if (elapsed <= lastElapsed) return
        lastElapsed = elapsed
        val candidate =
            TrackPoint(
                location.getLatitude(),
                location.getLongitude(),
                if (location.hasAltitude()) location.getAltitude() else Double.NaN,
                if (location.hasAccuracy()) location.getAccuracy().toDouble() else Double.NaN,
                if (location.hasSpeed()) location.getSpeed().toDouble() else Double.NaN,
                location.getTime(),
                elapsed,
                segment,
                false,
                if (location.hasVerticalAccuracy()) location.getVerticalAccuracyMeters().toDouble() else Double.NaN,
                if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude())
                    location.getMslAltitudeMeters()
                else Double.NaN,
                if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitudeAccuracy())
                    location.getMslAltitudeAccuracyMeters()
                        .toDouble()
                else Double.NaN,
                if (location.hasSpeedAccuracy()) location.getSpeedAccuracyMetersPerSecond().toDouble() else Double.NaN
            )
        val good =
            !location.isMock() && TrackFilter.usable(candidate, SystemClock.elapsedRealtime())
                    && TrackFilter.plausible(previous, candidate)
        if (!cadence.shouldSave(elapsed, intervalSeconds)) {
            if (!good) unsavedBreak = true
            return
        }
        if (unsavedBreak) {
            segment++
            previous = null
            anchor = null
            unsavedBreak = false
        }
        if (good && previous != null && elapsed - previous!!.elapsed > 30000) {
            segment++
            previous = null
            anchor = null
        }
        val p =
            TrackPoint(
                candidate.lat,
                candidate.lon,
                candidate.altitude,
                candidate.accuracy,
                candidate.speed,
                candidate.time,
                candidate.elapsed,
                segment,
                good,
                candidate.verticalAccuracy,
                candidate.mslAltitude,
                candidate.mslAccuracy,
                candidate.speedAccuracy
            )
        try {
            app!!.store.add(trackId, p, location.isMock())
            analytics.add(p)
            if (good) {
                val step = TrackFilter.distanceContribution(anchor, p)
                distance += step
                if (anchor == null || step > 0 || p.elapsed - anchor!!.elapsed > 30000) anchor = p
                previous = p
            } else {
                segment++
                previous = null
                anchor = null
            }
            app!!.store.update(trackId, duration(), distance, "recording")
        } catch (e: RuntimeException) {
            finish("interrupted")
        }
    }

    private fun pause() {
        if (trackId < 0 || paused) return
        accumulated = duration()
        paused = true
        app!!.engine.setRecording(false)
        app!!.store.update(trackId, duration(), distance, "paused")
        getSystemService<NotificationManager?>(NotificationManager::class.java)!!.notify(11, notification())
    }

    private fun resume() {
        if (trackId < 0 || !paused || !app!!.engine.precise()) return
        segment++
        previous = null
        anchor = null
        cadence.reset()
        unsavedBreak = false
        paused = false
        resumed = SystemClock.elapsedRealtime()
        app!!.engine.setRecording(true)
        app!!.store.update(trackId, duration(), distance, "recording")
        getSystemService<NotificationManager?>(NotificationManager::class.java)!!.notify(11, notification())
    }

    private fun notification(): Notification? {
        val open: Intent? = Intent(this, MainActivity::class.java)
        val content =
            PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        val b: Notification.Builder? =
            Notification.Builder(this, "recording")
                .setSmallIcon(com.meteorsss.startrace.R.drawable.ic_app)
                .setContentTitle(if (paused) "星迹 · 已暂停" else "星迹 · 正在记录")
                .setContentText(
                    (Geo.duration(duration())
                            + " · "
                            + Geo.meters(distance)
                            + " · "
                            + (if (app!!.engine.fresh()) "定位正常" else "等待定位，缺失段不会补线"))
                )
                .setContentIntent(content)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
        b!!.addAction(
            Notification.Action.Builder(
                null, if (paused) "继续" else "暂停", action(if (paused) "RESUME" else "PAUSE", 1)
            )
                .build()
        )
        b.addAction(Notification.Action.Builder(null, "停止", action("STOP", 2)).build())
        return b.build()
    }

    private fun action(action: String, id: Int): PendingIntent? {
        return PendingIntent.getService(
            this,
            id,
            Intent(this, TrackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun finish(status: String) {
        if (ending) return
        ending = true
        if (trackId >= 0) app!!.store.update(trackId, duration(), distance, status)
        app!!.main.removeCallbacks(ticker)
        app!!.engine.recorder = null
        app!!.engine.setRecording(false)
        active = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!ending && trackId >= 0) app!!.store.update(trackId, duration(), distance, "interrupted")
        app!!.main.removeCallbacks(ticker)
        if (active == this) {
            active = null
            app!!.engine.recorder = null
            app!!.engine.setRecording(false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        var active: TrackService? = null
    }
}

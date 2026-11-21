package com.meteorsss.startrace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Explicit opt-in live location, independent of recording; never creates/saves track rows. */
class LocationHoldService : Service() {
    private lateinit var app: App
    private val permissionCheck = object : Runnable {
        override fun run() {
            if (!app.engine.precise()) {
                Preferences.backgroundLocation(this@LocationHoldService, false)
                shutdown()
                return
            }
            app.main.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as App
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "后台持续定位", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            Preferences.backgroundLocation(this, false)
            shutdown()
            return START_NOT_STICKY
        }
        if (!Preferences.backgroundLocation(this) || !app.engine.precise()) {
            shutdown()
            return START_NOT_STICKY
        }
        try {
            val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 21, Intent(this, LocationHoldService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("星迹 · 后台持续定位")
                .setContentText("返回桌面仍获取位置；不自动保存轨迹，点击停止可关闭")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(Notification.Action.Builder(null, "停止持续定位", stop).build()).build()
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            active = this
            app.engine.setBackgroundHold(true)
            app.main.removeCallbacks(permissionCheck)
            app.main.post(permissionCheck)
        } catch (failure: RuntimeException) {
            Preferences.backgroundLocation(this, false)
            app.engine.problem = "后台持续定位未能启动，请回到应用检查定位权限后重试"
            shutdown()
        }
        // Do not silently restart after force-stop/process death or boot. A visible Activity can
        // restart an opted-in session; recording recovery remains explicit and marks real gaps.
        return START_NOT_STICKY
    }

    private fun shutdown() {
        app.main.removeCallbacks(permissionCheck)
        if (active === this) {
            active = null
            app.engine.setBackgroundHold(false)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        app.main.removeCallbacks(permissionCheck)
        if (active === this) {
            active = null
            app.engine.setBackgroundHold(false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val STOP = "STOP_LIVE_LOCATION"
        const val NOTIFICATION_ID = 12
        private const val CHANNEL = "live-location"
        var active: LocationHoldService? = null
            private set
    }
}

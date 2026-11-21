package com.meteorsss.startrace

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {
    lateinit var store: Store
    lateinit var engine: GnssEngine
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    val main: Handler = Handler(Looper.getMainLooper())

    // Track STARTED activities across the whole application, not each page's onPause.
    // The brief grace period covers recreation and in-app Activity handoff gaps.
    private val startedActivities = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Activity, Boolean>())
    private val stopVisibleLocation = Runnable {
        if (startedActivities.isEmpty()) engine.setVisible(false)
    }

    fun visibleActivityCount(): Int = startedActivities.size

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        store!!.recoverInterrupted()
        engine = GnssEngine(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities.add(activity)
                main.removeCallbacks(stopVisibleLocation)
                engine.setVisible(true)
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities.remove(activity)
                if (startedActivities.isEmpty()) {
                    main.removeCallbacks(stopVisibleLocation)
                    main.postDelayed(stopVisibleLocation, 1500)
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) { startedActivities.remove(activity) }
        })
    }
}

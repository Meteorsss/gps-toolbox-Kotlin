package com.meteorsss.startrace

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {
    var store: Store? = null
    var engine: GnssEngine? = null
    val io: ExecutorService? = Executors.newSingleThreadExecutor()
    val main: Handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        store!!.recoverInterrupted()
        engine = GnssEngine(this)
    }
}

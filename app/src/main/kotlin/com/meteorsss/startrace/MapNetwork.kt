package com.meteorsss.startrace

import android.content.Context
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File
import java.io.IOException

/** One client/cache per process; no request URLs or coordinates are logged. */
object MapNetwork {
    const val USER_AGENT = "StarTrace-Kotlin/" + BuildConfig.VERSION_NAME +
        " (+https://github.com/Meteorsss/gps-toolbox-Kotlin)"
    private var client: OkHttpClient? = null

    @Synchronized
    fun install(context: Context) {
        if (client != null) return
        val app = context.applicationContext
        client = createClient(File(app.cacheDir, "map-http-v1")) { MapPolicy.allowed(app) }
        HttpRequestUtil.setOkHttpClient(client)
        HttpRequestUtil.setLogEnabled(false)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
    }

    internal fun createClient(cacheDirectory: File, allowed: () -> Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 4 })
            // HTTP freshness/ETag/Last-Modified are honoured. MapLibre also retains
            // its native resource cache. Never add no-cache headers or force refresh.
            .cache(Cache(cacheDirectory, 32L * 1024 * 1024))
            .addInterceptor { chain ->
                if (!allowed()) throw IOException("Online map consent withdrawn")
                val request = chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
                chain.proceed(request)
            }.build()

    fun cancelPending() { client?.dispatcher?.cancelAll() }
}

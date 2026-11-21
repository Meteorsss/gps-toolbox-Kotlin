package com.meteorsss.startrace

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.WindowInsetsController

/** Shared by MainActivity, map and recording service; retains existing preference keys.  */
object Preferences {
    private fun settings(c: Context): SharedPreferences {
        return c.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
    }

    fun theme(c: Context): ThemeMode {
        val p = settings(c)
        return ThemeMode.Companion.restore(
            p.getString("theme-mode", null), p.contains("light"), p.getBoolean("light", false)
        )
    }

    fun light(c: Context): Boolean {
        val dark =
            ((c.getResources().getConfiguration().uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES)
        return theme(c)!!.isLight(dark)
    }

    fun applyWindow(activity: Activity, light: Boolean) {
        val mask =
            (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        activity.getWindow().getInsetsController()!!.setSystemBarsAppearance(if (light) mask else 0, mask)
        activity
            .getWindow()
            .getDecorView()
            .post(Runnable {
                if (!activity.isDestroyed()) activity
                    .getWindow()
                    .getInsetsController()!!
                    .setSystemBarsAppearance(if (light) mask else 0, mask)
            })
    }

    fun theme(c: Context, mode: ThemeMode) {
        settings(c).edit().putString("theme-mode", mode.name).apply()
    }

    fun interval(c: Context): Int {
        return RecordingCadence.Companion.sanitize(settings(c).getInt("record-interval-seconds", 1))
    }

    fun interval(c: Context, seconds: Int) {
        settings(c)
            .edit()
            .putInt("record-interval-seconds", RecordingCadence.Companion.sanitize(seconds))
            .apply()
    }
}

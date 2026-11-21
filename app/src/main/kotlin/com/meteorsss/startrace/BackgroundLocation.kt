package com.meteorsss.startrace

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** Foreground-only service starts; background permission is requested separately in Settings. */
object BackgroundLocation {
    private const val FINE_REQUEST = 40
    private const val NOTIFICATION_REQUEST = 41

    fun alwaysAllowed(activity: Activity): Boolean =
        activity.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun status(activity: Activity): String = when {
        LocationHoldService.active != null -> "已开启"
        Preferences.backgroundLocation(activity) -> "等待回到前台启动"
        else -> "已关闭"
    }

    fun show(activity: Activity): AlertDialog {
        val enabled = Preferences.backgroundLocation(activity) || LocationHoldService.active != null
        return AlertDialog.Builder(activity)
            .setTitle("后台持续定位")
            .setMessage("开启后，即使返回桌面、切换应用或关闭本应用页面，也继续获取卫星位置，并显示持续定位通知。它不会自动保存轨迹；只有点击开始记录才保存。持续使用 GPS 会增加耗电。\n\n"
                + "可从本页或通知栏随时关闭。轨迹记录有独立的前台服务，关闭持续定位不会停止正在记录的轨迹。\n\n"
                + "可另外设置始终允许定位；拒绝该权限仍可在应用内使用定位，并从前台启动定位服务。强制停止、系统杀进程和厂商省电策略仍可能中断。")
            .setPositiveButton(if (enabled) "停止持续定位" else "开启持续定位") { _, _ ->
                if (enabled) stop(activity) else requestStart(activity)
            }
            .setNeutralButton("设置始终允许") { _, _ -> showAlwaysPermission(activity) }
            .setNegativeButton("取消", null).show()
    }

    fun settingsIntent(activity: Activity): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))

    fun showAlwaysPermission(activity: Activity): AlertDialog? {
        val app = activity.application as App
        if (!app.engine.precise()) {
            toast(activity, "请先授予精确位置，再点击始终允许定位")
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), FINE_REQUEST)
            return null
        }
        val label = activity.packageManager.backgroundPermissionOptionLabel
        return AlertDialog.Builder(activity)
            .setTitle("始终允许定位")
            .setMessage("用于你主动开启的后台持续定位和轨迹记录。位置不上传；没有开机自动定位。Android 需要你亲自在系统设置中授权。\n\n"
                + "进入：权限 → 位置信息 → $label（始终允许），同时打开精确位置。部分系统菜单名称不同。\n\n"
                + "授权本身不会开启定位；请再开启后台持续定位或开始记录。可以选择暂不，其他前台功能照常使用。")
            .setPositiveButton("打开系统设置") { _, _ ->
                try { activity.startActivity(settingsIntent(activity)) }
                catch (_: RuntimeException) { toast(activity, "请在系统设置 → 应用 → 星迹 → 权限中修改定位权限") }
            }.setNegativeButton("暂不", null).show()
    }

    private fun requestStart(activity: Activity) {
        val app = activity.application as App
        if (!app.engine.precise()) {
            toast(activity, "先授予精确位置，然后再次开启后台持续定位")
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), FINE_REQUEST)
            return
        }
        if (!app.engine.gpsEnabled()) {
            toast(activity, "请先打开系统定位开关，然后再次开启")
            activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        } else enable(activity)
    }

    fun onPermissionResult(activity: Activity, code: Int): Boolean = when (code) {
        NOTIFICATION_REQUEST -> {
            // Permission denial does not prevent FGS; Android then exposes it in active apps.
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                toast(activity, "通知未允许，状态栏入口可能不可见；可在设置页关闭持续定位")
            enable(activity)
            true
        }
        FINE_REQUEST -> {
            (activity.application as App).engine.reconcile()
            toast(activity, "请再次选择后台持续定位或始终允许；后台权限不会自动授予")
            true
        }
        else -> false
    }

    fun enable(activity: Activity): Boolean {
        if (!(activity.application as App).engine.precise()) return false
        Preferences.backgroundLocation(activity, true)
        return startVisible(activity)
    }

    fun resumeIfEnabled(activity: Activity) {
        if (Preferences.backgroundLocation(activity) && LocationHoldService.active == null)
            startVisible(activity)
    }

    private fun startVisible(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed || !(activity.application as App).engine.precise()) return false
        return try {
            activity.startForegroundService(Intent(activity, LocationHoldService::class.java))
            true
        } catch (_: RuntimeException) {
            Preferences.backgroundLocation(activity, false)
            toast(activity, "无法启动后台定位，请在前台检查精确位置权限后重试")
            false
        }
    }

    fun stop(activity: Activity) {
        Preferences.backgroundLocation(activity, false)
        activity.stopService(Intent(activity, LocationHoldService::class.java))
        toast(activity, "已关闭持续定位；正在记录的轨迹不受影响")
    }

    private fun toast(activity: Activity, text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}

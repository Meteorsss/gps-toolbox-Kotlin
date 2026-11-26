package com.meteorsss.startrace

import android.content.Context

object MapPolicy {
    const val STYLE_URL: String = "https://tiles.openfreemap.org/styles/liberty"
    val LOCAL_STYLE: String = ("{\"version\":8,\"name\":\"Local track"
            + " only\",\"sources\":{},\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\"#182638\"}}]}")
    val DISCLOSURE = "在线底图可选 OpenStreetMap 标准或 OpenFreeMap，均无需账号/API Key。" +
        "服务方可通过地图请求看到IP及浏览区域；不会主动发送整条轨迹、收藏列表或手机朝向。" +
        "OSM 标准图遵守公共瓦片政策，仅按当前视野加载并缓存，不批量下载、不预取、不提供离线区域包。" +
        "公共服务不保证可用；失败时可重试或手动切换，不影响定位和记录。\n\n" +
        "选择“仅轨迹，不联网”可撤回同意，显示本地位置/轨迹但无道路底图；已缓存资源不会自动清除。" +
        "箭头表示手机屏幕上方朝向（真北参考），不是行进方向；朝向不可靠时隐藏。"

    private fun prefs(c: Context) = c.getSharedPreferences("map-policy", Context.MODE_PRIVATE)

    // New provider means new disclosure: never reuse v1 consent silently for a new host.
    fun allowed(c: Context): Boolean = prefs(c).getBoolean("online-consent-v2", false)

    fun allow(c: Context, allow: Boolean) {
        prefs(c).edit().putBoolean("online-consent-v2", allow)
            .putBoolean("online-consent-v1", false).apply()
    }

    fun selected(c: Context): MapBasemap = MapBasemap.fromId(prefs(c).getString("basemap-v1", null))

    fun select(c: Context, basemap: MapBasemap) {
        prefs(c).edit().putString("basemap-v1", basemap.id).apply()
    }
}

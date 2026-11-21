package com.meteorsss.startrace

import android.content.Context

object MapPolicy {
    const val STYLE_URL: String = "https://tiles.openfreemap.org/styles/liberty"
    val LOCAL_STYLE: String = ("{\"version\":8,\"name\":\"Local track"
            + " only\",\"sources\":{},\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\"#182638\"}}]}")
    val DISCLOSURE: String = ("在线底图由 OpenFreeMap 提供，数据来自 OpenStreetMap。无需账号或API"
            + " Key。地图样式、字体、图标和瓦片请求会发送到地图服务，服务方可看到IP及所浏览区域；不会主动发送整条轨迹或收藏列表。网络加载失败不影响卫星定位与轨迹记录。\n\n"
            + "不愿联网可选择“仅轨迹”，显示本地位置/轨迹但无道路底图。可随时撤回联网同意；已缓存地图资源不会自动清除。暂不提供区域批量下载、离线地图包或路线规划。")

    fun allowed(c: Context): Boolean {
        return c.getSharedPreferences("map-policy", Context.MODE_PRIVATE)
            .getBoolean("online-consent-v1", false)
    }

    fun allow(c: Context, allow: Boolean) {
        c.getSharedPreferences("map-policy", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("online-consent-v1", allow)
            .apply()
    }
}

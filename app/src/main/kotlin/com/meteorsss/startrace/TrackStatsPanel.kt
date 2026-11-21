package com.meteorsss.startrace

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import java.util.*

/**
 * Live data is O(1) from the recording service; completed history is analyzed once off the UI
 * thread.
 */
class TrackStatsPanel(context: Context?, u: Ui, private val trackId: Long) : LinearLayout(context) {
    private val app: App
    private val values: TextView
    private val quality: TextView
    private var loading = false
    private var historyLoaded = false
    private var history: TrackAnalytics.Result? = null

    init {
        app = context!!.getApplicationContext() as App
        setOrientation(VERTICAL)
        addView(u.title("路程统计", 18))
        u.gap(this, 10)
        values = u.text("正在读取统计…", 15, u.text)
        values.setLineSpacing(u.dp(5f).toFloat(), 1f)
        addView(values)
        u.gap(this, 10)
        quality = u.text("", 12, u.muted)
        addView(quality)
        refresh()
    }

    fun refresh() {
        val service: TrackService? = TrackService.Companion.active
        if (service != null && service.trackId == trackId) {
            historyLoaded = false
            display(service.insights())
            return
        }
        if (historyLoaded) {
            if (history != null) display(history!!)
            return
        }
        if (loading) return
        loading = true
        app.io.execute(Runnable {
            var result: TrackAnalytics.Result? = null
            try {
                val track = app.store.track(trackId)
                if (track != null) result = app.store.analyze(trackId, track.duration, track.distance)
            } finally {
                val loaded = result
                app.main.post(Runnable {
                    loading = false
                    historyLoaded = true
                    history = loaded
                    if (loaded != null) display(loaded)
                    else values.setText("轨迹不存在或无法读取")
                })
            }
        })
    }

    fun valuesForTests(): String {
        return values.getText().toString()
    }

    private fun number(n: Double, suffix: String): String {
        return if (java.lang.Double.isFinite(n)) String.format(Locale.US, "%.1f%s", n, suffix) else "—"
    }

    private fun display(s: TrackAnalytics.Result) {
        values.setText(
            ("总里程  "
                    + Geo.meters(s.distance)
                    + "    记录时长  "
                    + Geo.duration(s.activeMs)
                    + "\n平均速度  "
                    + number(s.averageKmh(), " km/h")
                    + "\n移动时间  "
                    + Geo.duration(s.movingMs)
                    + "    静止时间  "
                    + Geo.duration(s.stationaryMs)
                    + "\n移动均速  "
                    + number(s.movingKmh(), " km/h")
                    + "    最高速度  "
                    + number(s.maxSpeed * 3.6, " km/h")
                    + "\n累计上升  "
                    + number(s.ascent, " m")
                    + "    累计下降  "
                    + number(s.descent, " m")
                    + "\n最低 / 最高"
                    + (if (s.msl) "海拔" else "椭球高")
                    + "  "
                    + number(s.minHeight, " m")
                    + " / "
                    + number(s.maxHeight, " m"))
        )
        quality.setText(
            ("有效点 "
                    + s.accepted
                    + " / "
                    + s.raw
                    + "（"
                    + number(s.validPercent(), "%")
                    + "） · "
                    + s.sections
                    + " 个连续片段\n移动状态未知 "
                    + Geo.duration(s.unknownMs)
                    + " · 高度质量合格点 "
                    + s.heightPoints
                    + "\n\n"
                    + "平均速度=里程÷记录时长，手动暂停不计入。移动时间仅统计连续、速度≥0.8m/s的区间；移动均速由该区间GNSS速度积分估算，和过滤后的里程口径不同。最高速度使用3点中值，均为估计值。\n\n"
                    + "爬升/下降采用高度中值、平滑与精度阈值去噪，不跨缺失段。优先使用系统提供的平均海平面高度；否则明确使用WGS84椭球高。高度精度缺失/不足（包括旧记录）时显示—，不是0。小起伏可能被滤掉，不能用于专业测绘。")
        )
    }
}

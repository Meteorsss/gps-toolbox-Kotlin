package com.meteorsss.startrace

import android.content.Intent
import android.graphics.Typeface
import android.location.GnssStatus
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.View.OnLongClickListener
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import com.meteorsss.startrace.GnssEngine.Satellite
import com.meteorsss.startrace.Store.Place
import java.lang.Float
import java.text.SimpleDateFormat
import java.util.*
import java.util.function.Supplier
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.String
import kotlin.arrayOf
import kotlin.collections.plus
import kotlin.intArrayOf
import kotlin.math.max
import kotlin.plus
import kotlin.sequences.plus
import kotlin.text.format
import kotlin.text.isEmpty
import kotlin.text.plus

object Screens {
    fun render(a: MainActivity) {
        when (a.mode) {
            "settings" -> {
                settings(a)
                return
            }

            "places" -> {
                places(a)
                return
            }

            "track" -> {
                track(a)
                return
            }

            "compass" -> {
                compass(a, false)
                return
            }

            "guide" -> {
                compass(a, true)
                return
            }

            "speed" -> {
                speed(a)
                return
            }

            "diagnostics" -> {
                diagnostics(a)
                return
            }

            else -> {}
        }
        when (a.page) {
            1 -> satellites(a)
            2 -> records(a)
            3 -> tools(a)
            else -> home(a)
        }
    }

    private fun f(format: String, vararg args: Any?): String {
        return String.format(Locale.US, format, *args)
    }

    private fun metric(
        a: MainActivity, name: String, value: Supplier<String>, caption: String
    ): LinearLayout {
        val u = a.u
        val p = u.panel()
        p.addView(u.text(name, 12, u.muted))
        u.gap(p, 11)
        val t = u.title("—", 24)
        a.bind(t, value)
        p.addView(t)
        u.gap(p, 5)
        p.addView(u.text(caption, 10, u.muted))
        return p
    }

    private fun chart(
        a: MainActivity, panel: LinearLayout, kind: String, track: Long, place: Place?, height: Int
    ) {
        if (kind == "compass") {
            val compass = CompassView(a, place)
            panel.addView(compass, LinearLayout.LayoutParams(-1, a.u.dp(height.toFloat())))
            a.registerCompass(compass)
            return
        }
        val chart = Charts(a, kind, track, place)
        panel.addView(chart, LinearLayout.LayoutParams(-1, a.u.dp(height.toFloat())))
        a.watch(Runnable { chart.refreshData() })
    }

    private fun button(a: MainActivity, p: LinearLayout, title: String, action: Runnable) {
        p.addView(a.u.button(title, false, action))
        a.u.gap(p, 9)
    }

    private fun home(a: MainActivity) {
        val u = a.u
        val e = a.e
        val b = a.body
        u.section(b, "YOUR POSITION, CLEARLY", "此刻，在哪里")
        if (!e.precise()) {
            b.addView(u.button("启用精确定位", true, Runnable { a.askLocation() }))
            u.gap(b, 14)
        }
        button(a, b, "打开地图 · 当前位置", Runnable { a.openMap(-1) })
        val hero = u.panel()
        val speedLabel = u.text("当前速度 · LIVE SPEED", 12, u.accent)
        speedLabel.setTag("dashboard-speed-label")
        speedLabel.setGravity(Gravity.CENTER)
        hero.addView(speedLabel, LinearLayout.LayoutParams(-1, -2))
        u.gap(hero, 12)
        val row = u.row()
        row.setTag("dashboard-speed-row")
        row.setGravity(Gravity.CENTER)
        val speed = u.title("—", 64)
        speed.setTag("dashboard-speed-value")
        speed.setGravity(Gravity.CENTER)
        a.bind(
            speed,
            Supplier { if (e.fresh() && e.location!!.hasSpeed()) f("%.1f", e.location!!.getSpeed() * 3.6) else "—" })
        row.addView(speed)
        val unit = u.text("  km/h", 17, u.muted)
        row.addView(unit)
        hero.addView(row, LinearLayout.LayoutParams(-1, -2))
        u.gap(hero, 9)
        u.note(hero, "卫星速度 · 无有效速度时显示 —，不将未知速度当作 0。 ").setGravity(Gravity.CENTER)
        b.addView(hero)
        u.pair(
            b,
            metric(
                a,
                "水平精度估计",
                Supplier { if (e.fresh() && e.location!!.hasAccuracy()) f("%.1f", e.location!!.getAccuracy()) else "—" },
                "m · 估计半径，非保证误差"
            ),
            metric(
                a,
                "GNSS 高度",
                Supplier { if (e.fresh() && e.location!!.hasAltitude()) f("%.1f", e.location!!.getAltitude()) else "—" },
                "m · WGS84 椭球高"
            )
        )
        u.gap(b, 12)
        u.pair(
            b,
            metric(
                a,
                "独立卫星",
                Supplier {
                    if (e.satelliteDataFresh())
                        e.uniqueCount(true).toString() + " / " + e.uniqueCount(false)
                    else
                        "— / —"
                },
                "参与定位 / 上报 · 颗"
            ),
            metric(
                a,
                "本次首次定位",
                Supplier { if (e.firstFix >= 0) f("%.1f s", e.firstFix / 1000.0) else "—" },
                "从本次定位请求开始"
            )
        )
        u.gap(b, 12)
        val coords = u.panel()
        val title = u.row()
        title.addView(u.title("当前位置", 16), LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(u.text(a.coordinateLabel(), 11, u.accent))
        coords.addView(title)
        u.gap(coords, 12)
        u.pair(
            coords,
            u.button("WGS84", !a.gcj(), Runnable { a.setCoordinateSystem(false) }),
            u.button("GCJ-02", a.gcj(), Runnable { a.setCoordinateSystem(true) })
        )
        u.gap(coords, 14)
        val coord = u.text("等待定位…", 19, u.text)
        coord.setTypeface(Typeface.MONOSPACE)
        a.bind(
            coord,
            Supplier {
                if (!e.fresh()) return@Supplier "等待有效位置…"
                val l = e.location
                var lat = l!!.getLatitude()
                var lon = l.getLongitude()
                if (a.gcj()) {
                    val converted = Gcj02.fromWgs84(lat, lon)
                    if (!converted.converted) return@Supplier "超出近似转换范围\n请切换到 WGS84"
                    lat = converted.latitude
                    lon = converted.longitude
                }
                if (a.dms())
                    Geo.dms(lat, true) + "\n" + Geo.dms(lon, false)
                else
                    f("纬度  %.7f°\n经度  %.7f°", lat, lon)
            })
        coords.addView(coord)
        u.gap(coords, 10)
        u.note(
            coords,
            if (a.gcj())
                "GCJ-02 为离线近似转换，仅适用于大陆相关地图使用场景。覆盖范围采用粗略矩形，不等于国界；边界及港澳台等地区请核对地图来源。"
            else
                "WGS84 是原始卫星坐标；切换显示不会修改轨迹和收藏地点。"
        )
        u.gap(coords, 12)
        val bearing = u.text("", 12, u.muted)
        a.bind(
            bearing,
            Supplier {
                if (e.fresh() && e.location!!.hasBearing())
                    f("运动方向 %.0f°（真北）", e.location!!.getBearing())
                else
                    "运动方向暂无 · 不等于手机朝向"
            })
        coords.addView(bearing)
        u.gap(coords, 15)
        u.pair(
            coords,
            u.button("保存地点", false, Runnable { a.saveHere() }),
            u.button("复制 " + a.coordinateLabel(), false, Runnable { a.copyHere() })
        )
        coord.setOnLongClickListener(OnLongClickListener { v: View? ->
            a.copyHere()
            true
        })
        b.addView(coords)
        b.addView(
            u.button(
                if (TrackService.Companion.active == null) "开始记录轨迹" else "查看正在记录的轨迹",
                true,
                Runnable { a.record() })
        )
        u.gap(b, 12)
        button(a, b, "定位权限与开关", Runnable { a.askLocation() })
        u.note(b, "无账号 · 轨迹本地保存 · 在线地图需另行同意\n建议在开阔处等待首次定位；室内可能无法获取卫星位置。")
    }

    private fun satellites(a: MainActivity) {
        val u = a.u
        val b = a.body
        u.section(b, "ONE SKY, TWO COUNTS", "头顶的卫星")
        val totals = u.panel()
        totals.addView(u.title("参与定位 / 系统上报", 17))
        u.gap(totals, 14)
        totals.addView(u.text("频率信号 · 条（同星多频分别计数）", 12, u.accent))
        u.gap(totals, 7)
        val entries = u.title("— / —", 34)
        a.bind(
            entries,
            Supplier {
                val c = a.e.satelliteStats().total
                if (a.e.satelliteDataFresh()) c.usedEntries().toString() + " / " + c.entries() else "— / —"
            })
        totals.addView(entries)
        u.gap(totals, 16)
        totals.addView(u.text("独立卫星 · 颗（星座 + SVID 去重）", 12, u.accent))
        u.gap(totals, 7)
        val unique = u.title("— / —", 30)
        a.bind(
            unique,
            Supplier {
                val c = a.e.satelliteStats().total
                if (a.e.satelliteDataFresh()) c.used().toString() + " / " + c.received() else "— / —"
            })
        totals.addView(unique)
        u.gap(totals, 12)
        val positive = u.text("", 12, u.muted)
        a.bind(
            positive,
            Supplier {
                val c = a.e.satelliteStats().total
                if (a.e.satelliteDataFresh())
                    "C/N₀ > 0：" + c.trackedEntries() + " 条信号 / " + c.tracked() + " 颗独立卫星"
                else
                    "等待新鲜卫星状态；— 不代表天空中没有卫星"
            })
        totals.addView(positive)
        u.gap(totals, 7)
        val age = u.text("", 12, u.muted)
        a.bind(
            age,
            Supplier {
                if (a.e.satelliteDataFresh())
                    ("当前快照 · "
                            + max(
                        0,
                        (SystemClock.elapsedRealtime() - a.e.satelliteAt) / 1000
                    ) + " 秒前更新 · 不累加历史")
                else
                    "暂无可用快照"
            })
        totals.addView(age)
        u.gap(totals, 12)
        u.note(
            totals,
            "同一颗卫星的两个频率会产生两条记录，但仍是一颗卫星。与按频率计数的软件对比时，请看上面的“频率信号”，不要与“独立卫星”混比。所有斜线左侧均为参与定位，右侧为系统上报。"
        )
        b.addView(totals)
        val table = u.panel()
        table.addView(u.title("按卫星系统统计", 18))
        u.gap(table, 8)
        u.note(table, "两列均为：参与定位 / 系统上报")
        u.gap(table, 12)
        val header = u.row()
        header.addView(u.text("系统", 11, u.muted), LinearLayout.LayoutParams(0, -2, 1.1f))
        for (label in arrayOf<String>("独立卫星·颗", "频率信号·条")) {
            val t = u.text(label, 11, u.muted)
            t.setGravity(Gravity.END)
            header.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        table.addView(header)
        u.gap(table, 6)
        val systems: IntArray? = intArrayOf(
            GnssStatus.CONSTELLATION_BEIDOU,
            GnssStatus.CONSTELLATION_GPS,
            GnssStatus.CONSTELLATION_GALILEO,
            GnssStatus.CONSTELLATION_GLONASS,
            GnssStatus.CONSTELLATION_QZSS,
            GnssStatus.CONSTELLATION_IRNSS,
            GnssStatus.CONSTELLATION_SBAS,
            GnssStatus.CONSTELLATION_UNKNOWN
        )
        for (system in systems!!) {
            val row = u.row()
            row.setPadding(0, u.dp(11f), 0, u.dp(11f))
            row.addView(
                u.text(GnssEngine.Companion.systemName(system), 13, u.constellation(system)),
                LinearLayout.LayoutParams(0, -2, 1.1f)
            )
            val satellites = u.title("— / —", 16)
            val signals = u.title("— / —", 16)
            satellites.setGravity(Gravity.END)
            signals.setGravity(Gravity.END)
            satellites.setContentDescription(GnssEngine.Companion.systemName(system) + " 独立卫星 参与定位 / 系统上报")
            signals.setContentDescription(GnssEngine.Companion.systemName(system) + " 频率信号 参与定位 / 系统上报")
            a.bind(
                satellites,
                Supplier {
                    val c = a.e.satelliteStats().system(system)
                    if (a.e.satelliteDataFresh()) c!!.used().toString() + " / " + c.received() else "— / —"
                })
            a.bind(
                signals,
                Supplier {
                    val c = a.e.satelliteStats().system(system)
                    if (a.e.satelliteDataFresh()) c!!.usedEntries().toString() + " / " + c.entries() else "— / —"
                })
            row.addView(satellites, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(signals, LinearLayout.LayoutParams(0, -2, 1f))
            table.addView(row)
        }
        b.addView(table)
        val sky = u.panel()
        sky.addView(u.title("天空图 · 每颗卫星只画一次", 16))
        chart(a, sky, "sky", -1, null, 300)
        u.note(sky, "实心 = 至少一条频率参与定位；空心 = 当前未参与\n青绿 GPS · 蓝色北斗 · 粉色 Galileo · 橙色 GLONASS")
        b.addView(sky)
        val signal = u.panel()
        signal.addView(u.title("全部频率信号明细", 18))
        u.gap(signal, 8)
        u.pair(
            signal,
            u.button("同星多频分组", a.groupSignals(), Runnable { a.setGroupSignals(true) }),
            u.button("按信号强度", !a.groupSignals(), Runnable { a.setGroupSignals(false) })
        )
        u.gap(signal, 10)
        u.note(
            signal,
            "相同星座和 SVID 的不同 MHz 是同一颗卫星的不同频率。✓ 为该条记录参与定位，— 为未参与；C/N₀ 单位 dB-Hz。保留系统上报的全部条目，包括 0 信号值。"
        )
        u.gap(signal, 15)
        chart(a, signal, "signal", -1, null, 64)
        b.addView(signal)
        val explanation = u.panel()
        explanation.addView(u.title("这两个数为什么不同？", 17))
        u.gap(explanation, 8)
        u.note(
            explanation,
            "“系统上报”不是保证正在锁定；C/N₀=0 的条目也可能存在。参与定位采用系统 usedInFix 标记，同一颗卫星只要任一频率参与，就计为一颗参与卫星。\n\n"
                    + "不同时间、遮挡、手机姿态或定位请求状态都会使快照变化。对照时应保持同一位置、同一姿态，并尽量在相近时刻比较同一种口径；数量大不等于定位更准。"
        )
        b.addView(explanation)
        button(a, b, "查看设备与定位诊断", Runnable { a.navigate("diagnostics") })
    }

    private fun records(a: MainActivity) {
        val u = a.u
        val b = a.body
        u.section(b, "LEAVE A TRACE", "把走过的路留下")
        button(
            a,
            b,
            "轨迹点间隔：" + Preferences.interval(a) + " 秒（点击选择）",
            Runnable { a.selectRecordingInterval() })
        val t: TrackService? = TrackService.Companion.active
        if (t != null) {
            val live = u.panel()
            live.addView(u.text(if (t.paused) "Ⅱ  已暂停" else "●  正在记录", 12, u.accent))
            u.gap(live, 12)
            val elapsed = u.title("00:00:00", 38)
            a.bind(elapsed, Supplier { Geo.duration(t.duration()) })
            live.addView(elapsed)
            u.gap(live, 8)
            val d = u.title("0 m", 23)
            a.bind(d, Supplier { Geo.meters(t.distance) })
            live.addView(d)
            u.gap(live, 8)
            u.note(
                live,
                "保存间隔 " + t.intervalSeconds() + " 秒。缺失、暂停、低精度或异常跳点会断开显示；模拟位置不计入有效轨迹。"
            )
            button(a, live, "在地图上看实时轨迹", Runnable { a.openMap(t.trackId) })
            val liveStats = TrackStatsPanel(a, u, t.trackId)
            live.addView(liveStats)
            a.watch(Runnable { liveStats.refresh() })
            chart(a, live, "track", t.trackId, null, 230)
            u.gap(live, 8)
            u.pair(
                live,
                u.button(
                    if (t.paused) "继续" else "暂停",
                    false,
                    Runnable { a.service(if (t.paused) "RESUME" else "PAUSE") }),
                u.button("结束并保存", true, Runnable { a.service("STOP") })
            )
            b.addView(live)
        } else {
            b.addView(u.button("开始新轨迹", true, Runnable { a.record() }))
            u.gap(b, 14)
        }
        button(a, b, "收藏地点 →", Runnable { a.navigate("places") })
        u.gap(b, 8)
        b.addView(u.title("历史轨迹", 19))
        u.gap(b, 12)
        val list = a.app.store.tracks()
        if (list.isEmpty()) {
            val empty = u.panel()
            empty.addView(u.title("第一段旅程，等你出发", 18))
            u.gap(empty, 8)
            u.note(empty, "记录默认关闭。只有点击开始后才会持续记录，结束后保存在这台手机上。")
            b.addView(empty)
        }
        for (track in list) {
            if (t != null && track.id == t.trackId) continue
            val p = u.panel()
            p.addView(u.title(track.name, 17))
            u.gap(p, 8)
            p.addView(
                u.text(
                    Geo.meters(track.distance) + "  ·  " + Geo.duration(track.duration), 15, u.accent
                )
            )
            u.gap(p, 6)
            p.addView(
                u.text(
                    (date(track.start)
                            + "  ·  "
                            + status(track.status)
                            + "\n"
                            + track.points
                            + " 有效点 / "
                            + track.raw
                            + " 原始点"),
                    12,
                    u.muted
                )
            )
            p.setOnClickListener(View.OnClickListener { v: View? -> a.trackActions(track) })
            p.setFocusable(true)
            p.setContentDescription(track.name + "，查看或导出轨迹")
            b.addView(p)
        }
        u.note(b, "距离经过抗漂移过滤，是估计值。卸载会删除本地记录，请先导出备份。")
    }

    private fun status(s: String?): String {
        when (s) {
            "recording" -> return "记录中"
            "paused" -> return "已暂停"
            "finished" -> return "已完成"
            else -> return "意外中断 · 已保留数据"
        }
    }

    private fun date(time: Long): String? {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))
    }

    private fun track(a: MainActivity) {
        val u = a.u
        val t = a.app.store.track(a.selectedTrack)
        if (t == null) {
            u.note(a.body, "这段轨迹不存在。")
            return
        }
        u.section(a.body, "TRACK DETAILS", t.name)
        val p = u.panel()
        p.addView(u.title(Geo.meters(t.distance), 34))
        u.gap(p, 9)
        p.addView(u.text(Geo.duration(t.duration) + "  ·  " + status(t.status), 15, u.muted))
        u.gap(p, 8)
        p.addView(
            u.text(
                ("平均速度 "
                        + (if (t.duration > 0) f("%.1f km/h", t.distance / (t.duration / 1000.0) * 3.6) else "—")
                        + "\n"
                        + t.points
                        + " 有效点 / "
                        + t.raw
                        + " 原始点"),
                13,
                u.muted
            )
        )
        button(a, p, "在地图上查看整段轨迹", Runnable { a.openMap(t.id) })
        val stats = TrackStatsPanel(a, u, t.id)
        p.addView(stats)
        a.watch(Runnable { stats.refresh() })
        chart(a, p, "track", t.id, null, 320)
        u.note(p, "橙点为起点，青绿点为终点。轨迹是相对示意图，不含道路底图；长距离和极地区域可能有投影变形。")
        a.body.addView(p)
        val live = TrackService.Companion.active != null && TrackService.Companion.active!!.trackId == t.id
        if (!live) {
            button(a, a.body, "导出 GPX（有效轨迹）", Runnable { a.export(t, false) })
            button(a, a.body, "导出 KML（Google Earth / 我的地图）", Runnable { a.export(t, TrackExport.KML) })
            button(a, a.body, "导出 CSV（全部原始点）", Runnable { a.export(t, true) })
        } else u.note(a.body, "请结束记录后再导出，以获得一致的完整文件。")
        u.note(
            a.body,
            "GPX / KML 保留断点，不插值补线。KML 使用 WGS84 贴地轨迹，不导出椭球高度；逐点时间与测量值用 GPX / CSV。Google Earth 可导入"
                    + " KML；Google 地图需先在网页版“我的地图”导入（KML 最大 5 MB）。CSV 保留被过滤的位置与模拟标记。"
        )
    }

    private fun places(a: MainActivity) {
        val u = a.u
        u.section(a.body, "PLACES TO REMEMBER", "收藏地点")
        u.pair(
            a.body,
            u.button("保存当前位置", true, Runnable { a.saveHere() }),
            u.button("输入坐标", false, Runnable { a.placeDialog(null, null) })
        )
        u.gap(a.body, 18)
        val places = a.app.store.places()
        if (places.isEmpty()) u.note(a.body, "还没有收藏地点。可以保存停车位置、出发点或常去地点。所有坐标均为 WGS84。")
        for (p in places) {
            val panel = u.panel()
            panel.addView(u.title(p.name, 19))
            u.gap(panel, 8)
            panel.addView(u.text(f("%.6f, %.6f", p.lat, p.lon), 13, u.muted))
            if (!p.note!!.isEmpty()) {
                u.gap(panel, 7)
                panel.addView(u.text(p.note, 13, u.muted))
            }
            u.gap(panel, 8)
            val distance = u.text("", 14, u.accent)
            a.bind(
                distance,
                Supplier {
                    if (a.e.fresh())
                        ("直线距离 "
                                + Geo.meters(
                            Geo.distance(
                                a.e.location!!.getLatitude(),
                                a.e.location!!.getLongitude(),
                                p.lat,
                                p.lon
                            )
                        ))
                    else
                        "点击查看操作"
                })
            panel.addView(distance)
            u.gap(panel, 12)
            u.pair(
                panel,
                u.button(
                    "指向这里",
                    false,
                    Runnable {
                        a.destination = p
                        a.navigate("guide")
                    }),
                u.button("编辑名称 / 备注", false, Runnable { a.editPlaceDialog(p) })
            )
            panel.setOnClickListener(View.OnClickListener { v: View? -> a.placeActions(p) })
            a.body.addView(panel)
        }
    }

    private fun tools(a: MainActivity) {
        val u = a.u
        u.section(a.body, "SMALL TOOLS, REAL USE", "随手可用")
        tool(a, "地图与轨迹", "OpenFreeMap · 免账号免 Key", Runnable { a.openMap(-1) })
        tool(a, "方向指南针", "磁北方向 · 传感器状态", Runnable { a.navigate("compass") })
        tool(a, "大字速度表", "专注速度 · 可选保持亮屏", Runnable { a.navigate("speed") })
        tool(a, "坐标格式转换", "十进制度 ↔ 度分秒", Runnable { a.coordinateTool() })
        tool(a, "两点测距", "WGS84 · 直线距离与方位", Runnable { a.distanceTool() })
        tool(a, "收藏地点", "保存位置 · 指向目标", Runnable { a.navigate("places") })
        tool(a, "定位诊断", "权限、设备与数据状态", Runnable { a.navigate("diagnostics") })
    }

    private fun tool(a: MainActivity, title: String, description: String, action: Runnable) {
        val p = a.u.panel()
        p.addView(a.u.title(title + "  ›", 19))
        a.u.gap(p, 8)
        p.addView(a.u.text(description, 13, a.u.muted))
        p.setOnClickListener(View.OnClickListener { v: View? -> action.run() })
        p.setMinimumHeight(a.u.dp(92f))
        p.setFocusable(true)
        p.setContentDescription(title)
        a.body.addView(p)
    }

    private fun compass(a: MainActivity, guide: Boolean) {
        val u = a.u
        val target = if (guide) a.destination else null
        if (guide && target == null) {
            u.note(a.body, "请先选择目标地点。")
            return
        }
        u.section(
            a.body,
            if (guide) "FIND YOUR WAY BACK" else "FOLLOW YOUR BEARING",
            if (guide) target!!.name else "方向指南针"
        )
        val p = u.panel()
        chart(a, p, "compass", -1, target, 330)
        val state = u.text("", 13, u.muted)
        a.bind(
            state,
            Supplier { if (!a.e.hasCompass) "设备没有提供旋转矢量传感器" else if (guide) "方位基准：真北（由磁偏角修正）" else "方位基准：磁北 · 请将手机平放" })
        p.addView(state)
        u.gap(p, 8)
        val quality = u.text("", 13, u.warning)
        a.bind(
            quality,
            Supplier { if (a.e.sensorAccuracy <= 1) "方向可靠性较低：请远离磁铁、金属或磁吸壳，并缓慢转动手机。" else "方向仅供参考，不作为专业测量依据。" })
        p.addView(quality)
        a.body.addView(p)
        if (guide) {
            val info = u.panel()
            val distance = u.title("等待定位", 32)
            a.bind(
                distance,
                Supplier {
                    if (a.e.fresh())
                        Geo.meters(
                            Geo.distance(
                                a.e.location!!.getLatitude(),
                                a.e.location!!.getLongitude(),
                                target!!.lat,
                                target.lon
                            )
                        )
                    else
                        "等待新鲜位置"
                })
            info.addView(distance)
            u.gap(info, 10)
            val bearing = u.text("", 15, u.accent)
            a.bind(
                bearing,
                Supplier {
                    if (a.e.fresh())
                        f(
                            "目标真北方位 %.0f°",
                            Geo.bearing(
                                a.e.location!!.getLatitude(),
                                a.e.location!!.getLongitude(),
                                target!!.lat,
                                target.lon
                            )
                        )
                    else
                        "需要定位后才能计算目标方向"
                })
            info.addView(bearing)
            u.gap(info, 10)
            u.note(info, "这是直线方向指引，不是道路导航。不能判断障碍、地形或可通行路线；接近目标时请结合定位精度判断。")
            a.body.addView(info)
        }
    }

    private fun speed(a: MainActivity) {
        val u = a.u
        u.section(a.body, "LIVE SPEED", "大字速度表")
        if (!a.e.precise() || !a.e.gpsEnabled()) button(
            a,
            a.body,
            "启用精确定位 · 进入即自动测速",
            Runnable { a.askLocation() })
        val p = u.panel()
        u.gap(p, 24)
        val speed = u.title("—", 76)
        speed.setGravity(Gravity.CENTER)
        speed.setTag("large-speed-current")
        a.bind(
            speed,
            Supplier {
                if (a.e.fresh()
                    && a.e.location!!.hasSpeed()
                    && Float.isFinite(a.e.location!!.getSpeed())
                    && a.e.location!!.getSpeed() >= 0
                )
                    f("%.1f", a.e.location!!.getSpeed() * 3.6)
                else
                    "—"
            })
        p.addView(speed, LinearLayout.LayoutParams(-1, -2))
        val unit = u.text("km/h", 22, u.accent)
        unit.setGravity(Gravity.CENTER)
        p.addView(unit, LinearLayout.LayoutParams(-1, -2))
        u.gap(p, 8)
        val metres = u.text("— m/s", 24, u.accent)
        metres.setTag("large-speed-current-mps")
        metres.setGravity(Gravity.CENTER)
        a.bind(
            metres,
            Supplier {
                if (a.e.fresh()
                    && a.e.location!!.hasSpeed()
                    && Float.isFinite(a.e.location!!.getSpeed())
                    && a.e.location!!.getSpeed() >= 0
                )
                    f("%.2f m/s", a.e.location!!.getSpeed())
                else
                    "— m/s"
            })
        p.addView(metres, LinearLayout.LayoutParams(-1, -2))
        u.gap(p, 24)
        val statistics = u.text("", 16, u.accent)
        statistics.setTag("large-speed-session-stats")
        statistics.setGravity(Gravity.CENTER)
        a.bind(
            statistics,
            Supplier {
                val s = a.speedStats()
                if (s == null) return@Supplier "本次测速 · 等待进入"
                val max = if (java.lang.Double.isFinite(s.maxMps)) f("%.1f km/h", s.maxMps * 3.6) else "—"
                val avg = if (java.lang.Double.isFinite(s.averageMps)) f("%.1f km/h", s.averageMps * 3.6) else "—"
                ((if (s.validPoints == 0) "本次测速 · 等待有效定位" else "本次测速 · 自动统计中")
                        + "\n最高速度  "
                        + max
                        + "\n平均速度  "
                        + avg
                        + "\n累计里程  "
                        + Geo.meters(s.distance))
            })
        p.addView(statistics, LinearLayout.LayoutParams(-1, -2))
        u.gap(p, 24)
        val keep = Switch(a)
        keep.setText("保持屏幕常亮")
        keep.setTextColor(u.text)
        keep.setMinHeight(u.dp(48f))
        keep.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { v: CompoundButton?, on: Boolean ->
            a.keepAwake(
                on
            )
        })
        p.addView(keep)
        a.body.addView(p)
        u.note(
            a.body,
            "进入本页即自动开始独立测速，不需要开启轨迹记录；离开本页即清空，再进入从零开始，不保存历史或轨迹文件。临时退到后台暂停统计，返回同一页继续，不连接后台移动。\n\n"
                    + "平均速度=本页抗漂移里程÷前台测速时长（含静止），最高速度为本次有效GNSS速度样本的最大值。定位未到或样本不足显示—；模拟、过期或低精度位置不计统计。轨迹记录的开始、暂停、间隔与统计均不影响本页。所有数值为估计，请勿在驾驶时操作手机。"
        )
    }

    private fun diagnostics(a: MainActivity) {
        val u = a.u
        u.section(a.body, "KNOW WHAT IS HAPPENING", "定位诊断")
        val p = u.panel()
        val details = u.text("", 14, u.text)
        a.bind(
            details,
            Supplier {
                ("设备  "
                        + Build.MANUFACTURER
                        + " "
                        + Build.MODEL
                        + "\nAndroid  "
                        + Build.VERSION.RELEASE
                        + " / API "
                        + Build.VERSION.SDK_INT
                        + "\n精确位置权限  "
                        + (if (a.e.precise()) "已授予" else "未授予")
                        + "\n系统定位  "
                        + (if (a.e.enabled()) "开启" else "关闭")
                        + "\nGPS 提供器  "
                        + (if (a.e.gpsEnabled()) "开启" else "关闭或不可用")
                        + "\n旋转矢量传感器  "
                        + (if (a.e.hasCompass) "可用" else "未提供")
                        + "\n数据年龄  "
                        + (if (a.e.location == null) "无位置" else (a.e.age() / 1000).toString() + " 秒")
                        + "\n当前提供器  "
                        + (if (a.e.location == null) "—" else a.e.location!!.getProvider())
                        + "\n模拟位置  "
                        + (if (a.e.location == null) "—" else if (a.e.location!!.isMock()) "是" else "否")
                        + "\n卫星信号记录  "
                        + a.e.currentSatellites().size
                        + " 条\n频率字段  "
                        + (if (a.e.currentSatellites().stream().anyMatch { s: Satellite? -> s!!.hasFrequency })
                    "设备有提供"
                else
                    "当前未提供 / 无信号")
                        + "\n"
                        + a.e.problem)
            })
        details.setLineSpacing(u.dp(7f).toFloat(), 1f)
        p.addView(details)
        a.body.addView(p)
        button(a, a.body, "请求精确定位", Runnable { a.askLocation() })
        button(a, a.body, "应用权限设置", Runnable { a.appSettings() })
        button(
            a,
            a.body,
            "系统定位设置",
            Runnable { a.openSystem(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
        u.note(
            a.body,
            "首次定位耗时不是严格冷启动测试。频率有数据不等于已经验证双频性能。诊断只检查可观察状态，不提供虚假的一键增强。"
        )
    }

    private fun settings(a: MainActivity) {
        val u = a.u
        u.section(a.body, "MAKE IT YOURS", "设置与关于")
        button(a, a.body, "色彩模式：" + Preferences.theme(a).label, Runnable { a.selectTheme() })
        button(a, a.body, "轨迹点间隔：" + Preferences.interval(a) + " 秒", Runnable { a.selectRecordingInterval() })
        u.note(
            a.body,
            "间隔控制保存新定位点，不是保证定位频率；仪表仍约每秒请求定位。大间隔省存储但可能漏弯道或速度峰值；调整间隔会开始新片段，不影响已保存点。CSV保留按所选间隔保存的原始点。"
        )
        button(
            a,
            a.body,
            if (a.dms()) "坐标显示：度分秒（点击切换）" else "坐标显示：十进制度（点击切换）",
            Runnable { a.toggleDms() })
        button(a, a.body, "定位与通知权限", Runnable { a.appSettings() })
        button(a, a.body, "在线地图隐私 / 撤回同意", Runnable { a.mapPrivacy() })
        button(a, a.body, "第三方许可 · 离线坐标转换", Runnable { a.thirdPartyNotices() })
        button(
            a,
            a.body,
            "系统电池优化设置",
            Runnable { a.openSystem(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        val p = u.panel()
        p.addView(u.title("红米 / 澎湃 OS 使用提示", 17))
        u.gap(p, 10)
        u.note(
            p,
            "如需长时间熄屏记录，可在系统应用设置中检查后台电池策略。菜单因系统版本不同而异，必要时允许应用在后台运行。\n\n"
                    + "星迹不会自动更改系统设置，也不承诺永不被系统终止。记录意外中断后，下次打开会保留已落盘的数据并标为中断。"
        )
        a.body.addView(p)
        val privacy = u.panel()
        privacy.addView(u.title("你的轨迹，只在你手里", 18))
        u.gap(privacy, 10)
        u.note(
            privacy,
            ("无账号、广告或分析 SDK。可选在线地图会联网获取底图，须先同意；轨迹仍只保存本机。未申请后台始终定位。只有手动开始轨迹后，才使用前台服务继续记录。\n\n"
                    + "导出文件包含精确位置，请谨慎分享。卸载应用会删除本地数据。GPX 用于通用轨迹，KML 用于 Google Earth / 我的地图，CSV"
                    + " 用于保留全部原始点。\n\n"
                    + "星迹 "
                    + BuildConfig.VERSION_NAME
                    + " · build "
                    + BuildConfig.VERSION_CODE
                    + "\n"
                    + "目标 Android 16 · GitHub Actions 云端构建\n"
                    + "本PR加入可选OpenFreeMap在线道路底图；没有离线地图包、路线规划或模拟定位。当前位置支持 WGS84 / GCJ-02"
                    + " 显示切换（离线近似转换）；轨迹与收藏始终使用 WGS84。")
        )
        a.body.addView(privacy)
    }
}

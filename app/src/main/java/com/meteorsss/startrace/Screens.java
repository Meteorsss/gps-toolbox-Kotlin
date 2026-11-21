package com.meteorsss.startrace;

import android.content.Intent;
import android.graphics.Typeface;
import android.location.*;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Supplier;

public final class Screens {
  private Screens() {}

  public static void render(MainActivity a) {
    switch (a.mode) {
      case "settings":
        settings(a);
        return;
      case "places":
        places(a);
        return;
      case "track":
        track(a);
        return;
      case "compass":
        compass(a, false);
        return;
      case "guide":
        compass(a, true);
        return;
      case "speed":
        speed(a);
        return;
      case "diagnostics":
        diagnostics(a);
        return;
      default:
        break;
    }
    switch (a.page) {
      case 1:
        satellites(a);
        break;
      case 2:
        records(a);
        break;
      case 3:
        tools(a);
        break;
      default:
        home(a);
    }
  }

  private static String f(String format, Object... args) {
    return String.format(Locale.US, format, args);
  }

  private static LinearLayout metric(
      MainActivity a, String name, Supplier<String> value, String caption) {
    Ui u = a.u;
    LinearLayout p = u.panel();
    p.addView(u.text(name, 12, u.muted));
    u.gap(p, 11);
    TextView t = u.title("—", 24);
    a.bind(t, value);
    p.addView(t);
    u.gap(p, 5);
    p.addView(u.text(caption, 10, u.muted));
    return p;
  }

  private static void chart(
      MainActivity a, LinearLayout panel, String kind, long track, Store.Place place, int height) {
    if (kind.equals("compass")) {
      CompassView compass = new CompassView(a, place);
      panel.addView(compass, new LinearLayout.LayoutParams(-1, a.u.dp(height)));
      a.registerCompass(compass);
      return;
    }
    Charts chart = new Charts(a, kind, track, place);
    panel.addView(chart, new LinearLayout.LayoutParams(-1, a.u.dp(height)));
    a.watch(chart::refreshData);
  }

  private static void button(MainActivity a, LinearLayout p, String title, Runnable action) {
    p.addView(a.u.button(title, false, action));
    a.u.gap(p, 9);
  }

  private static void home(MainActivity a) {
    Ui u = a.u;
    GnssEngine e = a.e;
    LinearLayout b = a.body;
    u.section(b, "YOUR POSITION, CLEARLY", "此刻，在哪里");
    if (!e.precise()) {
      b.addView(u.button("启用精确定位", true, a::askLocation));
      u.gap(b, 14);
    }
    button(a, b, "打开地图 · 当前位置", () -> a.openMap(-1));
    LinearLayout hero = u.panel();
    TextView speedLabel = u.text("当前速度 · LIVE SPEED", 12, u.accent);
    speedLabel.setTag("dashboard-speed-label");
    speedLabel.setGravity(Gravity.CENTER);
    hero.addView(speedLabel, new LinearLayout.LayoutParams(-1, -2));
    u.gap(hero, 12);
    LinearLayout row = u.row();
    row.setTag("dashboard-speed-row");
    row.setGravity(Gravity.CENTER);
    TextView speed = u.title("—", 64);
    speed.setTag("dashboard-speed-value");
    speed.setGravity(Gravity.CENTER);
    a.bind(
        speed,
        () -> e.fresh() && e.location.hasSpeed() ? f("%.1f", e.location.getSpeed() * 3.6) : "—");
    row.addView(speed);
    TextView unit = u.text("  km/h", 17, u.muted);
    row.addView(unit);
    hero.addView(row, new LinearLayout.LayoutParams(-1, -2));
    u.gap(hero, 9);
    u.note(hero, "卫星速度 · 无有效速度时显示 —，不将未知速度当作 0。 ").setGravity(Gravity.CENTER);
    b.addView(hero);
    u.pair(
        b,
        metric(
            a,
            "水平精度估计",
            () -> e.fresh() && e.location.hasAccuracy() ? f("%.1f", e.location.getAccuracy()) : "—",
            "m · 估计半径，非保证误差"),
        metric(
            a,
            "GNSS 高度",
            () -> e.fresh() && e.location.hasAltitude() ? f("%.1f", e.location.getAltitude()) : "—",
            "m · WGS84 椭球高"));
    u.gap(b, 12);
    u.pair(
        b,
        metric(
            a,
            "独立卫星",
            () ->
                e.satelliteDataFresh()
                    ? e.uniqueCount(true) + " / " + e.uniqueCount(false)
                    : "— / —",
            "参与定位 / 上报 · 颗"),
        metric(
            a,
            "本次首次定位",
            () -> e.firstFix >= 0 ? f("%.1f s", e.firstFix / 1000.0) : "—",
            "从本次定位请求开始"));
    u.gap(b, 12);
    LinearLayout coords = u.panel();
    LinearLayout title = u.row();
    title.addView(u.title("当前位置", 16), new LinearLayout.LayoutParams(0, -2, 1));
    title.addView(u.text(a.coordinateLabel(), 11, u.accent));
    coords.addView(title);
    u.gap(coords, 12);
    u.pair(
        coords,
        u.button("WGS84", !a.gcj(), () -> a.setCoordinateSystem(false)),
        u.button("GCJ-02", a.gcj(), () -> a.setCoordinateSystem(true)));
    u.gap(coords, 14);
    TextView coord = u.text("等待定位…", 19, u.text);
    coord.setTypeface(Typeface.MONOSPACE);
    a.bind(
        coord,
        () -> {
          if (!e.fresh()) return "等待有效位置…";
          Location l = e.location;
          double lat = l.getLatitude(), lon = l.getLongitude();
          if (a.gcj()) {
            Gcj02.Coordinate converted = Gcj02.fromWgs84(lat, lon);
            if (!converted.converted) return "超出近似转换范围\n请切换到 WGS84";
            lat = converted.latitude;
            lon = converted.longitude;
          }
          return a.dms()
              ? Geo.dms(lat, true) + "\n" + Geo.dms(lon, false)
              : f("纬度  %.7f°\n经度  %.7f°", lat, lon);
        });
    coords.addView(coord);
    u.gap(coords, 10);
    u.note(
        coords,
        a.gcj()
            ? "GCJ-02 为离线近似转换，仅适用于大陆相关地图使用场景。覆盖范围采用粗略矩形，不等于国界；边界及港澳台等地区请核对地图来源。"
            : "WGS84 是原始卫星坐标；切换显示不会修改轨迹和收藏地点。");
    u.gap(coords, 12);
    TextView bearing = u.text("", 12, u.muted);
    a.bind(
        bearing,
        () ->
            e.fresh() && e.location.hasBearing()
                ? f("运动方向 %.0f°（真北）", e.location.getBearing())
                : "运动方向暂无 · 不等于手机朝向");
    coords.addView(bearing);
    u.gap(coords, 15);
    u.pair(
        coords,
        u.button("保存地点", false, a::saveHere),
        u.button("复制 " + a.coordinateLabel(), false, a::copyHere));
    coord.setOnLongClickListener(v -> {
      a.copyHere();
      return true;
    });
    b.addView(coords);
    b.addView(u.button(TrackService.active == null ? "开始记录轨迹" : "查看正在记录的轨迹", true, a::record));
    u.gap(b, 12);
    button(a, b, "定位权限与开关", a::askLocation);
    u.note(b, "无账号 · 轨迹本地保存 · 在线地图需另行同意\n建议在开阔处等待首次定位；室内可能无法获取卫星位置。");
  }

  private static void satellites(MainActivity a) {
    Ui u = a.u;
    LinearLayout b = a.body;
    u.section(b, "ONE SKY, TWO COUNTS", "头顶的卫星");
    LinearLayout totals = u.panel();
    totals.addView(u.title("参与定位 / 系统上报", 17));
    u.gap(totals, 14);
    totals.addView(u.text("频率信号 · 条（同星多频分别计数）", 12, u.accent));
    u.gap(totals, 7);
    TextView entries = u.title("— / —", 34);
    a.bind(
        entries,
        () -> {
          SatelliteStats.Counts c = a.e.satelliteStats().total;
          return a.e.satelliteDataFresh() ? c.usedEntries() + " / " + c.entries() : "— / —";
        });
    totals.addView(entries);
    u.gap(totals, 16);
    totals.addView(u.text("独立卫星 · 颗（星座 + SVID 去重）", 12, u.accent));
    u.gap(totals, 7);
    TextView unique = u.title("— / —", 30);
    a.bind(
        unique,
        () -> {
          SatelliteStats.Counts c = a.e.satelliteStats().total;
          return a.e.satelliteDataFresh() ? c.used() + " / " + c.received() : "— / —";
        });
    totals.addView(unique);
    u.gap(totals, 12);
    TextView positive = u.text("", 12, u.muted);
    a.bind(
        positive,
        () -> {
          SatelliteStats.Counts c = a.e.satelliteStats().total;
          return a.e.satelliteDataFresh()
              ? "C/N₀ > 0：" + c.trackedEntries() + " 条信号 / " + c.tracked() + " 颗独立卫星"
              : "等待新鲜卫星状态；— 不代表天空中没有卫星";
        });
    totals.addView(positive);
    u.gap(totals, 7);
    TextView age = u.text("", 12, u.muted);
    a.bind(
        age,
        () ->
            a.e.satelliteDataFresh()
                ? "当前快照 · "
                    + Math.max(0, (SystemClock.elapsedRealtime() - a.e.satelliteAt) / 1000)
                    + " 秒前更新 · 不累加历史"
                : "暂无可用快照");
    totals.addView(age);
    u.gap(totals, 12);
    u.note(
        totals,
        "同一颗卫星的两个频率会产生两条记录，但仍是一颗卫星。与按频率计数的软件对比时，请看上面的“频率信号”，不要与“独立卫星”混比。所有斜线左侧均为参与定位，右侧为系统上报。");
    b.addView(totals);
    LinearLayout table = u.panel();
    table.addView(u.title("按卫星系统统计", 18));
    u.gap(table, 8);
    u.note(table, "两列均为：参与定位 / 系统上报");
    u.gap(table, 12);
    LinearLayout header = u.row();
    header.addView(u.text("系统", 11, u.muted), new LinearLayout.LayoutParams(0, -2, 1.1f));
    for (String label : new String[] {"独立卫星·颗", "频率信号·条"}) {
      TextView t = u.text(label, 11, u.muted);
      t.setGravity(Gravity.END);
      header.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    }
    table.addView(header);
    u.gap(table, 6);
    int[] systems = {
      GnssStatus.CONSTELLATION_BEIDOU,
      GnssStatus.CONSTELLATION_GPS,
      GnssStatus.CONSTELLATION_GALILEO,
      GnssStatus.CONSTELLATION_GLONASS,
      GnssStatus.CONSTELLATION_QZSS,
      GnssStatus.CONSTELLATION_IRNSS,
      GnssStatus.CONSTELLATION_SBAS,
      GnssStatus.CONSTELLATION_UNKNOWN
    };
    for (int system : systems) {
      LinearLayout row = u.row();
      row.setPadding(0, u.dp(11), 0, u.dp(11));
      row.addView(
          u.text(GnssEngine.systemName(system), 13, u.constellation(system)),
          new LinearLayout.LayoutParams(0, -2, 1.1f));
      TextView satellites = u.title("— / —", 16), signals = u.title("— / —", 16);
      satellites.setGravity(Gravity.END);
      signals.setGravity(Gravity.END);
      satellites.setContentDescription(GnssEngine.systemName(system) + " 独立卫星 参与定位 / 系统上报");
      signals.setContentDescription(GnssEngine.systemName(system) + " 频率信号 参与定位 / 系统上报");
      a.bind(
          satellites,
          () -> {
            SatelliteStats.Counts c = a.e.satelliteStats().system(system);
            return a.e.satelliteDataFresh() ? c.used() + " / " + c.received() : "— / —";
          });
      a.bind(
          signals,
          () -> {
            SatelliteStats.Counts c = a.e.satelliteStats().system(system);
            return a.e.satelliteDataFresh() ? c.usedEntries() + " / " + c.entries() : "— / —";
          });
      row.addView(satellites, new LinearLayout.LayoutParams(0, -2, 1));
      row.addView(signals, new LinearLayout.LayoutParams(0, -2, 1));
      table.addView(row);
    }
    b.addView(table);
    LinearLayout sky = u.panel();
    sky.addView(u.title("天空图 · 每颗卫星只画一次", 16));
    chart(a, sky, "sky", -1, null, 300);
    u.note(sky, "实心 = 至少一条频率参与定位；空心 = 当前未参与\n青绿 GPS · 蓝色北斗 · 粉色 Galileo · 橙色 GLONASS");
    b.addView(sky);
    LinearLayout signal = u.panel();
    signal.addView(u.title("全部频率信号明细", 18));
    u.gap(signal, 8);
    u.pair(
        signal,
        u.button("同星多频分组", a.groupSignals(), () -> a.setGroupSignals(true)),
        u.button("按信号强度", !a.groupSignals(), () -> a.setGroupSignals(false)));
    u.gap(signal, 10);
    u.note(
        signal,
        "相同星座和 SVID 的不同 MHz 是同一颗卫星的不同频率。✓ 为该条记录参与定位，— 为未参与；C/N₀ 单位 dB-Hz。保留系统上报的全部条目，包括 0 信号值。");
    u.gap(signal, 15);
    chart(a, signal, "signal", -1, null, 64);
    b.addView(signal);
    LinearLayout explanation = u.panel();
    explanation.addView(u.title("这两个数为什么不同？", 17));
    u.gap(explanation, 8);
    u.note(
        explanation,
        "“系统上报”不是保证正在锁定；C/N₀=0 的条目也可能存在。参与定位采用系统 usedInFix 标记，同一颗卫星只要任一频率参与，就计为一颗参与卫星。\n\n"
            + "不同时间、遮挡、手机姿态或定位请求状态都会使快照变化。对照时应保持同一位置、同一姿态，并尽量在相近时刻比较同一种口径；数量大不等于定位更准。");
    b.addView(explanation);
    button(a, b, "查看设备与定位诊断", () -> a.navigate("diagnostics"));
  }

  private static void records(MainActivity a) {
    Ui u = a.u;
    LinearLayout b = a.body;
    u.section(b, "LEAVE A TRACE", "把走过的路留下");
    button(a, b, "轨迹点间隔：" + Preferences.interval(a) + " 秒（点击选择）", a::selectRecordingInterval);
    TrackService t = TrackService.active;
    if (t != null) {
      LinearLayout live = u.panel();
      live.addView(u.text(t.paused ? "Ⅱ  已暂停" : "●  正在记录", 12, u.accent));
      u.gap(live, 12);
      TextView elapsed = u.title("00:00:00", 38);
      a.bind(elapsed, () -> Geo.duration(t.duration()));
      live.addView(elapsed);
      u.gap(live, 8);
      TextView d = u.title("0 m", 23);
      a.bind(d, () -> Geo.meters(t.distance));
      live.addView(d);
      u.gap(live, 8);
      u.note(live, "保存间隔 " + t.intervalSeconds() + " 秒。缺失、暂停、低精度或异常跳点会断开显示；模拟位置不计入有效轨迹。");
      button(a, live, "在地图上看实时轨迹", () -> a.openMap(t.trackId));
      TrackStatsPanel liveStats = new TrackStatsPanel(a, u, t.trackId);
      live.addView(liveStats);
      a.watch(liveStats::refresh);
      chart(a, live, "track", t.trackId, null, 230);
      u.gap(live, 8);
      u.pair(
          live,
          u.button(t.paused ? "继续" : "暂停", false, () -> a.service(t.paused ? "RESUME" : "PAUSE")),
          u.button("结束并保存", true, () -> a.service("STOP")));
      b.addView(live);
    } else {
      b.addView(u.button("开始新轨迹", true, a::record));
      u.gap(b, 14);
    }
    button(a, b, "收藏地点 →", () -> a.navigate("places"));
    u.gap(b, 8);
    b.addView(u.title("历史轨迹", 19));
    u.gap(b, 12);
    List<Store.Track> list = a.app.store.tracks();
    if (list.isEmpty()) {
      LinearLayout empty = u.panel();
      empty.addView(u.title("第一段旅程，等你出发", 18));
      u.gap(empty, 8);
      u.note(empty, "记录默认关闭。只有点击开始后才会持续记录，结束后保存在这台手机上。");
      b.addView(empty);
    }
    for (Store.Track track : list) {
      if (t != null && track.id == t.trackId) continue;
      LinearLayout p = u.panel();
      p.addView(u.title(track.name, 17));
      u.gap(p, 8);
      p.addView(
          u.text(
              Geo.meters(track.distance) + "  ·  " + Geo.duration(track.duration), 15, u.accent));
      u.gap(p, 6);
      p.addView(
          u.text(
              date(track.start)
                  + "  ·  "
                  + status(track.status)
                  + "\n"
                  + track.points
                  + " 有效点 / "
                  + track.raw
                  + " 原始点",
              12,
              u.muted));
      p.setOnClickListener(v -> a.trackActions(track));
      p.setFocusable(true);
      p.setContentDescription(track.name + "，查看或导出轨迹");
      b.addView(p);
    }
    u.note(b, "距离经过抗漂移过滤，是估计值。卸载会删除本地记录，请先导出备份。");
  }

  private static String status(String s) {
    switch (s) {
      case "recording":
        return "记录中";
      case "paused":
        return "已暂停";
      case "finished":
        return "已完成";
      default:
        return "意外中断 · 已保留数据";
    }
  }

  private static String date(long time) {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(time));
  }

  private static void track(MainActivity a) {
    Ui u = a.u;
    Store.Track t = a.app.store.track(a.selectedTrack);
    if (t == null) {
      u.note(a.body, "这段轨迹不存在。");
      return;
    }
    u.section(a.body, "TRACK DETAILS", t.name);
    LinearLayout p = u.panel();
    p.addView(u.title(Geo.meters(t.distance), 34));
    u.gap(p, 9);
    p.addView(u.text(Geo.duration(t.duration) + "  ·  " + status(t.status), 15, u.muted));
    u.gap(p, 8);
    p.addView(
        u.text(
            "平均速度 "
                + (t.duration > 0 ? f("%.1f km/h", t.distance / (t.duration / 1000.0) * 3.6) : "—")
                + "\n"
                + t.points
                + " 有效点 / "
                + t.raw
                + " 原始点",
            13,
            u.muted));
    button(a, p, "在地图上查看整段轨迹", () -> a.openMap(t.id));
    TrackStatsPanel stats = new TrackStatsPanel(a, u, t.id);
    p.addView(stats);
    a.watch(stats::refresh);
    chart(a, p, "track", t.id, null, 320);
    u.note(p, "橙点为起点，青绿点为终点。轨迹是相对示意图，不含道路底图；长距离和极地区域可能有投影变形。");
    a.body.addView(p);
    boolean live = TrackService.active != null && TrackService.active.trackId == t.id;
    if (!live) {
      button(a, a.body, "导出 GPX（有效轨迹）", () -> a.export(t, false));
      button(a, a.body, "导出 KML（Google Earth / 我的地图）", () -> a.export(t, TrackExport.KML));
      button(a, a.body, "导出 CSV（全部原始点）", () -> a.export(t, true));
    } else u.note(a.body, "请结束记录后再导出，以获得一致的完整文件。");
    u.note(
        a.body,
        "GPX / KML 保留断点，不插值补线。KML 使用 WGS84 贴地轨迹，不导出椭球高度；逐点时间与测量值用 GPX / CSV。Google Earth 可导入"
            + " KML；Google 地图需先在网页版“我的地图”导入（KML 最大 5 MB）。CSV 保留被过滤的位置与模拟标记。");
  }

  private static void places(MainActivity a) {
    Ui u = a.u;
    u.section(a.body, "PLACES TO REMEMBER", "收藏地点");
    u.pair(
        a.body,
        u.button("保存当前位置", true, a::saveHere),
        u.button("输入坐标", false, () -> a.placeDialog(null, null)));
    u.gap(a.body, 18);
    List<Store.Place> places = a.app.store.places();
    if (places.isEmpty()) u.note(a.body, "还没有收藏地点。可以保存停车位置、出发点或常去地点。所有坐标均为 WGS84。");
    for (Store.Place p : places) {
      LinearLayout panel = u.panel();
      panel.addView(u.title(p.name, 19));
      u.gap(panel, 8);
      panel.addView(u.text(f("%.6f, %.6f", p.lat, p.lon), 13, u.muted));
      if (!p.note.isEmpty()) {
        u.gap(panel, 7);
        panel.addView(u.text(p.note, 13, u.muted));
      }
      u.gap(panel, 8);
      TextView distance = u.text("", 14, u.accent);
      a.bind(
          distance,
          () ->
              a.e.fresh()
                  ? "直线距离 "
                      + Geo.meters(
                          Geo.distance(
                              a.e.location.getLatitude(),
                              a.e.location.getLongitude(),
                              p.lat,
                              p.lon))
                  : "点击查看操作");
      panel.addView(distance);
      u.gap(panel, 12);
      u.pair(
          panel,
          u.button(
              "指向这里",
              false,
              () -> {
                a.destination = p;
                a.navigate("guide");
              }),
          u.button("编辑名称 / 备注", false, () -> a.editPlaceDialog(p)));
      panel.setOnClickListener(v -> a.placeActions(p));
      a.body.addView(panel);
    }
  }

  private static void tools(MainActivity a) {
    Ui u = a.u;
    u.section(a.body, "SMALL TOOLS, REAL USE", "随手可用");
    tool(a, "地图与轨迹", "OpenFreeMap · 免账号免 Key", () -> a.openMap(-1));
    tool(a, "方向指南针", "磁北方向 · 传感器状态", () -> a.navigate("compass"));
    tool(a, "大字速度表", "专注速度 · 可选保持亮屏", () -> a.navigate("speed"));
    tool(a, "坐标格式转换", "十进制度 ↔ 度分秒", a::coordinateTool);
    tool(a, "两点测距", "WGS84 · 直线距离与方位", a::distanceTool);
    tool(a, "收藏地点", "保存位置 · 指向目标", () -> a.navigate("places"));
    tool(a, "定位诊断", "权限、设备与数据状态", () -> a.navigate("diagnostics"));
  }

  private static void tool(MainActivity a, String title, String description, Runnable action) {
    LinearLayout p = a.u.panel();
    p.addView(a.u.title(title + "  ›", 19));
    a.u.gap(p, 8);
    p.addView(a.u.text(description, 13, a.u.muted));
    p.setOnClickListener(v -> action.run());
    p.setMinimumHeight(a.u.dp(92));
    p.setFocusable(true);
    p.setContentDescription(title);
    a.body.addView(p);
  }

  private static void compass(MainActivity a, boolean guide) {
    Ui u = a.u;
    Store.Place target = guide ? a.destination : null;
    if (guide && target == null) {
      u.note(a.body, "请先选择目标地点。");
      return;
    }
    u.section(
        a.body,
        guide ? "FIND YOUR WAY BACK" : "FOLLOW YOUR BEARING",
        guide ? target.name : "方向指南针");
    LinearLayout p = u.panel();
    chart(a, p, "compass", -1, target, 330);
    TextView state = u.text("", 13, u.muted);
    a.bind(
        state,
        () -> !a.e.hasCompass ? "设备没有提供旋转矢量传感器" : guide ? "方位基准：真北（由磁偏角修正）" : "方位基准：磁北 · 请将手机平放");
    p.addView(state);
    u.gap(p, 8);
    TextView quality = u.text("", 13, u.warning);
    a.bind(
        quality,
        () -> a.e.sensorAccuracy <= 1 ? "方向可靠性较低：请远离磁铁、金属或磁吸壳，并缓慢转动手机。" : "方向仅供参考，不作为专业测量依据。");
    p.addView(quality);
    a.body.addView(p);
    if (guide) {
      LinearLayout info = u.panel();
      TextView distance = u.title("等待定位", 32);
      a.bind(
          distance,
          () ->
              a.e.fresh()
                  ? Geo.meters(
                      Geo.distance(
                          a.e.location.getLatitude(),
                          a.e.location.getLongitude(),
                          target.lat,
                          target.lon))
                  : "等待新鲜位置");
      info.addView(distance);
      u.gap(info, 10);
      TextView bearing = u.text("", 15, u.accent);
      a.bind(
          bearing,
          () ->
              a.e.fresh()
                  ? f(
                      "目标真北方位 %.0f°",
                      Geo.bearing(
                          a.e.location.getLatitude(),
                          a.e.location.getLongitude(),
                          target.lat,
                          target.lon))
                  : "需要定位后才能计算目标方向");
      info.addView(bearing);
      u.gap(info, 10);
      u.note(info, "这是直线方向指引，不是道路导航。不能判断障碍、地形或可通行路线；接近目标时请结合定位精度判断。");
      a.body.addView(info);
    }
  }

  private static void speed(MainActivity a) {
    Ui u = a.u;
    u.section(a.body, "LIVE SPEED", "大字速度表");
    if (!a.e.precise() || !a.e.gpsEnabled()) button(a, a.body, "启用精确定位 · 进入即自动测速", a::askLocation);
    LinearLayout p = u.panel();
    u.gap(p, 24);
    TextView speed = u.title("—", 76);
    speed.setGravity(Gravity.CENTER);
    speed.setTag("large-speed-current");
    a.bind(
        speed,
        () ->
            a.e.fresh()
                    && a.e.location.hasSpeed()
                    && Float.isFinite(a.e.location.getSpeed())
                    && a.e.location.getSpeed() >= 0
                ? f("%.1f", a.e.location.getSpeed() * 3.6)
                : "—");
    p.addView(speed, new LinearLayout.LayoutParams(-1, -2));
    TextView unit = u.text("km/h", 22, u.accent);
    unit.setGravity(Gravity.CENTER);
    p.addView(unit, new LinearLayout.LayoutParams(-1, -2));
    u.gap(p, 8);
    TextView metres = u.text("— m/s", 24, u.accent);
    metres.setTag("large-speed-current-mps");
    metres.setGravity(Gravity.CENTER);
    a.bind(
        metres,
        () ->
            a.e.fresh()
                    && a.e.location.hasSpeed()
                    && Float.isFinite(a.e.location.getSpeed())
                    && a.e.location.getSpeed() >= 0
                ? f("%.2f m/s", a.e.location.getSpeed())
                : "— m/s");
    p.addView(metres, new LinearLayout.LayoutParams(-1, -2));
    u.gap(p, 24);
    TextView statistics = u.text("", 16, u.accent);
    statistics.setTag("large-speed-session-stats");
    statistics.setGravity(Gravity.CENTER);
    a.bind(
        statistics,
        () -> {
          SpeedSession.Snapshot s = a.speedStats();
          if (s == null) return "本次测速 · 等待进入";
          String max = Double.isFinite(s.maxMps) ? f("%.1f km/h", s.maxMps * 3.6) : "—";
          String avg = Double.isFinite(s.averageMps) ? f("%.1f km/h", s.averageMps * 3.6) : "—";
          return (s.validPoints == 0 ? "本次测速 · 等待有效定位" : "本次测速 · 自动统计中")
              + "\n最高速度  "
              + max
              + "\n平均速度  "
              + avg
              + "\n累计里程  "
              + Geo.meters(s.distance);
        });
    p.addView(statistics, new LinearLayout.LayoutParams(-1, -2));
    u.gap(p, 24);
    Switch keep = new Switch(a);
    keep.setText("保持屏幕常亮");
    keep.setTextColor(u.text);
    keep.setMinHeight(u.dp(48));
    keep.setOnCheckedChangeListener((v, on) -> a.keepAwake(on));
    p.addView(keep);
    a.body.addView(p);
    u.note(
        a.body,
        "进入本页即自动开始独立测速，不需要开启轨迹记录；离开本页即清空，再进入从零开始，不保存历史或轨迹文件。临时退到后台暂停统计，返回同一页继续，不连接后台移动。\n\n"
            + "平均速度=本页抗漂移里程÷前台测速时长（含静止），最高速度为本次有效GNSS速度样本的最大值。定位未到或样本不足显示—；模拟、过期或低精度位置不计统计。轨迹记录的开始、暂停、间隔与统计均不影响本页。所有数值为估计，请勿在驾驶时操作手机。");
  }

  private static void diagnostics(MainActivity a) {
    Ui u = a.u;
    u.section(a.body, "KNOW WHAT IS HAPPENING", "定位诊断");
    LinearLayout p = u.panel();
    TextView details = u.text("", 14, u.text);
    a.bind(
        details,
        () ->
            "设备  "
                + Build.MANUFACTURER
                + " "
                + Build.MODEL
                + "\nAndroid  "
                + Build.VERSION.RELEASE
                + " / API "
                + Build.VERSION.SDK_INT
                + "\n精确位置权限  "
                + (a.e.precise() ? "已授予" : "未授予")
                + "\n系统定位  "
                + (a.e.enabled() ? "开启" : "关闭")
                + "\nGPS 提供器  "
                + (a.e.gpsEnabled() ? "开启" : "关闭或不可用")
                + "\n旋转矢量传感器  "
                + (a.e.hasCompass ? "可用" : "未提供")
                + "\n数据年龄  "
                + (a.e.location == null ? "无位置" : a.e.age() / 1000 + " 秒")
                + "\n当前提供器  "
                + (a.e.location == null ? "—" : a.e.location.getProvider())
                + "\n模拟位置  "
                + (a.e.location == null ? "—" : a.e.location.isMock() ? "是" : "否")
                + "\n卫星信号记录  "
                + a.e.currentSatellites().size()
                + " 条\n频率字段  "
                + (a.e.currentSatellites().stream().anyMatch(s -> s.hasFrequency)
                    ? "设备有提供"
                    : "当前未提供 / 无信号")
                + "\n"
                + a.e.problem);
    details.setLineSpacing(u.dp(7), 1);
    p.addView(details);
    a.body.addView(p);
    button(a, a.body, "请求精确定位", a::askLocation);
    button(a, a.body, "应用权限设置", a::appSettings);
    button(
        a,
        a.body,
        "系统定位设置",
        () -> a.openSystem(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
    u.note(a.body, "首次定位耗时不是严格冷启动测试。频率有数据不等于已经验证双频性能。诊断只检查可观察状态，不提供虚假的一键增强。");
  }

  private static void settings(MainActivity a) {
    Ui u = a.u;
    u.section(a.body, "MAKE IT YOURS", "设置与关于");
    button(a, a.body, "色彩模式：" + Preferences.theme(a).label, a::selectTheme);
    button(a, a.body, "轨迹点间隔：" + Preferences.interval(a) + " 秒", a::selectRecordingInterval);
    u.note(
        a.body,
        "间隔控制保存新定位点，不是保证定位频率；仪表仍约每秒请求定位。大间隔省存储但可能漏弯道或速度峰值；调整间隔会开始新片段，不影响已保存点。CSV保留按所选间隔保存的原始点。");
    button(a, a.body, a.dms() ? "坐标显示：度分秒（点击切换）" : "坐标显示：十进制度（点击切换）", a::toggleDms);
    button(a, a.body, "定位与通知权限", a::appSettings);
    button(a, a.body, "在线地图隐私 / 撤回同意", a::mapPrivacy);
    button(a, a.body, "第三方许可 · 离线坐标转换", a::thirdPartyNotices);
    button(
        a,
        a.body,
        "系统电池优化设置",
        () -> a.openSystem(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
    LinearLayout p = u.panel();
    p.addView(u.title("红米 / 澎湃 OS 使用提示", 17));
    u.gap(p, 10);
    u.note(
        p,
        "如需长时间熄屏记录，可在系统应用设置中检查后台电池策略。菜单因系统版本不同而异，必要时允许应用在后台运行。\n\n"
            + "星迹不会自动更改系统设置，也不承诺永不被系统终止。记录意外中断后，下次打开会保留已落盘的数据并标为中断。");
    a.body.addView(p);
    LinearLayout privacy = u.panel();
    privacy.addView(u.title("你的轨迹，只在你手里", 18));
    u.gap(privacy, 10);
    u.note(
        privacy,
        "无账号、广告或分析 SDK。可选在线地图会联网获取底图，须先同意；轨迹仍只保存本机。未申请后台始终定位。只有手动开始轨迹后，才使用前台服务继续记录。\n\n"
            + "导出文件包含精确位置，请谨慎分享。卸载应用会删除本地数据。GPX 用于通用轨迹，KML 用于 Google Earth / 我的地图，CSV"
            + " 用于保留全部原始点。\n\n"
            + "星迹 "
            + BuildConfig.VERSION_NAME
            + " · build "
            + BuildConfig.VERSION_CODE
            + "\n"
            + "目标 Android 16 · GitHub Actions 云端构建\n"
            + "本PR加入可选OpenFreeMap在线道路底图；没有离线地图包、路线规划或模拟定位。当前位置支持 WGS84 / GCJ-02"
            + " 显示切换（离线近似转换）；轨迹与收藏始终使用 WGS84。");
    a.body.addView(privacy);
  }
}

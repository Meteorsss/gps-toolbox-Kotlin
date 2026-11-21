package com.meteorsss.startrace;

import static org.maplibre.android.style.layers.PropertyFactory.*;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.*;
import org.maplibre.android.maps.*;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.*;

/** The map is a view of local WGS84 data, never a replacement recorder or location provider. */
public final class MapActivity extends Activity {
  private App app;
  private Ui ui;
  private MapView mapView;
  private MapLibreMap map;
  private Style style;
  private FrameLayout holder;
  private TextView mapStatus, fixStatus, summary, followButton, pauseButton;
  private LinearLayout controls;
  private TrackStatsPanel statisticsPanel;
  private AlertDialog statisticsDialog;
  private boolean started,
      resumed,
      destroyed,
      online,
      following = true,
      loadingData,
      fitPending = true,
      notificationAsked,
      localFallback,
      rendered;
  private String controlKey = "";
  private long requestedTrack = -1, displayTrack = -1, loadSequence, lastFixElapsed;
  private Bundle mapState;
  private List<TrackPoint> points = Collections.emptyList();
  private List<Store.Place> places = Collections.emptyList();
  private TrackAnalytics.Result historicalStats;
  private String historicalName = "";
  private boolean historicalLoaded;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final GnssEngine.Observer fixObserver =
      () -> {
        if (resumed) updatePosition();
      };
  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (!resumed) return;
          updatePosition();
          updateControls();
          loadTrack();
          if (statisticsPanel != null) statisticsPanel.refresh();
          handler.postDelayed(this, 2500);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    setTheme(Preferences.light(this) ? R.style.AppThemeLight : R.style.AppTheme);
    super.onCreate(state);
    app = (App) getApplication();
    ui = new Ui(this, Preferences.light(this));
    requestedTrack = getIntent().getLongExtra("track_id", -1);
    displayTrack = requestedTrack;
    following = requestedTrack < 0;
    mapState = state == null ? null : state.getBundle("map_state");
    if (state != null) {
      requestedTrack = state.getLong("requested_track", requestedTrack);
      fitPending = state.getBoolean("fit_pending", true);
      following = state.getBoolean("following", true);
      displayTrack = state.getLong("display_track", requestedTrack);
    }
    getWindow().setDecorFitsSystemWindows(false);
    LinearLayout root = ui.column();
    root.setBackgroundColor(ui.bg);
    root.setOnApplyWindowInsetsListener((v, i) -> {
      android.graphics.Insets in =
          i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      v.setPadding(in.left, in.top, in.right, in.bottom);
      return i;
    });
    LinearLayout toolbar = ui.row();
    toolbar.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
    toolbar.addView(ui.button("返回", false, this::finish));
    TextView title = ui.title(requestedTrack >= 0 ? "轨迹地图" : "地图 · 当前位置", 19);
    title.setGravity(Gravity.CENTER);
    toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    toolbar.addView(ui.button("底图", false, this::showMapPolicy));
    root.addView(toolbar);
    mapStatus = ui.text("地图尚未联网", 12, ui.muted);
    mapStatus.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4));
    root.addView(mapStatus);
    fixStatus = ui.text("等待定位", 12, ui.accent);
    fixStatus.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(6));
    root.addView(fixStatus);
    holder = new FrameLayout(this);
    root.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout attribution = ui.row();
    attribution.setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5));
    for (String[] item :
        new String[][] {
          {"MapLibre", "https://maplibre.org/"},
          {"OpenFreeMap", "https://openfreemap.org/"},
          {"© OpenMapTiles", "https://openmaptiles.org/"},
          {"© OSM contributors", "https://www.openstreetmap.org/copyright"}
        }) {
      TextView link = ui.text(item[0], 9, ui.muted);
      link.setPadding(ui.dp(4), ui.dp(3), ui.dp(4), ui.dp(3));
      link.setOnClickListener(v -> openLink(item[1]));
      attribution.addView(link, new LinearLayout.LayoutParams(0, -2, 1));
    }
    root.addView(attribution);
    LinearLayout actions = ui.row();
    actions.setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(6));
    followButton = ui.button("跟随位置", true, this::follow);
    actions.addView(followButton, new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(
        ui.button("全程", false, this::fitTrack), new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(
        ui.button(
            "重试底图",
            false,
            () -> {
              if (map != null) {
                loadStyle();
              } else showMapPolicy();
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(actions);
    summary = ui.text("", 13, ui.text);
    summary.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(8));
    root.addView(summary);
    summary.setOnClickListener(v -> showStatistics());
    controls = ui.row();
    controls.setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(8));
    root.addView(controls);
    setContentView(root);
    Preferences.applyWindow(this, ui.light);
    root.requestApplyInsets();
    getOnBackInvokedDispatcher()
        .registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finish);
    if (MapPolicy.allowed(this)
        || localTestStyle()
        || (state != null && state.getBoolean("map_chosen", false))) installMap();
    else showMapPolicy();
    updateControls();
  }

  private boolean localTestStyle() {
    return BuildConfig.DEBUG && getIntent().getBooleanExtra("test_local_style", false);
  }

  private void openLink(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      toast("没有可打开链接的浏览器");
    }
  }

  public void showMapPolicy() {
    new AlertDialog.Builder(this)
        .setTitle("在线地图与隐私")
        .setMessage(MapPolicy.DISCLOSURE)
        .setPositiveButton(
            "允许在线底图",
            (d, w) -> {
              MapPolicy.allow(this, true);
              switchMode();
            })
        .setNeutralButton(
            "仅轨迹，不联网",
            (d, w) -> {
              MapPolicy.allow(this, false);
              switchMode();
            })
        .setNegativeButton(
            "返回",
            (d, w) -> {
              if (mapView == null) finish();
            })
        .show();
  }

  private void switchMode() {
    if (mapView == null) installMap();
    else recreate();
  }

  private void installMap() {
    if (destroyed || mapView != null) return;
    online = MapPolicy.allowed(this) && !localTestStyle();
    try {
      MapLibre.getInstance(getApplicationContext());
      mapView = new MapView(this);
      holder.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
      mapView.onCreate(mapState);
      mapView.addOnDidFinishLoadingMapListener(() -> rendered = true);
      mapView.addOnDidFailLoadingMapListener(error -> {
        if (destroyed) return;
        mapStatus.setText("底图加载失败，可重试；定位与记录不受影响");
        if (map != null && style == null) loadLocalFallback();
      });
      if (started) mapView.onStart();
      if (resumed) mapView.onResume();
      mapView.getMapAsync(m -> {
        if (destroyed) return;
        map = m;
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setAttributionEnabled(true);
        map.addOnCameraMoveStartedListener(reason -> {
          if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
            following = false;
            updateFollowLabel();
          }
        });
        map.addOnMapClickListener(latLng -> {
          if (style == null) return false;
          List<Feature> hit =
              map.queryRenderedFeatures(
                  map.getProjection().toScreenLocation(latLng), "places-layer");
          if (hit.isEmpty()) return false;
          Feature f = hit.get(0);
          new AlertDialog.Builder(this)
              .setTitle(f.getStringProperty("name"))
              .setMessage(f.getStringProperty("note"))
              .setPositiveButton("关闭", null)
              .show();
          return true;
        });
        if (mapState == null)
          map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(35, 105), 3));
        loadStyle();
      });
    } catch (RuntimeException | UnsatisfiedLinkError error) {
      mapStatus.setText("地图引擎启动失败；请返回继续使用记录工具");
    }
  }

  private void loadStyle() {
    if (map == null || destroyed) return;
    localFallback = false;
    style = null;
    mapStatus.setText(online ? "正在请求 OpenFreeMap 在线底图…" : "仅本地轨迹 · 无道路底图 · 不联网");
    Style.Builder builder =
        online
            ? new Style.Builder().fromUri(MapPolicy.STYLE_URL)
            : new Style.Builder().fromJson(MapPolicy.LOCAL_STYLE);
    map.setStyle(
        builder,
        s -> {
          if (destroyed) return;
          style = s;
          addOverlays();
          mapStatus.setText(online ? "在线底图已载入 · 瓦片按视野加载" : "仅本地轨迹 · 无道路底图 · 不联网");
          lastFixElapsed = 0;
          updatePosition();
          drawTrack();
        });
    handler.removeCallbacks(styleTimeout);
    handler.postDelayed(styleTimeout, 20000);
  }

  private final Runnable styleTimeout =
      () -> {
        if (!destroyed && style == null && online) {
          mapStatus.setText("底图超时，已切换本地轨迹；可重试在线底图");
          loadLocalFallback();
        }
      };

  private void loadLocalFallback() {
    if (map == null || destroyed || localFallback) return;
    localFallback = true;
    handler.removeCallbacks(styleTimeout);
    map.setStyle(
        new Style.Builder().fromJson(MapPolicy.LOCAL_STYLE),
        s -> {
          if (destroyed) return;
          style = s;
          addOverlays();
          lastFixElapsed = 0;
          updatePosition();
          drawTrack();
        });
  }

  private void addOverlays() {
    for (String id : new String[] {"track", "position", "accuracy", "endpoints", "places"})
      style.addSource(new GeoJsonSource(id, FeatureCollection.fromFeatures(new Feature[] {})));
    style.addLayer(
        new FillLayer("accuracy-layer", "accuracy")
            .withProperties(fillColor("#56A9FF"), fillOpacity(.16f)));
    style.addLayer(
        new LineLayer("track-outline", "track")
            .withProperties(
                lineColor("#14283F"),
                lineWidth(7f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)));
    style.addLayer(
        new LineLayer("track-layer", "track")
            .withProperties(
                lineColor("#00BAAA"),
                lineWidth(4f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)));
    style.addLayer(
        new CircleLayer("places-layer", "places")
            .withProperties(
                circleColor("#AD72E8"),
                circleRadius(6f),
                circleStrokeWidth(2f),
                circleStrokeColor("#FFFFFF")));
    style.addLayer(
        new CircleLayer("endpoints-layer", "endpoints")
            .withProperties(
                circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
                circleRadius(6f),
                circleStrokeWidth(2f),
                circleStrokeColor("#FFFFFF")));
    style.addLayer(
        new CircleLayer("position-layer", "position")
            .withProperties(
                circleColor("#168FFF"),
                circleRadius(7f),
                circleStrokeWidth(3f),
                circleStrokeColor("#FFFFFF")));
  }

  private void setSource(String id, List<Feature> features) {
    if (style == null) return;
    GeoJsonSource source = style.getSourceAs(id);
    if (source != null) source.setGeoJson(FeatureCollection.fromFeatures(features));
  }

  private void updatePosition() {
    if (destroyed) return;
    boolean fresh = app.engine.fresh();
    Location l = app.engine.location;
    fixStatus.setText(
        fresh
            ? (l.isMock()
                ? "模拟位置 · 不计入有效轨迹"
                : "实时 WGS84 位置 · "
                    + (l.hasAccuracy()
                        ? String.format(Locale.US, "精度估计 %.0fm", l.getAccuracy())
                        : "精度未知"))
            : "暂无新鲜位置 · 不显示过期定位点");
    if (style == null) return;
    if (!fresh) {
      setSource("position", Collections.emptyList());
      setSource("accuracy", Collections.emptyList());
      return;
    }
    setSource(
        "position",
        Collections.singletonList(
            Feature.fromGeometry(Point.fromLngLat(l.getLongitude(), l.getLatitude()))));
    List<double[]> ring =
        l.hasAccuracy()
            ? TrackGeometry.accuracyRing(l.getLatitude(), l.getLongitude(), l.getAccuracy())
            : Collections.emptyList();
    List<Point> coordinates = new ArrayList<>();
    for (double[] p : ring) coordinates.add(Point.fromLngLat(p[1], p[0]));
    setSource(
        "accuracy",
        coordinates.isEmpty()
            ? Collections.emptyList()
            : Collections.singletonList(
                Feature.fromGeometry(Polygon.fromLngLats(Collections.singletonList(coordinates)))));
    if (following && requestedTrack < 0 && l.getElapsedRealtimeNanos() != lastFixElapsed) {
      lastFixElapsed = l.getElapsedRealtimeNanos();
      map.animateCamera(
          CameraUpdateFactory.newLatLngZoom(
              new LatLng(l.getLatitude(), l.getLongitude()),
              Math.max(15, map.getCameraPosition().zoom)),
          500);
    }
  }

  private void follow() {
    following = true;
    requestedTrack = -1;
    lastFixElapsed = 0;
    updateFollowLabel();
    if (!app.engine.precise()) {
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          1);
    } else if (!app.engine.gpsEnabled()) {
      startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }
    updatePosition();
    loadTrack();
  }

  private void updateFollowLabel() {
    followButton.setText(following ? "跟随中" : "回到当前位置");
  }

  private void loadTrack() {
    if (loadingData || destroyed) return;
    TrackService active = TrackService.active;
    if (requestedTrack < 0 && active != null) {
      if (displayTrack != active.trackId) {
        displayTrack = active.trackId;
        fitPending = true;
        historicalLoaded = false;
      }
      historicalStats = null;
    }
    final long id = displayTrack;
    final boolean live = active != null && active.trackId == id;
    if (id >= 0 && !live && historicalLoaded) return;
    loadingData = true;
    long sequence = ++loadSequence;
    app.io.execute(() -> {
      List<TrackPoint> p = Collections.emptyList();
      List<Store.Place> saved = Collections.emptyList();
      TrackAnalytics.Result stats = null;
      String name = "";
      try {
        saved = app.store.places();
        if (id >= 0) {
          p = app.store.preview(id, 5000);
          Store.Track t = app.store.track(id);
          if (t != null) {
            name = t.name;
            if (!live) stats = app.store.analyze(id, t.duration, t.distance);
          }
        }
      } catch (RuntimeException ignored) {
      }
      final List<TrackPoint> pts = p;
      final List<Store.Place> pl = saved;
      final TrackAnalytics.Result result = stats;
      final String trackName = name;
      app.main.post(() -> {
        loadingData = false;
        if (destroyed || sequence != loadSequence || displayTrack != id) return;
        points = pts;
        places = pl;
        historicalStats = result;
        historicalName = trackName;
        if (!live && id >= 0) historicalLoaded = true;
        drawTrack();
        updateSummary();
      });
    });
  }

  private void drawTrack() {
    if (style == null) return;
    List<Feature> lines = new ArrayList<>();
    for (List<TrackPoint> segment : TrackGeometry.segments(points)) {
      List<Point> path = new ArrayList<>();
      for (TrackPoint p : segment) path.add(Point.fromLngLat(p.lon, p.lat));
      if (path.size() >= 2) lines.add(Feature.fromGeometry(LineString.fromLngLats(path)));
    }
    setSource("track", lines);
    List<Feature> markers = new ArrayList<>();
    if (!points.isEmpty()) {
      TrackPoint start = points.get(0), end = points.get(points.size() - 1);
      Feature a = Feature.fromGeometry(Point.fromLngLat(start.lon, start.lat)),
          b = Feature.fromGeometry(Point.fromLngLat(end.lon, end.lat));
      a.addStringProperty("color", "#F5AB35");
      b.addStringProperty("color", "#EB5C75");
      markers.add(a);
      markers.add(b);
    }
    setSource("endpoints", markers);
    List<Feature> favorites = new ArrayList<>();
    for (Store.Place p : places) {
      Feature f = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat));
      f.addStringProperty("name", p.name);
      f.addStringProperty("note", p.note);
      favorites.add(f);
    }
    setSource("places", favorites);
    if (fitPending && !points.isEmpty() && requestedTrack >= 0) {
      fitPending = false;
      fitTrack();
    }
  }

  private void fitTrack() {
    if (map == null || points.isEmpty()) {
      toast("当前没有可显示的有效轨迹点");
      return;
    }
    following = false;
    updateFollowLabel();
    if (points.size() == 1) {
      TrackPoint p = points.get(0);
      map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(p.lat, p.lon), 16));
      return;
    }
    LatLngBounds.Builder bounds = new LatLngBounds.Builder();
    for (TrackPoint p : points) bounds.include(new LatLng(p.lat, p.lon));
    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), ui.dp(36)), 600);
  }

  private void updateControls() {
    TrackService live = TrackService.active;
    String key = live == null ? "none" : live.trackId + ":" + live.paused;
    if (controlKey.equals(key)) {
      updateFollowLabel();
      updateSummary();
      return;
    }
    controlKey = key;
    controls.removeAllViews();
    if (live == null) {
      controls.addView(
          ui.button("开始记录", true, this::startRecord), new LinearLayout.LayoutParams(0, -2, 1));
    } else {
      pauseButton =
          ui.button(
              live.paused ? "继续" : "暂停",
              false,
              () -> sendService(live.paused ? "RESUME" : "PAUSE"));
      controls.addView(pauseButton, new LinearLayout.LayoutParams(0, -2, 1));
      controls.addView(
          ui.button(
              "结束保存",
              true,
              () ->
                  new AlertDialog.Builder(this)
                      .setTitle("结束并保存轨迹？")
                      .setPositiveButton("结束", (d, w) -> sendService("STOP"))
                      .setNegativeButton("取消", null)
                      .show()),
          new LinearLayout.LayoutParams(0, -2, 1));
    }
    controls.addView(
        ui.button("详细统计", false, this::showStatistics), new LinearLayout.LayoutParams(0, -2, 1));
    updateFollowLabel();
    updateSummary();
  }

  private void updateSummary() {
    TrackService active = TrackService.active;
    TrackAnalytics.Result s =
        active != null && active.trackId == displayTrack ? active.insights() : historicalStats;
    if (s == null) {
      summary.setText("橙色起点 · 红色终点 · 紫色收藏 · 蓝色当前位置\n地图加载与轨迹记录相互独立");
      return;
    }
    summary.setText(
        (active != null && active.trackId == displayTrack
                ? (active.paused ? "已暂停" : "正在记录")
                : historicalName)
            + " · "
            + Geo.meters(s.distance)
            + " · "
            + Geo.duration(s.activeMs)
            + "\n平均 "
            + format(s.averageKmh(), "km/h")
            + "    上升 "
            + format(s.ascent, "m")
            + "    下降 "
            + format(s.descent, "m")
            + "（估计）");
  }

  private String format(double n, String unit) {
    return Double.isFinite(n) ? String.format(Locale.US, "%.1f%s", n, unit) : "—";
  }

  private void showStatistics() {
    if (displayTrack < 0) {
      toast("开始记录后可查看路程统计");
      return;
    }
    if (statisticsDialog != null) statisticsDialog.dismiss();
    ScrollView scroll = new ScrollView(this);
    TrackStatsPanel panel = new TrackStatsPanel(this, ui, displayTrack);
    panel.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
    scroll.addView(panel);
    statisticsPanel = panel;
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("本地轨迹统计")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create();
    statisticsDialog = dialog;
    dialog.setOnDismissListener(d -> {
      if (statisticsDialog == dialog) {
        statisticsPanel = null;
        statisticsDialog = null;
      }
    });
    dialog.show();
  }

  private void startRecord() {
    if (!app.engine.precise()) {
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          1);
      toast("授予精确位置后，再点击开始记录");
      return;
    }
    if (!app.engine.gpsEnabled()) {
      startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        && !notificationAsked) {
      notificationAsked = true;
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 2);
      toast("完成通知权限选择后，再点击开始记录");
      return;
    }
    requestedTrack = -1;
    historicalLoaded = false;
    try {
      startForegroundService(new Intent(this, TrackService.class).setAction("START"));
    } catch (RuntimeException e) {
      toast("无法启动记录，请检查定位权限");
    }
  }

  private void sendService(String action) {
    if (TrackService.active != null)
      startService(new Intent(this, TrackService.class).setAction(action));
    handler.postDelayed(
        () -> {
          if (!destroyed) {
            updateControls();
            loadTrack();
          }
        },
        300);
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    app.engine.reconcile();
    updatePosition();
  }

  @Override
  protected void onStart() {
    super.onStart();
    started = true;
    if (mapView != null) mapView.onStart();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    Preferences.applyWindow(this, ui.light);
    app.engine.setCompassActive(false);
    app.engine.observe(fixObserver);
    app.engine.setVisible(true);
    if (mapView != null) mapView.onResume();
    handler.post(tick);
  }

  @Override
  protected void onPause() {
    resumed = false;
    handler.removeCallbacks(tick);
    app.engine.unobserve(fixObserver);
    app.engine.setVisible(false);
    if (mapView != null) mapView.onPause();
    super.onPause();
  }

  @Override
  protected void onStop() {
    started = false;
    if (mapView != null) mapView.onStop();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    if (statisticsDialog != null) statisticsDialog.dismiss();
    loadSequence++;
    handler.removeCallbacksAndMessages(null);
    if (mapView != null) mapView.onDestroy();
    super.onDestroy();
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();
    if (mapView != null) mapView.onLowMemory();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    Bundle state = new Bundle();
    if (mapView != null) mapView.onSaveInstanceState(state);
    out.putBundle("map_state", state);
    out.putBoolean("map_chosen", mapView != null);
    out.putBoolean("following", following);
    out.putLong("requested_track", requestedTrack);
    out.putBoolean("fit_pending", fitPending);
    out.putLong("display_track", displayTrack);
  }

  boolean lightForTests() {
    return ui.light;
  }

  double cameraZoomForTests() {
    return map == null ? 0 : map.getCameraPosition().zoom;
  }

  boolean followingForTests() {
    return following && requestedTrack < 0;
  }

  String statisticsForTests() {
    return statisticsPanel == null ? "" : statisticsPanel.valuesForTests();
  }

  void closeStatisticsForTests() {
    if (statisticsDialog != null) statisticsDialog.dismiss();
  }

  boolean readyForTests() {
    return style != null && style.getSource("track") != null;
  }

  boolean onlineForTests() {
    return online;
  }

  boolean renderedForTests() {
    return rendered && !localFallback;
  }

  int trackPointsForTests() {
    return points.size();
  }

  int trackSegmentsForTests() {
    return TrackGeometry.segments(points).size();
  }
}

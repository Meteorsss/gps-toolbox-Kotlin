package com.meteorsss.startrace

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Insets
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.View.OnApplyWindowInsetsListener
import android.view.WindowInsets
import android.widget.*
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.meteorsss.startrace.Store.Place
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds
import org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener
import org.maplibre.android.maps.MapLibreMap.OnMapClickListener
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapView.OnDidFailLoadingMapListener
import org.maplibre.android.maps.MapView.OnDidFinishLoadingMapListener
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.Style.OnStyleLoaded
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*
import java.util.*
import kotlin.math.max

/** The map is a view of local WGS84 data, never a replacement recorder or location provider.  */
class MapActivity : Activity() {
    private var app: App? = null
    private var ui: Ui? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var holder: FrameLayout? = null
    private var mapStatus: TextView? = null
    private var fixStatus: TextView? = null
    private var summary: TextView? = null
    private var followButton: TextView? = null
    private var pauseButton: TextView? = null
    private var controls: LinearLayout? = null
    private var statisticsPanel: TrackStatsPanel? = null
    private var statisticsDialog: AlertDialog? = null
    private var started = false
    private var resumed = false
    private var destroyed = false
    private var online = false
    private var following = true
    private var loadingData = false
    private var fitPending = true
    private var notificationAsked = false
    private var localFallback = false
    private var rendered = false
    private var controlKey = ""
    private var requestedTrack: Long = -1
    private var displayTrack: Long = -1
    private var loadSequence: Long = 0
    private var lastFixElapsed: Long = 0
    private var mapState: Bundle? = null
    private var points = mutableListOf<TrackPoint>()
    private var places = mutableListOf<Place>()
    private var historicalStats: TrackAnalytics.Result? = null
    private var historicalName: String? = ""
    private var historicalLoaded = false
    private val handler = Handler(Looper.getMainLooper())
    private val fixObserver = GnssEngine.Observer {
        if (resumed) updatePosition()
    }
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!resumed) return
            updatePosition()
            updateControls()
            loadTrack()
            if (statisticsPanel != null) statisticsPanel!!.refresh()
            handler.postDelayed(this, 2500)
        }
    }

    public override fun onCreate(state: Bundle?) {
        setTheme(if (Preferences.light(this)) R.style.AppThemeLight else R.style.AppTheme)
        super.onCreate(state)
        app = getApplication() as App
        ui = Ui(this, Preferences.light(this))
        requestedTrack = getIntent().getLongExtra("track_id", -1)
        displayTrack = requestedTrack
        following = requestedTrack < 0
        mapState = if (state == null) null else state.getBundle("map_state")
        if (state != null) {
            requestedTrack = state.getLong("requested_track", requestedTrack)
            fitPending = state.getBoolean("fit_pending", true)
            following = state.getBoolean("following", true)
            displayTrack = state.getLong("display_track", requestedTrack)
        }
        getWindow().setDecorFitsSystemWindows(false)
        val root = ui!!.column()
        root.setBackgroundColor(ui!!.bg)
        root.setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener { v: View?, i: WindowInsets? ->
            val `in`: Insets? =
                i!!.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v!!.setPadding(`in`!!.left, `in`.top, `in`.right, `in`.bottom)
            i
        })
        val toolbar = ui!!.row()
        toolbar.setPadding(ui!!.dp(12f), ui!!.dp(8f), ui!!.dp(12f), ui!!.dp(8f))
        toolbar.addView(ui!!.button("返回", false, Runnable { this.finish() }))
        val title = ui!!.title(if (requestedTrack >= 0) "轨迹地图" else "地图 · 当前位置", 19)
        title.setGravity(Gravity.CENTER)
        toolbar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(ui!!.button("底图", false, Runnable { this.showMapPolicy() }))
        root.addView(toolbar)
        mapStatus = ui!!.text("地图尚未联网", 12, ui!!.muted)
        mapStatus!!.setPadding(ui!!.dp(14f), ui!!.dp(4f), ui!!.dp(14f), ui!!.dp(4f))
        root.addView(mapStatus)
        fixStatus = ui!!.text("等待定位", 12, ui!!.accent)
        fixStatus!!.setPadding(ui!!.dp(14f), 0, ui!!.dp(14f), ui!!.dp(6f))
        root.addView(fixStatus)
        holder = FrameLayout(this)
        root.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        val attribution = ui!!.row()
        attribution.setPadding(ui!!.dp(10f), ui!!.dp(5f), ui!!.dp(10f), ui!!.dp(5f))
        for (item in arrayOf<Array<String?>?>(
            arrayOf<String?>("MapLibre", "https://maplibre.org/"),
            arrayOf<String?>("OpenFreeMap", "https://openfreemap.org/"),
            arrayOf<String?>("© OpenMapTiles", "https://openmaptiles.org/"),
            arrayOf<String?>("© OSM contributors", "https://www.openstreetmap.org/copyright")
        )) {
            val link = ui!!.text(item!![0], 9, ui!!.muted)
            link.setPadding(ui!!.dp(4f), ui!!.dp(3f), ui!!.dp(4f), ui!!.dp(3f))
            link.setOnClickListener(View.OnClickListener { v: View? -> openLink(item[1]) })
            attribution.addView(link, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(attribution)
        val actions = ui!!.row()
        actions.setPadding(ui!!.dp(10f), 0, ui!!.dp(10f), ui!!.dp(6f))
        followButton = ui!!.button("跟随位置", true, Runnable { this.follow() })
        actions.addView(followButton, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(
            ui!!.button("全程", false, Runnable { this.fitTrack() }), LinearLayout.LayoutParams(0, -2, 1f)
        )
        actions.addView(
            ui!!.button(
                "重试底图",
                false,
                Runnable {
                    if (map != null) {
                        loadStyle()
                    } else showMapPolicy()
                }),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        root.addView(actions)
        summary = ui!!.text("", 13, ui!!.text)
        summary!!.setPadding(ui!!.dp(14f), ui!!.dp(4f), ui!!.dp(14f), ui!!.dp(8f))
        root.addView(summary)
        summary!!.setOnClickListener(View.OnClickListener { v: View? -> showStatistics() })
        controls = ui!!.row()
        controls!!.setPadding(ui!!.dp(10f), 0, ui!!.dp(10f), ui!!.dp(8f))
        root.addView(controls)
        setContentView(root)
        Preferences.applyWindow(this, ui!!.light)
        root.requestApplyInsets()
        getOnBackInvokedDispatcher()
            .registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, OnBackInvokedCallback { this.finish() })
        if (MapPolicy.allowed(this)
            || localTestStyle()
            || (state != null && state.getBoolean("map_chosen", false))
        ) installMap()
        else showMapPolicy()
        updateControls()
    }

    private fun localTestStyle(): Boolean {
        return BuildConfig.DEBUG && getIntent().getBooleanExtra("test_local_style", false)
    }

    private fun openLink(url: String?) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast("没有可打开链接的浏览器")
        }
    }

    fun showMapPolicy() {
        AlertDialog.Builder(this)
            .setTitle("在线地图与隐私")
            .setMessage(MapPolicy.DISCLOSURE)
            .setPositiveButton(
                "允许在线底图",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    MapPolicy.allow(this, true)
                    switchMode()
                })
            .setNeutralButton(
                "仅轨迹，不联网",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    MapPolicy.allow(this, false)
                    switchMode()
                })
            .setNegativeButton(
                "返回",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    if (mapView == null) finish()
                })
            .show()
    }

    private fun switchMode() {
        if (mapView == null) installMap()
        else recreate()
    }

    private fun installMap() {
        if (destroyed || mapView != null) return
        online = MapPolicy.allowed(this) && !localTestStyle()
        try {
            MapLibre.getInstance(getApplicationContext())
            mapView = MapView(this)
            holder!!.addView(mapView, FrameLayout.LayoutParams(-1, -1))
            mapView!!.onCreate(mapState)
            mapView!!.addOnDidFinishLoadingMapListener(OnDidFinishLoadingMapListener { rendered = true })
            mapView!!.addOnDidFailLoadingMapListener(OnDidFailLoadingMapListener { error: String? ->
                if (destroyed) return@addOnDidFailLoadingMapListener
                mapStatus!!.setText("底图加载失败，可重试；定位与记录不受影响")
                if (map != null && style == null) loadLocalFallback()
            })
            if (started) mapView!!.onStart()
            if (resumed) mapView!!.onResume()
            mapView!!.getMapAsync(OnMapReadyCallback { m: MapLibreMap? ->
                if (destroyed) return@getMapAsync
                map = m
                map!!.getUiSettings().setCompassEnabled(true)
                map!!.getUiSettings().setAttributionEnabled(true)
                map!!.addOnCameraMoveStartedListener(OnCameraMoveStartedListener { reason: Int ->
                    if (reason == OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        following = false
                        updateFollowLabel()
                    }
                })
                map!!.addOnMapClickListener(OnMapClickListener { latLng: LatLng? ->
                    if (style == null) return@addOnMapClickListener false
                    val hit: MutableList<Feature>? =
                        map!!.queryRenderedFeatures(
                            map!!.getProjection().toScreenLocation(latLng!!), "places-layer"
                        )
                    if (hit!!.isEmpty()) return@addOnMapClickListener false
                    val f: Feature? = hit.get(0)
                    AlertDialog.Builder(this)
                        .setTitle(f!!.getStringProperty("name"))
                        .setMessage(f.getStringProperty("note"))
                        .setPositiveButton("关闭", null)
                        .show()
                    true
                })
                if (mapState == null) map!!.moveCamera(newLatLngZoom(LatLng(35.0, 105.0), 3.0))
                loadStyle()
            })
        } catch (error: RuntimeException) {
            mapStatus!!.setText("地图引擎启动失败；请返回继续使用记录工具")
        } catch (error: UnsatisfiedLinkError) {
            mapStatus!!.setText("地图引擎启动失败；请返回继续使用记录工具")
        }
    }

    private fun loadStyle() {
        if (map == null || destroyed) return
        localFallback = false
        style = null
        mapStatus!!.setText(if (online) "正在请求 OpenFreeMap 在线底图…" else "仅本地轨迹 · 无道路底图 · 不联网")
        val builder: Style.Builder? =
            if (online)
                Style.Builder().fromUri(MapPolicy.STYLE_URL)
            else
                Style.Builder().fromJson(MapPolicy.LOCAL_STYLE)
        map!!.setStyle(
            builder,
            OnStyleLoaded { s: Style? ->
                if (destroyed) return@setStyle
                style = s
                addOverlays()
                mapStatus!!.setText(if (online) "在线底图已载入 · 瓦片按视野加载" else "仅本地轨迹 · 无道路底图 · 不联网")
                lastFixElapsed = 0
                updatePosition()
                drawTrack()
            })
        handler.removeCallbacks(styleTimeout)
        handler.postDelayed(styleTimeout, 20000)
    }

    private val styleTimeout = Runnable {
        if (!destroyed && style == null && online) {
            mapStatus!!.setText("底图超时，已切换本地轨迹；可重试在线底图")
            loadLocalFallback()
        }
    }

    private fun loadLocalFallback() {
        if (map == null || destroyed || localFallback) return
        localFallback = true
        handler.removeCallbacks(styleTimeout)
        map!!.setStyle(
            Style.Builder().fromJson(MapPolicy.LOCAL_STYLE),
            OnStyleLoaded { s: Style? ->
                if (destroyed) return@setStyle
                style = s
                addOverlays()
                lastFixElapsed = 0
                updatePosition()
                drawTrack()
            })
    }

    private fun addOverlays() {
        for (id in arrayOf<String>("track", "position", "accuracy", "endpoints", "places")) style!!.addSource(
            GeoJsonSource(id, FeatureCollection.fromFeatures(arrayOf<Feature?>()))
        )
        style!!.addLayer(
            FillLayer("accuracy-layer", "accuracy")
                .withProperties(PropertyFactory.fillColor("#56A9FF"), PropertyFactory.fillOpacity(.16f))
        )
        style!!.addLayer(
            LineLayer("track-outline", "track")
                .withProperties(
                    PropertyFactory.lineColor("#14283F"),
                    PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
        )
        style!!.addLayer(
            LineLayer("track-layer", "track")
                .withProperties(
                    PropertyFactory.lineColor("#00BAAA"),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
        )
        style!!.addLayer(
            CircleLayer("places-layer", "places")
                .withProperties(
                    PropertyFactory.circleColor("#AD72E8"),
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor("#FFFFFF")
                )
        )
        style!!.addLayer(
            CircleLayer("endpoints-layer", "endpoints")
                .withProperties(
                    PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor("#FFFFFF")
                )
        )
        style!!.addLayer(
            CircleLayer("position-layer", "position")
                .withProperties(
                    PropertyFactory.circleColor("#168FFF"),
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor("#FFFFFF")
                )
        )
    }

    private fun setSource(id: String, features: MutableList<Feature?>?) {
        if (style == null) return
        val source = style!!.getSourceAs<GeoJsonSource?>(id)
        if (source != null) source.setGeoJson(FeatureCollection.fromFeatures(features!!))
    }

    private fun updatePosition() {
        if (destroyed) return
        val fresh = app!!.engine.fresh()
        val l = app!!.engine.location
        fixStatus!!.setText(
            if (fresh)
                (if (l.isMock())
                    "模拟位置 · 不计入有效轨迹"
                else
                    ("实时 WGS84 位置 · "
                            + (if (l.hasAccuracy()) String.format(Locale.US, "精度估计 %.0fm", l.getAccuracy()) else
                        "精度未知")))
            else
                "暂无新鲜位置 · 不显示过期定位点"
        )
        if (style == null) return
        if (!fresh) {
            setSource("position", mutableListOf<Feature?>())
            setSource("accuracy", mutableListOf<Feature?>())
            return
        }
        setSource(
            "position",
            mutableListOf<Feature?>(
                Feature.fromGeometry(Point.fromLngLat(l.getLongitude(), l.getLatitude()))
            )
        )
        val ring =
            if (l.hasAccuracy())
                TrackGeometry.accuracyRing(l.getLatitude(), l.getLongitude(), l.getAccuracy().toDouble())
            else mutableListOf<DoubleArray>()
        val coordinates: MutableList<Point?> = ArrayList<Point?>()
        for (p in ring!!) coordinates.add(Point.fromLngLat(p[1], p[0]))
        setSource(
            "accuracy",
            if (coordinates.isEmpty()) mutableListOf<Feature?>() else mutableListOf<Feature?>(
                Feature.fromGeometry(Polygon.fromLngLats(mutableListOf<MutableList<Point?>?>(coordinates)))
            )
        )
        if (following && requestedTrack < 0 && l.getElapsedRealtimeNanos() != lastFixElapsed) {
            lastFixElapsed = l.getElapsedRealtimeNanos()
            map!!.animateCamera(
                newLatLngZoom(
                    LatLng(l.getLatitude(), l.getLongitude()),
                    max(15.0, map!!.getCameraPosition().zoom)
                ),
                500
            )
        }
    }

    private fun follow() {
        following = true
        requestedTrack = -1
        lastFixElapsed = 0
        updateFollowLabel()
        if (!app!!.engine.precise()) {
            requestPermissions(
                arrayOf<String?>(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1
            )
        } else if (!app!!.engine.gpsEnabled()) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        updatePosition()
        loadTrack()
    }

    private fun updateFollowLabel() {
        followButton!!.setText(if (following) "跟随中" else "回到当前位置")
    }

    private fun loadTrack() {
        if (loadingData || destroyed) return
        val active: TrackService? = TrackService.Companion.active
        if (requestedTrack < 0 && active != null) {
            if (displayTrack != active.trackId) {
                displayTrack = active.trackId
                fitPending = true
                historicalLoaded = false
            }
            historicalStats = null
        }
        val id = displayTrack
        val live = active != null && active.trackId == id
        if (id >= 0 && !live && historicalLoaded) return
        loadingData = true
        val sequence = ++loadSequence
        app!!.io.execute(Runnable {
            var p = mutableListOf<TrackPoint>()
            var saved = mutableListOf<Place>()
            var stats: TrackAnalytics.Result? = null
            var name: String? = ""
            try {
                saved = app!!.store.places()
                if (id >= 0) {
                    p = app!!.store.preview(id, 5000)
                    val t = app!!.store.track(id)
                    if (t != null) {
                        name = t.name
                        if (!live) stats = app!!.store.analyze(id, t.duration, t.distance)
                    }
                }
            } catch (ignored: RuntimeException) {
            }
            val pts = p
            val pl = saved
            val result = stats
            val trackName = name
            app!!.main.post(Runnable {
                loadingData = false
                if (destroyed || sequence != loadSequence || displayTrack != id) return@post
                points = pts
                places = pl
                historicalStats = result
                historicalName = trackName
                if (!live && id >= 0) historicalLoaded = true
                drawTrack()
                updateSummary()
            })
        })
    }

    private fun drawTrack() {
        if (style == null) return
        val lines: MutableList<Feature?>? = ArrayList<Feature?>()
        for (segment in TrackGeometry.segments(points)) {
            val path: MutableList<Point?> = ArrayList<Point?>()
            for (p in segment) path.add(Point.fromLngLat(p.lon, p.lat))
            if (path.size >= 2) lines!!.add(Feature.fromGeometry(LineString.fromLngLats(path)))
        }
        setSource("track", lines)
        val markers: MutableList<Feature?> = ArrayList<Feature?>()
        if (!points.isEmpty()) {
            val start = points.get(0)
            val end = points.get(points.size - 1)
            val a = Feature.fromGeometry(Point.fromLngLat(start.lon, start.lat))
            val b = Feature.fromGeometry(Point.fromLngLat(end.lon, end.lat))
            a!!.addStringProperty("color", "#F5AB35")
            b!!.addStringProperty("color", "#EB5C75")
            markers.add(a)
            markers.add(b)
        }
        setSource("endpoints", markers)
        val favorites: MutableList<Feature?> = ArrayList<Feature?>()
        for (p in places) {
            val f = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))
            f!!.addStringProperty("name", p.name)
            f.addStringProperty("note", p.note)
            favorites.add(f)
        }
        setSource("places", favorites)
        if (fitPending && !points.isEmpty() && requestedTrack >= 0) {
            fitPending = false
            fitTrack()
        }
    }

    private fun fitTrack() {
        if (map == null || points.isEmpty()) {
            toast("当前没有可显示的有效轨迹点")
            return
        }
        following = false
        updateFollowLabel()
        if (points.size == 1) {
            val p = points.get(0)
            map!!.moveCamera(newLatLngZoom(LatLng(p.lat, p.lon), 16.0))
            return
        }
        val bounds = LatLngBounds.Builder()
        for (p in points) bounds.include(LatLng(p.lat, p.lon))
        map!!.animateCamera(newLatLngBounds(bounds.build(), ui!!.dp(36f)), 600)
    }

    private fun updateControls() {
        val live: TrackService? = TrackService.Companion.active
        val key = if (live == null) "none" else live.trackId.toString() + ":" + live.paused
        if (controlKey == key) {
            updateFollowLabel()
            updateSummary()
            return
        }
        controlKey = key
        controls!!.removeAllViews()
        if (live == null) {
            controls!!.addView(
                ui!!.button("开始记录", true, Runnable { this.startRecord() }), LinearLayout.LayoutParams(0, -2, 1f)
            )
        } else {
            pauseButton =
                ui!!.button(
                    if (live.paused) "继续" else "暂停",
                    false,
                    Runnable { sendService(if (live.paused) "RESUME" else "PAUSE") })
            controls!!.addView(pauseButton, LinearLayout.LayoutParams(0, -2, 1f))
            controls!!.addView(
                ui!!.button(
                    "结束保存",
                    true,
                    Runnable {
                        AlertDialog.Builder(this)
                            .setTitle("结束并保存轨迹？")
                            .setPositiveButton(
                                "结束",
                                DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> sendService("STOP") })
                            .setNegativeButton("取消", null)
                            .show()
                    }),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
        }
        controls!!.addView(
            ui!!.button("详细统计", false, Runnable { this.showStatistics() }), LinearLayout.LayoutParams(0, -2, 1f)
        )
        updateFollowLabel()
        updateSummary()
    }

    private fun updateSummary() {
        val active: TrackService? = TrackService.Companion.active
        val s =
            if (active != null && active.trackId == displayTrack) active.insights() else historicalStats
        if (s == null) {
            summary!!.setText("橙色起点 · 红色终点 · 紫色收藏 · 蓝色当前位置\n地图加载与轨迹记录相互独立")
            return
        }
        summary!!.setText(
            ((if (active != null && active.trackId == displayTrack)
                (if (active.paused) "已暂停" else "正在记录")
            else
                historicalName)
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
                    + "（估计）")
        )
    }

    private fun format(n: Double, unit: String): String? {
        return if (java.lang.Double.isFinite(n)) String.format(Locale.US, "%.1f%s", n, unit) else "—"
    }

    private fun showStatistics() {
        if (displayTrack < 0) {
            toast("开始记录后可查看路程统计")
            return
        }
        if (statisticsDialog != null) statisticsDialog!!.dismiss()
        val scroll: ScrollView? = ScrollView(this)
        val panel = TrackStatsPanel(this, ui, displayTrack)
        panel.setPadding(ui!!.dp(20f), ui!!.dp(16f), ui!!.dp(20f), ui!!.dp(16f))
        scroll!!.addView(panel)
        statisticsPanel = panel
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("本地轨迹统计")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .create()
        statisticsDialog = dialog
        dialog!!.setOnDismissListener(DialogInterface.OnDismissListener { d: DialogInterface? ->
            if (statisticsDialog === dialog) {
                statisticsPanel = null
                statisticsDialog = null
            }
        })
        dialog.show()
    }

    private fun startRecord() {
        if (!app!!.engine.precise()) {
            requestPermissions(
                arrayOf<String>(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1
            )
            toast("授予精确位置后，再点击开始记录")
            return
        }
        if (!app!!.engine.gpsEnabled()) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) && !notificationAsked
        ) {
            notificationAsked = true
            requestPermissions(arrayOf<String>(Manifest.permission.POST_NOTIFICATIONS), 2)
            toast("完成通知权限选择后，再点击开始记录")
            return
        }
        requestedTrack = -1
        historicalLoaded = false
        try {
            startForegroundService(Intent(this, TrackService::class.java).setAction("START"))
        } catch (e: RuntimeException) {
            toast("无法启动记录，请检查定位权限")
        }
    }

    private fun sendService(action: String) {
        if (TrackService.Companion.active != null) startService(
            Intent(
                this,
                TrackService::class.java
            ).setAction(action)
        )
        handler.postDelayed(
            Runnable {
                if (!destroyed) {
                    updateControls()
                    loadTrack()
                }
            },
            300
        )
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String?>?, results: IntArray?) {
        super.onRequestPermissionsResult(request, permissions!!, results!!)
        app!!.engine.reconcile()
        updatePosition()
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (mapView != null) mapView!!.onStart()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Preferences.applyWindow(this, ui!!.light)
        app!!.engine.setCompassActive(false)
        app!!.engine.observe(fixObserver)
        app!!.engine.setVisible(true)
        if (mapView != null) mapView!!.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
        app!!.engine.unobserve(fixObserver)
        app!!.engine.setVisible(false)
        if (mapView != null) mapView!!.onPause()
        super.onPause()
    }

    override fun onStop() {
        started = false
        if (mapView != null) mapView!!.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        if (statisticsDialog != null) statisticsDialog!!.dismiss()
        loadSequence++
        handler.removeCallbacksAndMessages(null)
        if (mapView != null) mapView!!.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (mapView != null) mapView!!.onLowMemory()
    }

    override fun onSaveInstanceState(out: Bundle?) {
        super.onSaveInstanceState(out!!)
        val state: Bundle? = Bundle()
        if (mapView != null) mapView!!.onSaveInstanceState(state!!)
        out.putBundle("map_state", state)
        out.putBoolean("map_chosen", mapView != null)
        out.putBoolean("following", following)
        out.putLong("requested_track", requestedTrack)
        out.putBoolean("fit_pending", fitPending)
        out.putLong("display_track", displayTrack)
    }

    fun lightForTests(): Boolean {
        return ui!!.light
    }

    fun cameraZoomForTests(): Double {
        return if (map == null) 0.0 else map!!.getCameraPosition().zoom
    }

    fun followingForTests(): Boolean {
        return following && requestedTrack < 0
    }

    fun statisticsForTests(): String? {
        return if (statisticsPanel == null) "" else statisticsPanel!!.valuesForTests()
    }

    fun closeStatisticsForTests() {
        if (statisticsDialog != null) statisticsDialog!!.dismiss()
    }

    fun readyForTests(): Boolean {
        return style != null && style!!.getSource("track") != null
    }

    fun onlineForTests(): Boolean {
        return online
    }

    fun renderedForTests(): Boolean {
        return rendered && !localFallback
    }

    fun trackPointsForTests(): Int {
        return points.size
    }

    fun trackSegmentsForTests(): Int {
        return TrackGeometry.segments(points).size
    }
}

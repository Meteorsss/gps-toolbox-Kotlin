package com.meteorsss.startrace

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Insets
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
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
    private lateinit var app: App
    private lateinit var ui: Ui
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var holder: FrameLayout? = null
    private var mapStatus: TextView? = null
    private var fixStatus: TextView? = null
    private var headingStatus: TextView? = null
    private var attribution: LinearLayout? = null
    private var basemap = MapBasemap.OSM_STANDARD
    private var styleSequence = 0L
    private val headingSmoother = HeadingSmoother()
    private var headingVisible = false
    private var headingAngle = Double.NaN
    private var headingFrames = 0L
    private var appliedHeading = Double.NaN
    private var appliedHeadingVisible = false
    private val sourceContents = mutableMapOf<String, String>()
    private val headingTick = object : Runnable {
        override fun run() {
            if (!resumed) return
            updateHeading()
            handler.postDelayed(this, 50) // 20Hz, only while this map is resumed.
        }
    }
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
        basemap = MapPolicy.selected(this)
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
        headingStatus = ui.text("手机朝向待获取", 11, ui.muted)
        headingStatus!!.setPadding(ui.dp(14f), 0, ui.dp(14f), ui.dp(4f))
        root.addView(headingStatus)
        holder = FrameLayout(this)
        root.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        attribution = ui.row()
        attribution!!.setPadding(ui.dp(10f), ui.dp(5f), ui.dp(10f), ui.dp(5f))
        root.addView(attribution)
        updateAttribution()
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
        var choice = basemap.ordinal
        // A separate view keeps the privacy disclosure visible alongside radio choices.
        val content = ui.column()
        content.setPadding(ui.dp(16f), 0, ui.dp(16f), 0)
        content.addView(ui.text(MapPolicy.DISCLOSURE, 13, ui.text))
        val radios = RadioGroup(this)
        MapBasemap.entries.forEachIndexed { index, item ->
            radios.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = item.label
                isChecked = index == choice
                setOnClickListener { choice = index }
            })
        }
        content.addView(radios)
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("在线地图与隐私")
            .setView(scroll)
            .setPositiveButton("允许在线底图") { _, _ ->
                MapPolicy.select(this, MapBasemap.entries[choice])
                MapPolicy.allow(this, true)
                switchMode()
            }
            .setNeutralButton("仅轨迹，不联网") { _, _ ->
                MapPolicy.allow(this, false)
                switchMode()
            }
            .setNegativeButton("返回") { _, _ -> if (mapView == null) finish() }
            .show()
    }

    private fun updateAttribution() {
        val row = attribution ?: return
        row.removeAllViews()
        val links = mutableListOf("MapLibre" to "https://maplibre.org/")
        if (basemap == MapBasemap.OPEN_FREE_MAP && MapPolicy.allowed(this)) {
            links += "OpenFreeMap" to "https://openfreemap.org/"
            links += "© OpenMapTiles" to "https://openmaptiles.org/"
        }
        links += "© OpenStreetMap contributors" to "https://www.openstreetmap.org/copyright"
        links.forEach { (label, url) ->
            row.addView(ui.text(label, 10, ui.muted).apply {
                setPadding(ui.dp(4f), ui.dp(3f), ui.dp(4f), ui.dp(3f))
                setOnClickListener { openLink(url) }
            }, LinearLayout.LayoutParams(0, -2, if (label.startsWith("© OpenStreetMap")) 2f else 1f))
        }
    }

    private fun switchMode() {
        basemap = MapPolicy.selected(this)
        online = MapPolicy.allowed(this) && !localTestStyle()
        MapNetwork.cancelPending()
        updateAttribution()
        if (mapView == null) installMap() else loadStyle()
        // Do not recreate Activity, touch GNSS/recorder, reset following or refit track.
    }

    private fun installMap() {
        if (destroyed || mapView != null) return
        online = MapPolicy.allowed(this) && !localTestStyle()
        try {
            MapLibre.getInstance(getApplicationContext())
            MapNetwork.install(this)
            mapView = MapView(this)
            holder!!.addView(mapView, FrameLayout.LayoutParams(-1, -1))
            mapView!!.onCreate(mapState)
            mapView!!.addOnDidFinishRenderingFrameListener(MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
                if (!destroyed && style != null && fully) {
                    rendered = true
                    if (online && !localFallback) mapStatus?.text = "${basemap.label} · 底图已载入"
                }
            })
            mapView!!.addOnDidFailLoadingMapListener(OnDidFailLoadingMapListener { error: String? ->
                if (destroyed) return@OnDidFailLoadingMapListener
                mapStatus!!.setText("底图加载失败，可重试；定位与记录不受影响")
                if (map != null && style == null) loadLocalFallback()
            })
            if (started) mapView!!.onStart()
            if (resumed) mapView!!.onResume()
            mapView!!.getMapAsync(OnMapReadyCallback { m: MapLibreMap? ->
                if (destroyed) return@OnMapReadyCallback
                map = m
                map!!.setPrefetchZoomDelta(0)
                map!!.getUiSettings().setCompassEnabled(true)
                map!!.getUiSettings().setAttributionEnabled(true)
                map!!.addOnCameraMoveStartedListener(OnCameraMoveStartedListener { reason: Int ->
                    rendered = false
                    if (reason == OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        following = false
                        updateFollowLabel()
                    }
                })
                map!!.addOnMapClickListener(OnMapClickListener { latLng: LatLng? ->
                    if (style == null) return@OnMapClickListener false
                    val hit: MutableList<Feature>? =
                        map!!.queryRenderedFeatures(
                            map!!.getProjection().toScreenLocation(latLng!!), "places-layer"
                        )
                    if (hit!!.isEmpty()) return@OnMapClickListener false
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
        val generation = ++styleSequence
        localFallback = false
        rendered = false
        style = null
        mapStatus!!.text = if (online) "正在请求 ${basemap.label} 在线底图…" else "仅本地轨迹 · 无道路底图 · 不联网"
        val builder = when {
            !online -> Style.Builder().fromJson(MapPolicy.LOCAL_STYLE)
            basemap == MapBasemap.OSM_STANDARD -> Style.Builder().fromJson(MapBasemap.OSM_STYLE)
            else -> Style.Builder().fromUri(MapPolicy.STYLE_URL)
        }
        map!!.setStyle(
            builder,
            OnStyleLoaded { s: Style? ->
                if (destroyed || generation != styleSequence) return@OnStyleLoaded
                style = s
                addOverlays()
                mapStatus!!.text = if (online) "${basemap.label} · 样式就绪，瓦片按视野加载" else "仅本地轨迹 · 无道路底图 · 不联网"
                updatePosition(moveCamera = false)
                updateHeading()
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
        val generation = ++styleSequence
        handler.removeCallbacks(styleTimeout)
        map!!.setStyle(
            Style.Builder().fromJson(MapPolicy.LOCAL_STYLE),
            OnStyleLoaded { s: Style? ->
                if (destroyed || generation != styleSequence) return@OnStyleLoaded
                style = s
                addOverlays()
                updatePosition(moveCamera = false)
                updateHeading()
                drawTrack()
            })
    }

    private fun addOverlays() {
        sourceContents.clear()
        appliedHeading = Double.NaN
        appliedHeadingVisible = false
        for (id in arrayOf<String>("track", "position", "accuracy", "endpoints", "places")) style!!.addSource(
            GeoJsonSource(id, FeatureCollection.fromFeatures(arrayOf<Feature>()))
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
        addHeadingLayer()
    }

    /** Icon points toward the phone screen's top edge, in true-north map coordinates. */
    private fun addHeadingLayer() {
        val size = ui.dp(64f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.density = resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)
        canvas.scale(size / 64f, size / 64f)
        val path = Path().apply {
            moveTo(32f, 3f); lineTo(22f, 23f); lineTo(32f, 19f); lineTo(42f, 23f); close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL; paint.color = android.graphics.Color.rgb(22, 143, 255)
        canvas.drawPath(path, paint)
        style!!.addImage("phone-heading-icon", bitmap)
        style!!.addLayer(SymbolLayer("phone-heading-layer", "position").withProperties(
            PropertyFactory.iconImage("phone-heading-icon"),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.visibility(Property.NONE)
        ))
    }

    private fun updateHeading() {
        val engine = app.engine
        val age = if (engine.orientationAt == 0L) -1L else SystemClock.elapsedRealtime() - engine.orientationAt
        val heading = MapHeading.usable(engine.hasCompass, engine.compassSubscribed(), engine.fresh(),
            age, engine.sensorAccuracy, engine.trueHeading().toDouble())
        val message = when {
            !engine.hasCompass -> "手机不支持方向传感器 · 仅显示位置"
            !engine.fresh() -> "等待新鲜位置以校正真北 · 箭头暂隐藏"
            age !in 0..MapHeading.MAX_SAMPLE_AGE_MS -> "手机朝向待获取 · 箭头暂隐藏"
            !heading.isFinite() -> "朝向精度不足 · 请远离磁性物体并校准手机"
            else -> "箭头 = 手机屏幕上方朝向 · 真北参考（非行进方向）"
        }
        if (headingStatus?.text != message) headingStatus?.text = message
        renderHeading(heading, SystemClock.elapsedRealtimeNanos())
        headingFrames++
    }

    private fun renderHeading(heading: Double, nowNanos: Long) {
        headingAngle = headingSmoother.update(heading, nowNanos)
        headingVisible = headingAngle.isFinite()
        val layer = style?.getLayerAs<SymbolLayer>("phone-heading-layer") ?: return
        if (headingVisible != appliedHeadingVisible) {
            layer.setProperties(PropertyFactory.visibility(if (headingVisible) Property.VISIBLE else Property.NONE))
            appliedHeadingVisible = headingVisible
        }
        // Layout changes invalidate symbol placement. Avoid redoing identical/subpixel work.
        if (headingVisible && (!appliedHeading.isFinite() || kotlin.math.abs(HeadingSmoother.delta(appliedHeading, headingAngle)) >= .3)) {
            layer.setProperties(PropertyFactory.iconRotate(headingAngle.toFloat()))
            appliedHeading = headingAngle
        }
    }

    private fun setSource(id: String, features: MutableList<Feature>?) {
        if (style == null) return
        val source = style!!.getSourceAs<GeoJsonSource?>(id) ?: return
        val collection = FeatureCollection.fromFeatures(features!!)
        val json = collection.toJson()
        if (sourceContents[id] == json) return
        sourceContents[id] = json
        source.setGeoJson(collection)
    }

    private fun updatePosition(moveCamera: Boolean = true) {
        if (destroyed) return
        val fresh = app!!.engine.fresh()
        val l = app!!.engine.location
        fixStatus!!.setText(
            if (fresh)
                (if (l!!.isMock())
                    "模拟位置 · 不计入有效轨迹"
                else
                    ("实时 WGS84 位置 · "
                            + (if (l!!.hasAccuracy()) String.format(Locale.US, "精度估计 %.0fm", l!!.getAccuracy()) else
                        "精度未知")))
            else
                "暂无新鲜位置 · 不显示过期定位点"
        )
        if (style == null) return
        if (!fresh) {
            setSource("position", mutableListOf<Feature>())
            setSource("accuracy", mutableListOf<Feature>())
            renderHeading(Double.NaN, SystemClock.elapsedRealtimeNanos())
            return
        }
        setSource(
            "position",
            mutableListOf<Feature>(
                Feature.fromGeometry(Point.fromLngLat(l!!.getLongitude(), l!!.getLatitude()))
            )
        )
        val ring =
            if (l!!.hasAccuracy())
                TrackGeometry.accuracyRing(l!!.getLatitude(), l!!.getLongitude(), l!!.getAccuracy().toDouble())
            else mutableListOf<DoubleArray>()
        val coordinates: MutableList<Point?> = ArrayList<Point?>()
        for (p in ring!!) coordinates.add(Point.fromLngLat(p[1], p[0]))
        setSource(
            "accuracy",
            if (coordinates.isEmpty()) mutableListOf<Feature>() else mutableListOf<Feature>(
                Feature.fromGeometry(Polygon.fromLngLats(mutableListOf<MutableList<Point?>?>(coordinates)))
            )
        )
        if (moveCamera && following && requestedTrack < 0 && l!!.getElapsedRealtimeNanos() != lastFixElapsed) {
            lastFixElapsed = l!!.getElapsedRealtimeNanos()
            map!!.animateCamera(
                newLatLngZoom(
                    LatLng(l!!.getLatitude(), l!!.getLongitude()),
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
                arrayOf<String>(
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
                if (destroyed || sequence != loadSequence || displayTrack != id) return@Runnable
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
        val lines: MutableList<Feature> = ArrayList<Feature>()
        for (segment in TrackGeometry.segments(points)) {
            val path: MutableList<Point?> = ArrayList<Point?>()
            for (p in segment) path.add(Point.fromLngLat(p.lon, p.lat))
            if (path.size >= 2) lines!!.add(Feature.fromGeometry(LineString.fromLngLats(path)))
        }
        setSource("track", lines)
        val markers: MutableList<Feature> = ArrayList<Feature>()
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
        val favorites: MutableList<Feature> = ArrayList<Feature>()
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

    private fun format(n: Double, unit: String): String {
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

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions!!, results!!)
        if (BackgroundLocation.onPermissionResult(this, request)) return
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
        app.engine.setCompassActive(true)
        app!!.engine.observe(fixObserver)
        app.engine.reconcile()
        BackgroundLocation.resumeIfEnabled(this)
        if (mapView != null) mapView!!.onResume()
        handler.post(tick)
        handler.post(headingTick)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(headingTick)
        app.engine.setCompassActive(false)
        headingSmoother.reset()
        app!!.engine.unobserve(fixObserver)
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
        styleSequence++
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

    override fun onSaveInstanceState(out: Bundle) {
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

    fun selectBasemapForTests(value: MapBasemap) {
        check(BuildConfig.DEBUG)
        MapPolicy.select(this, value)
        switchMode()
    }

    fun revokeOnlineForTests() {
        check(BuildConfig.DEBUG)
        MapPolicy.allow(this, false)
        switchMode()
    }

    fun basemapForTests() = basemap
    fun headingFramesForTests() = headingFrames
    fun headingVisibleForTests() = headingVisible
    fun headingAngleForTests() = style?.getLayerAs<SymbolLayer>("phone-heading-layer")?.iconRotate?.value
    fun headingAlignmentForTests() = style?.getLayerAs<SymbolLayer>("phone-heading-layer")?.iconRotationAlignment?.value
    fun cameraForTests() = map!!.cameraPosition
    fun prefetchForTests() = map!!.prefetchZoomDelta
    fun attributionForTests() = (0 until (attribution?.childCount ?: 0)).joinToString(" ") {
        (attribution!!.getChildAt(it) as TextView).text
    }
    fun positionCountForTests() = style?.getSourceAs<GeoJsonSource>("position")?.querySourceFeatures(null)?.size ?: 0
    fun injectHeadingForTests(value: Double) {
        check(BuildConfig.DEBUG)
        handler.removeCallbacks(headingTick)
        headingSmoother.reset()
        renderHeading(value, SystemClock.elapsedRealtimeNanos())
        headingStatus?.text = if (value.isFinite()) "测试合成手机朝向 · 真北参考（非行进方向）" else "测试朝向不可用"
    }
    fun renderedFeaturesForTests(layer: String): Int = map!!.queryRenderedFeatures(
        android.graphics.RectF(0f, 0f, mapView!!.width.toFloat(), mapView!!.height.toFloat()), layer).size
    fun mapStateForTests() = "online=$online fallback=$localFallback rendered=$rendered style=${style != null} " +
        "points=${points.size} status=${mapStatus?.text} heading=$headingVisible position=${sourceContents["position"]}"
    fun moveCameraForTests(position: org.maplibre.android.camera.CameraPosition) {
        check(BuildConfig.DEBUG)
        following = false
        map!!.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(position))
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

    fun statisticsForTests(): String {
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

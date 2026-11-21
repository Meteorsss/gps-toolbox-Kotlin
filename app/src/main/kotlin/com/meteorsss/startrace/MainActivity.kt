package com.meteorsss.startrace

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.DialogInterface.OnShowListener
import android.content.pm.PackageManager
import android.graphics.Insets
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.text.InputType
import android.view.View
import android.view.View.OnApplyWindowInsetsListener
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.meteorsss.startrace.Store.Place
import com.meteorsss.startrace.Store.Track
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.IntFunction
import java.util.function.Supplier
import kotlin.math.max

class MainActivity : Activity() {
    var app: App? = null
    var e: GnssEngine? = null
    var u: Ui? = null
    var body: LinearLayout? = null
    var page: Int = 0
    var mode: String = ""
    var selectedTrack: Long = -1
    var destination: Place? = null
    private var root: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var status: TextView? = null
    private val updates: MutableList<Runnable>? = ArrayList<Runnable>()
    private val compassViews: MutableList<CompassView> = ArrayList<CompassView>()
    private var resumed = false
    private var rendering = false
    private var pendingRecord = false
    private var serviceKey = ""
    private var exportId: Long = -1
    private var exportFormat = TrackExport.GPX
    private var speedSession: SpeedSession? = null
    private val observer = GnssEngine.Observer {
        if (resumed && !rendering) refresh()
    }

    public override fun onCreate(saved: Bundle?) {
        setTheme(if (Preferences.light(this)) R.style.AppThemeLight else R.style.AppTheme)
        super.onCreate(saved)
        app = getApplication() as App
        e = app!!.engine
        if (saved != null) {
            page = saved.getInt("page")
            mode = saved.getString("mode", "")
            selectedTrack = saved.getLong("track", -1)
            exportId = saved.getLong("export", -1)
            exportFormat = TrackExport.Companion.restore(saved.getString("export_format"), saved.getBoolean("raw"))
            pendingRecord = saved.getBoolean("pendingRecord")
            if (mode == "guide") mode = "places"
        }
        val retained = getLastNonConfigurationInstance()
        if (mode == "speed" && retained is SpeedSession) speedSession = retained as SpeedSession?
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher()
            .registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, OnBackInvokedCallback { this.back() })
        buildRoot()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("page", page)
        out.putString("mode", mode)
        out.putLong("track", selectedTrack)
        out.putLong("export", exportId)
        out.putString("export_format", exportFormat.name)
        out.putBoolean("pendingRecord", pendingRecord)
    }

    fun buildRoot() {
        val light = Preferences.light(this)
        setTheme(if (light) R.style.AppThemeLight else R.style.AppTheme)
        u = Ui(this, light)
        getWindow().setDecorFitsSystemWindows(false)
        getWindow().setNavigationBarContrastEnforced(false)
        root = u!!.column()
        root!!.setBackgroundColor(u!!.bg)
        root!!.setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener { v: View?, insets: WindowInsets? ->
            val i: Insets? =
                insets!!.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v!!.setPadding(i!!.left, i.top, i.right, i.bottom)
            insets
        })
        val top = u!!.row()
        top.setPadding(u!!.dp(20f), u!!.dp(12f), u!!.dp(20f), u!!.dp(12f))
        val brand = u!!.column()
        val over = u!!.text("STARTRACE / PERSONAL GPS", 9, u!!.accent)
        over.setLetterSpacing(.14f)
        brand.addView(over)
        u!!.gap(brand, 5)
        brand.addView(u!!.title("星迹", 26))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = u!!.button("设置", false, Runnable { navigate("settings") })
        top.addView(settings, LinearLayout.LayoutParams(u!!.dp(64f), u!!.dp(48f)))
        root!!.addView(top)
        status = u!!.text("检查定位状态…", 12, u!!.muted)
        status!!.setPadding(u!!.dp(22f), 0, u!!.dp(22f), u!!.dp(12f))
        root!!.addView(status)
        status!!.setOnClickListener(View.OnClickListener { v: View? -> askLocation() })
        scroll = ScrollView(this)
        scroll!!.setFillViewport(true)
        scroll!!.setClipToPadding(false)
        body = u!!.column()
        body!!.setPadding(u!!.dp(18f), u!!.dp(8f), u!!.dp(18f), u!!.dp(18f))
        scroll!!.addView(body, FrameLayout.LayoutParams(-1, -2))
        root!!.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = u!!.row()
        nav.setPadding(u!!.dp(10f), u!!.dp(8f), u!!.dp(10f), u!!.dp(8f))
        nav.setBackgroundColor(u!!.card)
        val names: Array<String?>? = arrayOf<String?>("◉  仪表", "✦  卫星", "⌁  记录", "⊞  工具")
        for (i in 0..3) {
            val n = i
            val item = u!!.button(names!![i], i == page, Runnable { goPage(n) })
            val lp = LinearLayout.LayoutParams(0, u!!.dp(48f), 1f)
            lp.leftMargin = u!!.dp(3f)
            lp.rightMargin = u!!.dp(3f)
            nav.addView(item, lp)
        }
        root!!.addView(nav)
        setContentView(root)
        root!!.requestApplyInsets()
        Preferences.applyWindow(this, light)
        render()
    }

    fun goPage(index: Int) {
        page = index
        mode = ""
        clearKeepAwake()
        buildRoot()
    }

    fun navigate(value: String) {
        mode = value
        clearKeepAwake()
        render()
        scroll!!.scrollTo(0, 0)
    }

    private fun back() {
        if (!mode.isEmpty()) {
            navigate("")
        } else if (page != 0) {
            goPage(0)
        } else {
            finish()
        }
    }

    fun render() {
        if (mode != "speed") speedSession = null
        else if (speedSession == null) speedSession = SpeedSession(SystemClock.elapsedRealtime())
        rendering = true
        for (view in compassViews) view.setActive(false)
        compassViews.clear()
        e!!.setCompassActive(mode == "compass" || mode == "guide")
        updates!!.clear()
        body!!.removeAllViews()
        if (!mode.isEmpty()) {
            body!!.addView(u!!.button("‹  返回", false, Runnable { navigate(if (mode == "guide") "places" else "") }))
            u!!.gap(body, 16)
        }
        Screens.render(this)
        rendering = false
        refresh()
    }

    fun registerCompass(view: CompassView) {
        compassViews.add(view)
        view.setActive(resumed)
        watch(Runnable { view.updateSlowState() })
    }

    fun watch(update: Runnable) {
        updates!!.add(update)
        update.run()
    }

    fun bind(target: TextView, value: Supplier<String?>) {
        watch(Runnable {
            val s = value.get()
            if (target.getText().toString() != s) target.setText(s)
        })
    }

    private fun key(): String {
        val t: TrackService? = TrackService.Companion.active
        return if (t == null) "none" else t.trackId.toString() + ":" + t.paused
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (serviceKey != key()) {
                serviceKey = key()
                render()
            } else refresh()
            app!!.main.postDelayed(this, 500)
        }
    }

    private fun updateSpeedSession() {
        if (!resumed || speedSession == null) return
        if (!e!!.fresh()) {
            speedSession!!.disconnect()
            return
        }
        val l = e!!.location
        speedSession!!.add(
            TrackPoint(
                l.getLatitude(),
                l.getLongitude(),
                Double.NaN,
                if (l.hasAccuracy()) l.getAccuracy().toDouble() else Double.NaN,
                if (l.hasSpeed()) l.getSpeed().toDouble() else Double.NaN,
                l.getTime(),
                l.getElapsedRealtimeNanos() / 1000000L,
                0,
                true,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                if (l.hasSpeedAccuracy()) l.getSpeedAccuracyMetersPerSecond().toDouble() else Double.NaN
            ),
            l.isMock(),
            SystemClock.elapsedRealtime()
        )
    }

    fun speedStats(): SpeedSession.Snapshot? {
        return if (speedSession == null) null else speedSession!!.snapshot(SystemClock.elapsedRealtime())
    }

    override fun onRetainNonConfigurationInstance(): Any? {
        return if (mode == "speed") speedSession else null
    }

    fun refresh() {
        if (rendering || status == null) return
        updateSpeedSession()
        var state =
            if (!e!!.precise())
                "○  未获得精确位置权限"
            else
                if (!e!!.enabled())
                    "○  系统定位已关闭"
                else
                    if (e!!.fresh())
                        (if (e!!.location.isMock())
                            "⚠  模拟位置 · 不计入有效轨迹"
                        else
                            "●  卫星定位 · " + max(0, e!!.age() / 1000) + " 秒前更新")
                    else
                        if (e!!.location != null) "○  位置已过期 · " + e!!.age() / 1000 + " 秒未更新" else "○  正在等待卫星定位"
        val t: TrackService? = TrackService.Companion.active
        if (t != null) state += "   /   " + (if (t.paused) "记录已暂停" else "记录中")
        status!!.setText(state)
        status!!.setTextColor(if (e!!.fresh() && !e!!.location.isMock()) u!!.accent else u!!.warning)
        for (update in ArrayList<Runnable>(updates)) update.run()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (speedSession != null) speedSession!!.resume(SystemClock.elapsedRealtime())
        Preferences.applyWindow(this, u!!.light)
        e!!.observe(observer)
        e!!.setVisible(true)
        for (view in compassViews) view.setActive(true)
        serviceKey = key()
        app!!.main.removeCallbacks(tick)
        app!!.main.post(tick)
    }

    override fun onPause() {
        if (speedSession != null) speedSession!!.pause(SystemClock.elapsedRealtime())
        resumed = false
        for (view in compassViews) view.setActive(false)
        app!!.main.removeCallbacks(tick)
        e!!.unobserve(observer)
        e!!.setVisible(false)
        super.onPause()
    }

    override fun onDestroy() {
        app!!.main.removeCallbacks(tick)
        if (!isChangingConfigurations()) speedSession = null
        super.onDestroy()
    }

    fun toast(message: String?) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    fun openSystem(intent: Intent?) {
        try {
            startActivity(intent)
        } catch (ex: ActivityNotFoundException) {
            toast("系统未提供这个设置入口，请在设置中手动查找。")
        }
    }

    fun askLocation() {
        if (e!!.precise()) {
            if (!e!!.gpsEnabled()) openSystem(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else {
                e!!.reconcile()
                toast("已请求实时卫星定位，请到开阔处等待。")
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("启用精确定位")
            .setMessage("星迹使用卫星位置显示坐标和记录轨迹。数据仅保存在手机上，不申请始终允许位置。请在下一步选择精确位置。 ")
            .setPositiveButton(
                "继续",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    requestPermissions(
                        arrayOf<String?>(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        1
                    )
                })
            .setNegativeButton("暂不", null)
            .show()
    }

    fun record() {
        if (TrackService.Companion.active != null) {
            page = 2
            mode = ""
            buildRoot()
            return
        }
        if (!e!!.precise()) {
            toast("请先启用精确定位，再点击开始记录。")
            askLocation()
            return
        }
        if (!e!!.gpsEnabled()) {
            toast("请先开启系统定位。")
            openSystem(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= 33
            && (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED)
        ) {
            AlertDialog.Builder(this)
                .setTitle("记录状态通知")
                .setMessage("允许通知后，可在锁屏和通知栏暂停或停止轨迹。拒绝通知仍可记录，但需要回到应用操作。")
                .setPositiveButton(
                    "继续",
                    DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                        pendingRecord = true
                        requestPermissions(arrayOf<String?>(Manifest.permission.POST_NOTIFICATIONS), 2)
                    })
                .setNegativeButton("取消", null)
                .show()
        } else startRecord()
    }

    private fun startRecord() {
        try {
            startForegroundService(Intent(this, TrackService::class.java).setAction("START"))
            page = 2
            mode = ""
            buildRoot()
            toast("正在开始记录。熄屏记录仍受系统电池管理影响。")
        } catch (ex: RuntimeException) {
            toast("无法启动记录，请检查定位权限并重试。")
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String?>?, grants: IntArray?) {
        super.onRequestPermissionsResult(code, permissions!!, grants!!)
        e!!.reconcile()
        if (code == 1 && !e!!.precise()) toast("未获得精确位置权限。可在应用设置中开启。")
        if (code == 2 && pendingRecord) {
            pendingRecord = false
            startRecord()
        }
        render()
    }

    fun service(action: String) {
        if (TrackService.Companion.active == null) return
        if (action == "STOP") {
            AlertDialog.Builder(this)
                .setTitle("结束这段轨迹？")
                .setMessage("已记录的位置会保留在手机上。")
                .setPositiveButton(
                    "结束并保存",
                    DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> sendService("STOP") })
                .setNegativeButton("继续记录", null)
                .show()
        } else sendService(action)
    }

    private fun sendService(action: String) {
        startService(Intent(this, TrackService::class.java).setAction(action))
    }

    fun copy(text: String?) {
        copyCoordinate(text, "WGS84")
    }

    private fun copyCoordinate(text: String?, system: String) {
        getSystemService<ClipboardManager?>(ClipboardManager::class.java)!!
            .setPrimaryClip(ClipData.newPlainText(system + " 坐标", text))
        toast("已复制，坐标系 " + system + "。")
    }

    fun coordinates(l: Location): String? {
        return String.format(Locale.US, "%.7f, %.7f", l.getLatitude(), l.getLongitude())
    }

    fun groupSignals(): Boolean {
        return getPreferences(MODE_PRIVATE).getBoolean("groupSignals", true)
    }

    fun setGroupSignals(group: Boolean) {
        getPreferences(MODE_PRIVATE).edit().putBoolean("groupSignals", group).apply()
        render()
    }

    fun gcj(): Boolean {
        return getPreferences(MODE_PRIVATE).getBoolean("gcj02", false)
    }

    fun setCoordinateSystem(gcj: Boolean) {
        getPreferences(MODE_PRIVATE).edit().putBoolean("gcj02", gcj).apply()
        render()
    }

    fun coordinateLabel(): String {
        return if (gcj()) "GCJ-02" else "WGS84"
    }

    fun copyHere() {
        if (!e!!.fresh()) {
            toast("暂无新鲜位置，未复制过期坐标。")
            return
        }
        if (gcj()) {
            val c = Gcj02.fromWgs84(e!!.location.getLatitude(), e!!.location.getLongitude())
            if (!c.converted) {
                toast("当前位置超出近似转换覆盖范围，请切换到 WGS84。")
                return
            }
            copyCoordinate(
                String.format(Locale.US, "%.7f, %.7f (GCJ-02 · 离线近似)", c.latitude, c.longitude),
                "GCJ-02"
            )
        } else copyCoordinate(coordinates(e!!.location) + " (WGS84)", "WGS84")
    }

    fun saveHere() {
        if (!e!!.fresh()) {
            toast("请先等待有效定位。")
            return
        }
        val l: Location? = Location(e!!.location)
        placeDialog(l!!.getLatitude(), l.getLongitude())
    }

    private fun input(form: LinearLayout, hint: String, value: String?, numeric: Boolean): EditText {
        val field = EditText(this)
        field.setTextColor(u!!.text)
        field.setHintTextColor(u!!.muted)
        field.setHint(hint)
        field.setText(value)
        field.setTextSize(15f)
        field.setMinHeight(u!!.dp(54f))
        field.setSingleLine(true)
        field.setInputType(
            if (numeric)
                InputType.TYPE_CLASS_TEXT
            else
                (InputType.TYPE_CLASS_TEXT
                        or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        )
        form.addView(field, LinearLayout.LayoutParams(-1, -2))
        return field
    }

    private fun form(): LinearLayout {
        val f = u!!.column()
        f.setPadding(u!!.dp(22f), u!!.dp(12f), u!!.dp(22f), u!!.dp(6f))
        return f
    }

    fun placeDialog(lat: Double?, lon: Double?) {
        val f = form()
        val name = input(f, "地点名称", "", false)
        val note = input(f, "备注（可选）", "", false)
        val latitude =
            input(f, "纬度 · WGS84", if (lat == null) "" else String.format(Locale.US, "%.7f", lat), true)
        val longitude =
            input(f, "经度 · WGS84", if (lon == null) "" else String.format(Locale.US, "%.7f", lon), true)
        u!!.note(f, "仅接收 WGS84 坐标。支持十进制度或度分秒；不自动转换 GCJ-02 / BD-09。")
        val d =
            AlertDialog.Builder(this)
                .setTitle("保存地点")
                .setView(f)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create()
        d!!.setOnShowListener(OnShowListener { v: DialogInterface? ->
            d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(View.OnClickListener { b: View? ->
                    try {
                        var title: String? = name.getText().toString().trim { it <= ' ' }
                        if (title!!.isEmpty()) title = "收藏地点"
                        app!!.store.savePlace(
                            title,
                            note.getText().toString(),
                            Geo.parse(latitude.getText().toString(), true),
                            Geo.parse(longitude.getText().toString(), false)
                        )
                        d.dismiss()
                        toast("地点已保存")
                        if (mode == "places") render()
                    } catch (ex: IllegalArgumentException) {
                        toast(ex.message)
                    }
                })
        })
        d.show()
    }

    fun editPlaceDialog(place: Place): AlertDialog? {
        val f = form()
        val name = input(f, "地点名称", place.name, false)
        val note = input(f, "备注（可留空）", place.note, false)
        name.setContentDescription("地点名称")
        note.setContentDescription("地点备注")
        name.setFilters(
            arrayOf<InputFilter>(LengthFilter(100))
        )
        note.setInputType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        )
        note.setSingleLine(false)
        note.setMinLines(2)
        note.setMaxLines(5)
        note.setFilters(
            arrayOf<InputFilter>(LengthFilter(2000))
        )
        u!!.note(f, "仅修改名称和备注；原始 WGS84 坐标与创建时间保持不变。")
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("编辑收藏地点")
                .setView(f)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create()
        dialog!!.setOnShowListener(OnShowListener { v: DialogInterface? ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(View.OnClickListener { button: View? ->
                    try {
                        app!!.store.updatePlace(
                            place.id, name.getText().toString(), note.getText().toString()
                        )
                        if (destination != null && destination!!.id == place.id) {
                            destination!!.name = name.getText().toString().trim { it <= ' ' }
                            destination!!.note = note.getText().toString()
                        }
                        dialog.dismiss()
                        toast("地点已更新")
                        render()
                    } catch (ex: IllegalArgumentException) {
                        name.setError(ex.message)
                    } catch (ex: IllegalStateException) {
                        name.setError(ex.message)
                    }
                })
        })
        dialog.show()
        return dialog
    }

    fun openMap(trackId: Long) {
        startActivity(Intent(this, MapActivity::class.java).putExtra("track_id", trackId))
    }

    fun mapPrivacy() {
        AlertDialog.Builder(this)
            .setTitle("地图联网与隐私")
            .setMessage(MapPolicy.DISCLOSURE)
            .setPositiveButton(
                "允许在线底图",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    MapPolicy.allow(this, true)
                    toast("已允许，可打开地图查看")
                })
            .setNeutralButton(
                "撤回联网同意",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    MapPolicy.allow(this, false)
                    toast("已撤回。下次打开地图可选择仅本地轨迹。")
                })
            .setNegativeButton("取消", null)
            .show()
    }

    fun thirdPartyNotices() {
        try {
            getAssets().open("third-party/eviltransform-LICENSE.txt").use { `in` ->
                val license = String(`in`.readAllBytes(), StandardCharsets.UTF_8)
                AlertDialog.Builder(this)
                    .setTitle("离线坐标转换 · 第三方许可")
                    .setMessage(license)
                    .setPositiveButton("关闭", null)
                    .show()
            }
        } catch (ex: IOException) {
            toast("无法打开许可文本")
        }
    }

    fun placeActions(p: Place) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(
                arrayOf<String>("指向这个地点", "复制 WGS84 坐标", "重命名 / 编辑备注", "删除地点"),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    if (w == 0) {
                        destination = p
                        navigate("guide")
                    } else if (w == 1) copy(String.format(Locale.US, "%.7f, %.7f (WGS84)", p.lat, p.lon))
                    else if (w == 2) editPlaceDialog(p)
                    else AlertDialog.Builder(this)
                        .setTitle("删除地点？")
                        .setMessage(p.name)
                        .setPositiveButton(
                            "删除",
                            DialogInterface.OnClickListener { x: DialogInterface?, y: Int ->
                                app!!.store.deletePlace(p.id)
                                render()
                            })
                        .setNegativeButton("取消", null)
                        .show()
                })
            .show()
    }

    fun trackActions(t: Track) {
        val live = TrackService.Companion.active != null && TrackService.Companion.active.trackId == t.id
        val choices: Array<String?> =
            if (live)
                arrayOf<String>("查看轨迹")
            else
                arrayOf<String>(
                    "查看轨迹",
                    "导出 GPX（有效轨迹）",
                    "导出 KML（Google Earth / 我的地图）",
                    "导出 CSV（全部原始点）",
                    "重命名",
                    "删除轨迹"
                )
        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(
                choices,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    if (w == 0) {
                        selectedTrack = t.id
                        navigate("track")
                    } else if (w == 1) export(t, false)
                    else if (w == 2) export(t, TrackExport.KML)
                    else if (w == 3) export(t, true)
                    else if (w == 4) {
                        val f = form()
                        val n = input(f, "名称", t.name, false)
                        AlertDialog.Builder(this)
                            .setTitle("重命名")
                            .setView(f)
                            .setPositiveButton(
                                "保存",
                                DialogInterface.OnClickListener { x: DialogInterface?, y: Int ->
                                    if (!n.getText().toString().trim { it <= ' ' }.isEmpty()) app!!.store.rename(
                                        t.id,
                                        n.getText().toString().trim { it <= ' ' })
                                    render()
                                })
                            .setNegativeButton("取消", null)
                            .show()
                    } else AlertDialog.Builder(this)
                        .setTitle("永久删除这段轨迹？")
                        .setMessage("删除前可先导出备份。此操作不能撤销。")
                        .setPositiveButton(
                            "删除",
                            DialogInterface.OnClickListener { x: DialogInterface?, y: Int ->
                                app!!.store.delete(t.id)
                                render()
                            })
                        .setNegativeButton("取消", null)
                        .show()
                })
            .show()
    }

    fun export(t: Track, raw: Boolean) {
        export(t, if (raw) TrackExport.CSV else TrackExport.GPX)
    }

    fun prepareExport(t: Track?, format: TrackExport): Intent? {
        exportId = t!!.id
        exportFormat = format
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(format.mimeType)
            .putExtra(Intent.EXTRA_TITLE, "startrace-" + t.id + format.extension)
    }

    fun export(t: Track, format: TrackExport) {
        if (TrackService.Companion.active != null && TrackService.Companion.active.trackId == t.id) {
            toast("请结束记录后再导出，以获得一致的完整文件。")
            return
        }
        try {
            startActivityForResult(prepareExport(t, format), 5)
        } catch (ex: ActivityNotFoundException) {
            exportId = -1
            toast("未找到系统文件保存器。")
        }
    }

    public override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 5 && result != RESULT_OK) {
            exportId = -1
            return
        }
        if (request == 5 && result == RESULT_OK && data != null && data.getData() != null && exportId >= 0) {
            val id = exportId
            val format = exportFormat
            exportId = -1
            val uri = data.getData()
            app!!.io.execute(Runnable {
                try {
                    getContentResolver().openOutputStream(uri!!, "wt").use { stream ->
                        if (stream == null) throw IOException("无法打开文件")
                        app!!.store.export(id, stream, format)
                        app!!.main.post(Runnable { toast("导出完成，请妥善保管含精确位置的文件。") })
                    }
                } catch (ex: Exception) {
                    app!!.main.post(Runnable { toast("导出失败，请检查保存位置及可用空间。") })
                }
            })
        }
    }

    fun coordinateTool() {
        val f = form()
        val lat =
            input(
                f,
                "纬度 · 十进制度或度分秒",
                if (e!!.fresh()) String.format(Locale.US, "%.7f", e!!.location.getLatitude()) else "",
                true
            )
        val lon =
            input(
                f,
                "经度 · 十进制度或度分秒",
                if (e!!.fresh()) String.format(Locale.US, "%.7f", e!!.location.getLongitude()) else "",
                true
            )
        u!!.note(f, "这是格式转换，不是 WGS84 / GCJ-02 / BD-09 坐标系转换。")
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("坐标格式转换 · WGS84")
                .setView(f)
                .setPositiveButton("转换", null)
                .setNegativeButton("取消", null)
                .create()
        dialog!!.setOnShowListener(OnShowListener { v: DialogInterface? ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(View.OnClickListener { b: View? ->
                    try {
                        val la = Geo.parse(lat.getText().toString(), true)
                        val lo = Geo.parse(lon.getText().toString(), false)
                        val out: String? = String.format(
                            Locale.US,
                            "%.7f, %.7f\n%s\n%s\nWGS84",
                            la,
                            lo,
                            Geo.dms(la, true),
                            Geo.dms(lo, false)
                        )
                        AlertDialog.Builder(this)
                            .setTitle("转换结果")
                            .setMessage(out)
                            .setPositiveButton(
                                "复制",
                                DialogInterface.OnClickListener { x: DialogInterface?, y: Int -> copy(out) })
                            .setNegativeButton("关闭", null)
                            .show()
                    } catch (ex: IllegalArgumentException) {
                        toast(ex.message)
                    }
                })
        })
        dialog.show()
    }

    fun distanceTool() {
        val f = form()
        val a = input(f, "起点纬度", if (e!!.fresh()) "" + e!!.location.getLatitude() else "", true)
        val b = input(f, "起点经度", if (e!!.fresh()) "" + e!!.location.getLongitude() else "", true)
        val c = input(f, "终点纬度", "", true)
        val d = input(f, "终点经度", "", true)
        u!!.note(f, "两点必须使用相同的 WGS84 坐标系。结果为球面近似直线距离，不是道路里程。")
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("两点测距")
                .setView(f)
                .setPositiveButton("计算", null)
                .setNegativeButton("取消", null)
                .create()
        dialog!!.setOnShowListener(OnShowListener { v: DialogInterface? ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(View.OnClickListener { button: View? ->
                    try {
                        val la = Geo.parse(a.getText().toString(), true)
                        val lo = Geo.parse(b.getText().toString(), false)
                        val lb = Geo.parse(c.getText().toString(), true)
                        val ln = Geo.parse(d.getText().toString(), false)
                        AlertDialog.Builder(this)
                            .setTitle(Geo.meters(Geo.distance(la, lo, lb, ln)))
                            .setMessage(
                                String.format(
                                    Locale.US,
                                    "初始方位角 %.1f°（真北）\n不包含道路、障碍或高差。",
                                    Geo.bearing(la, lo, lb, ln)
                                )
                            )
                            .setPositiveButton("知道了", null)
                            .show()
                    } catch (ex: IllegalArgumentException) {
                        toast(ex.message)
                    }
                })
        })
        dialog.show()
    }

    fun theme() {
        Preferences.theme(this, if (u!!.light) ThemeMode.DARK else ThemeMode.LIGHT)
        buildRoot()
    }

    fun selectTheme() {
        val modes: Array<ThemeMode?> = ThemeMode.entries.toTypedArray()
        val labels = Arrays.stream<ThemeMode?>(modes).map<String?> { m: ThemeMode? -> m!!.label }
            .toArray<String?> { _Dummy_.__Array__() }
        AlertDialog.Builder(this)
            .setTitle("色彩模式")
            .setSingleChoiceItems(
                labels,
                Preferences.theme(this).ordinal,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    Preferences.theme(this, modes[which])
                    dialog!!.dismiss()
                    buildRoot()
                })
            .setNegativeButton("取消", null)
            .show()
    }

    fun selectRecordingInterval() {
        val labels: Array<String?> =
            Arrays.stream(RecordingCadence.Companion.SECONDS)
                .mapToObj<String?>(IntFunction { i: Int -> i.toString() + " 秒 / 点" })
                .toArray<String?> { _Dummy_.__Array__() }
        var selected = 0
        for (i in RecordingCadence.Companion.SECONDS.indices) if (RecordingCadence.Companion.SECONDS[i] == Preferences.interval(
                this
            )
        ) selected = i
        AlertDialog.Builder(this)
            .setTitle("轨迹点保存间隔")
            .setSingleChoiceItems(
                labels,
                selected,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    val seconds: Int = RecordingCadence.Companion.SECONDS[which]
                    Preferences.interval(this, seconds)
                    if (TrackService.Companion.active != null) TrackService.Companion.active.setIntervalSeconds(seconds)
                    dialog!!.dismiss()
                    render()
                })
            .setNegativeButton("取消", null)
            .show()
    }

    fun dms(): Boolean {
        return getPreferences(MODE_PRIVATE).getBoolean("dms", false)
    }

    fun toggleDms() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("dms", !dms()).apply()
        render()
    }

    fun keepAwake(on: Boolean) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else clearKeepAwake()
    }

    private fun clearKeepAwake() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun appSettings() {
        openSystem(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())
            )
        )
    }

    fun visibleText(): String? {
        val text = StringBuilder()
        collect(root, text)
        return text.toString()
    }

    private fun collect(v: View?, out: StringBuilder) {
        if (v is TextView) out.append(v.getText()).append('\n')
        if (v is ViewGroup) {
            val g = v
            for (i in 0..<g.getChildCount()) collect(g.getChildAt(i), out)
        }
    }
}

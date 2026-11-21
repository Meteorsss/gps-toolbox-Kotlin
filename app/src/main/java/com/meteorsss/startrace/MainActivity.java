package com.meteorsss.startrace;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;

public final class MainActivity extends Activity {
  public App app;
  public GnssEngine e;
  public Ui u;
  public LinearLayout body;
  public int page;
  public String mode = "";
  public long selectedTrack = -1;
  public Store.Place destination;
  private LinearLayout root;
  private ScrollView scroll;
  private TextView status;
  private final List<Runnable> updates = new ArrayList<>();
  private final List<CompassView> compassViews = new ArrayList<>();
  private boolean resumed, rendering, pendingRecord;
  private String serviceKey = "";
  private long exportId = -1;
  private TrackExport exportFormat = TrackExport.GPX;
  private SpeedSession speedSession;
  private final GnssEngine.Observer observer =
      () -> {
        if (resumed && !rendering) refresh();
      };

  @Override
  public void onCreate(Bundle saved) {
    setTheme(Preferences.light(this) ? R.style.AppThemeLight : R.style.AppTheme);
    super.onCreate(saved);
    app = (App) getApplication();
    e = app.engine;
    if (saved != null) {
      page = saved.getInt("page");
      mode = saved.getString("mode", "");
      selectedTrack = saved.getLong("track", -1);
      exportId = saved.getLong("export", -1);
      exportFormat = TrackExport.restore(saved.getString("export_format"), saved.getBoolean("raw"));
      pendingRecord = saved.getBoolean("pendingRecord");
      if (mode.equals("guide")) mode = "places";
    }
    Object retained = getLastNonConfigurationInstance();
    if (mode.equals("speed") && retained instanceof SpeedSession)
      speedSession = (SpeedSession) retained;
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
    buildRoot();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putInt("page", page);
    out.putString("mode", mode);
    out.putLong("track", selectedTrack);
    out.putLong("export", exportId);
    out.putString("export_format", exportFormat.name());
    out.putBoolean("pendingRecord", pendingRecord);
  }

  public void buildRoot() {
    boolean light = Preferences.light(this);
    setTheme(light ? R.style.AppThemeLight : R.style.AppTheme);
    u = new Ui(this, light);
    getWindow().setDecorFitsSystemWindows(false);
    getWindow().setNavigationBarContrastEnforced(false);
    root = u.column();
    root.setBackgroundColor(u.bg);
    root.setOnApplyWindowInsetsListener((v, insets) -> {
      android.graphics.Insets i =
          insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      v.setPadding(i.left, i.top, i.right, i.bottom);
      return insets;
    });
    LinearLayout top = u.row();
    top.setPadding(u.dp(20), u.dp(12), u.dp(20), u.dp(12));
    LinearLayout brand = u.column();
    TextView over = u.text("STARTRACE / PERSONAL GPS", 9, u.accent);
    over.setLetterSpacing(.14f);
    brand.addView(over);
    u.gap(brand, 5);
    brand.addView(u.title("星迹", 26));
    top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    TextView settings = u.button("设置", false, () -> navigate("settings"));
    top.addView(settings, new LinearLayout.LayoutParams(u.dp(64), u.dp(48)));
    root.addView(top);
    status = u.text("检查定位状态…", 12, u.muted);
    status.setPadding(u.dp(22), 0, u.dp(22), u.dp(12));
    root.addView(status);
    status.setOnClickListener(v -> askLocation());
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    body = u.column();
    body.setPadding(u.dp(18), u.dp(8), u.dp(18), u.dp(18));
    scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout nav = u.row();
    nav.setPadding(u.dp(10), u.dp(8), u.dp(10), u.dp(8));
    nav.setBackgroundColor(u.card);
    String[] names = {"◉  仪表", "✦  卫星", "⌁  记录", "⊞  工具"};
    for (int i = 0; i < 4; i++) {
      final int n = i;
      TextView item = u.button(names[i], i == page, () -> goPage(n));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, u.dp(48), 1);
      lp.leftMargin = u.dp(3);
      lp.rightMargin = u.dp(3);
      nav.addView(item, lp);
    }
    root.addView(nav);
    setContentView(root);
    root.requestApplyInsets();
    Preferences.applyWindow(this, light);
    render();
  }

  public void goPage(int index) {
    page = index;
    mode = "";
    clearKeepAwake();
    buildRoot();
  }

  public void navigate(String value) {
    mode = value;
    clearKeepAwake();
    render();
    scroll.scrollTo(0, 0);
  }

  private void back() {
    if (!mode.isEmpty()) {
      navigate("");
    } else if (page != 0) {
      goPage(0);
    } else {
      finish();
    }
  }

  public void render() {
    if (!mode.equals("speed")) speedSession = null;
    else if (speedSession == null) speedSession = new SpeedSession(SystemClock.elapsedRealtime());
    rendering = true;
    for (CompassView view : compassViews) view.setActive(false);
    compassViews.clear();
    e.setCompassActive(mode.equals("compass") || mode.equals("guide"));
    updates.clear();
    body.removeAllViews();
    if (!mode.isEmpty()) {
      body.addView(u.button("‹  返回", false, () -> navigate(mode.equals("guide") ? "places" : "")));
      u.gap(body, 16);
    }
    Screens.render(this);
    rendering = false;
    refresh();
  }

  public void registerCompass(CompassView view) {
    compassViews.add(view);
    view.setActive(resumed);
    watch(view::updateSlowState);
  }

  public void watch(Runnable update) {
    updates.add(update);
    update.run();
  }

  public void bind(TextView target, Supplier<String> value) {
    watch(() -> {
      String s = value.get();
      if (!target.getText().toString().equals(s)) target.setText(s);
    });
  }

  private String key() {
    TrackService t = TrackService.active;
    return t == null ? "none" : t.trackId + ":" + t.paused;
  }

  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          if (!serviceKey.equals(key())) {
            serviceKey = key();
            render();
          } else refresh();
          app.main.postDelayed(this, 500);
        }
      };

  private void updateSpeedSession() {
    if (!resumed || speedSession == null) return;
    if (!e.fresh()) {
      speedSession.disconnect();
      return;
    }
    Location l = e.location;
    speedSession.add(
        new TrackPoint(
            l.getLatitude(),
            l.getLongitude(),
            Double.NaN,
            l.hasAccuracy() ? l.getAccuracy() : Double.NaN,
            l.hasSpeed() ? l.getSpeed() : Double.NaN,
            l.getTime(),
            l.getElapsedRealtimeNanos() / 1000000L,
            0,
            true,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            l.hasSpeedAccuracy() ? l.getSpeedAccuracyMetersPerSecond() : Double.NaN),
        l.isMock(),
        SystemClock.elapsedRealtime());
  }

  SpeedSession.Snapshot speedStats() {
    return speedSession == null ? null : speedSession.snapshot(SystemClock.elapsedRealtime());
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return mode.equals("speed") ? speedSession : null;
  }

  void refresh() {
    if (rendering || status == null) return;
    updateSpeedSession();
    String state =
        !e.precise()
            ? "○  未获得精确位置权限"
            : !e.enabled()
                ? "○  系统定位已关闭"
                : e.fresh()
                    ? (e.location.isMock()
                        ? "⚠  模拟位置 · 不计入有效轨迹"
                        : "●  卫星定位 · " + Math.max(0, e.age() / 1000) + " 秒前更新")
                    : e.location != null ? "○  位置已过期 · " + e.age() / 1000 + " 秒未更新" : "○  正在等待卫星定位";
    TrackService t = TrackService.active;
    if (t != null) state += "   /   " + (t.paused ? "记录已暂停" : "记录中");
    status.setText(state);
    status.setTextColor(e.fresh() && !e.location.isMock() ? u.accent : u.warning);
    for (Runnable update : new ArrayList<>(updates)) update.run();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    if (speedSession != null) speedSession.resume(SystemClock.elapsedRealtime());
    Preferences.applyWindow(this, u.light);
    e.observe(observer);
    e.setVisible(true);
    for (CompassView view : compassViews) view.setActive(true);
    serviceKey = key();
    app.main.removeCallbacks(tick);
    app.main.post(tick);
  }

  @Override
  protected void onPause() {
    if (speedSession != null) speedSession.pause(SystemClock.elapsedRealtime());
    resumed = false;
    for (CompassView view : compassViews) view.setActive(false);
    app.main.removeCallbacks(tick);
    e.unobserve(observer);
    e.setVisible(false);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    app.main.removeCallbacks(tick);
    if (!isChangingConfigurations()) speedSession = null;
    super.onDestroy();
  }

  public void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  public void openSystem(Intent intent) {
    try {
      startActivity(intent);
    } catch (ActivityNotFoundException ex) {
      toast("系统未提供这个设置入口，请在设置中手动查找。");
    }
  }

  public void askLocation() {
    if (e.precise()) {
      if (!e.gpsEnabled()) openSystem(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
      else {
        e.reconcile();
        toast("已请求实时卫星定位，请到开阔处等待。");
      }
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("启用精确定位")
        .setMessage("星迹使用卫星位置显示坐标和记录轨迹。数据仅保存在手机上，不申请始终允许位置。请在下一步选择精确位置。 ")
        .setPositiveButton(
            "继续",
            (d, w) ->
                requestPermissions(
                    new String[] {
                      Manifest.permission.ACCESS_FINE_LOCATION,
                      Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    1))
        .setNegativeButton("暂不", null)
        .show();
  }

  public void record() {
    if (TrackService.active != null) {
      page = 2;
      mode = "";
      buildRoot();
      return;
    }
    if (!e.precise()) {
      toast("请先启用精确定位，再点击开始记录。");
      askLocation();
      return;
    }
    if (!e.gpsEnabled()) {
      toast("请先开启系统定位。");
      openSystem(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      new AlertDialog.Builder(this)
          .setTitle("记录状态通知")
          .setMessage("允许通知后，可在锁屏和通知栏暂停或停止轨迹。拒绝通知仍可记录，但需要回到应用操作。")
          .setPositiveButton(
              "继续",
              (d, w) -> {
                pendingRecord = true;
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 2);
              })
          .setNegativeButton("取消", null)
          .show();
    } else startRecord();
  }

  private void startRecord() {
    try {
      startForegroundService(new Intent(this, TrackService.class).setAction("START"));
      page = 2;
      mode = "";
      buildRoot();
      toast("正在开始记录。熄屏记录仍受系统电池管理影响。");
    } catch (RuntimeException ex) {
      toast("无法启动记录，请检查定位权限并重试。");
    }
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(code, permissions, grants);
    e.reconcile();
    if (code == 1 && !e.precise()) toast("未获得精确位置权限。可在应用设置中开启。");
    if (code == 2 && pendingRecord) {
      pendingRecord = false;
      startRecord();
    }
    render();
  }

  public void service(String action) {
    if (TrackService.active == null) return;
    if (action.equals("STOP")) {
      new AlertDialog.Builder(this)
          .setTitle("结束这段轨迹？")
          .setMessage("已记录的位置会保留在手机上。")
          .setPositiveButton("结束并保存", (d, w) -> sendService("STOP"))
          .setNegativeButton("继续记录", null)
          .show();
    } else sendService(action);
  }

  private void sendService(String action) {
    startService(new Intent(this, TrackService.class).setAction(action));
  }

  public void copy(String text) {
    copyCoordinate(text, "WGS84");
  }

  private void copyCoordinate(String text, String system) {
    getSystemService(android.content.ClipboardManager.class)
        .setPrimaryClip(ClipData.newPlainText(system + " 坐标", text));
    toast("已复制，坐标系 " + system + "。");
  }

  public String coordinates(Location l) {
    return String.format(Locale.US, "%.7f, %.7f", l.getLatitude(), l.getLongitude());
  }

  public boolean groupSignals() {
    return getPreferences(MODE_PRIVATE).getBoolean("groupSignals", true);
  }

  public void setGroupSignals(boolean group) {
    getPreferences(MODE_PRIVATE).edit().putBoolean("groupSignals", group).apply();
    render();
  }

  public boolean gcj() {
    return getPreferences(MODE_PRIVATE).getBoolean("gcj02", false);
  }

  public void setCoordinateSystem(boolean gcj) {
    getPreferences(MODE_PRIVATE).edit().putBoolean("gcj02", gcj).apply();
    render();
  }

  public String coordinateLabel() {
    return gcj() ? "GCJ-02" : "WGS84";
  }

  public void copyHere() {
    if (!e.fresh()) {
      toast("暂无新鲜位置，未复制过期坐标。");
      return;
    }
    if (gcj()) {
      Gcj02.Coordinate c = Gcj02.fromWgs84(e.location.getLatitude(), e.location.getLongitude());
      if (!c.converted) {
        toast("当前位置超出近似转换覆盖范围，请切换到 WGS84。");
        return;
      }
      copyCoordinate(
          String.format(Locale.US, "%.7f, %.7f (GCJ-02 · 离线近似)", c.latitude, c.longitude),
          "GCJ-02");
    } else copyCoordinate(coordinates(e.location) + " (WGS84)", "WGS84");
  }

  public void saveHere() {
    if (!e.fresh()) {
      toast("请先等待有效定位。");
      return;
    }
    Location l = new Location(e.location);
    placeDialog(l.getLatitude(), l.getLongitude());
  }

  private EditText input(LinearLayout form, String hint, String value, boolean numeric) {
    EditText field = new EditText(this);
    field.setTextColor(u.text);
    field.setHintTextColor(u.muted);
    field.setHint(hint);
    field.setText(value);
    field.setTextSize(15);
    field.setMinHeight(u.dp(54));
    field.setSingleLine(true);
    field.setInputType(
        numeric
            ? android.text.InputType.TYPE_CLASS_TEXT
            : android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    form.addView(field, new LinearLayout.LayoutParams(-1, -2));
    return field;
  }

  private LinearLayout form() {
    LinearLayout f = u.column();
    f.setPadding(u.dp(22), u.dp(12), u.dp(22), u.dp(6));
    return f;
  }

  public void placeDialog(Double lat, Double lon) {
    LinearLayout f = form();
    EditText name = input(f, "地点名称", "", false),
        note = input(f, "备注（可选）", "", false),
        latitude =
            input(f, "纬度 · WGS84", lat == null ? "" : String.format(Locale.US, "%.7f", lat), true),
        longitude =
            input(f, "经度 · WGS84", lon == null ? "" : String.format(Locale.US, "%.7f", lon), true);
    u.note(f, "仅接收 WGS84 坐标。支持十进制度或度分秒；不自动转换 GCJ-02 / BD-09。");
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("保存地点")
            .setView(f)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();
    d.setOnShowListener(v ->
        d.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(b -> {
              try {
                String title = name.getText().toString().trim();
                if (title.isEmpty()) title = "收藏地点";
                app.store.savePlace(
                    title,
                    note.getText().toString(),
                    Geo.parse(latitude.getText().toString(), true),
                    Geo.parse(longitude.getText().toString(), false));
                d.dismiss();
                toast("地点已保存");
                if (mode.equals("places")) render();
              } catch (IllegalArgumentException ex) {
                toast(ex.getMessage());
              }
            }));
    d.show();
  }

  public AlertDialog editPlaceDialog(Store.Place place) {
    LinearLayout f = form();
    EditText name = input(f, "地点名称", place.name, false),
        note = input(f, "备注（可留空）", place.note, false);
    name.setContentDescription("地点名称");
    note.setContentDescription("地点备注");
    name.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(100)});
    note.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    note.setSingleLine(false);
    note.setMinLines(2);
    note.setMaxLines(5);
    note.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(2000)});
    u.note(f, "仅修改名称和备注；原始 WGS84 坐标与创建时间保持不变。");
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("编辑收藏地点")
            .setView(f)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.setOnShowListener(v ->
        dialog
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(button -> {
              try {
                app.store.updatePlace(
                    place.id, name.getText().toString(), note.getText().toString());
                if (destination != null && destination.id == place.id) {
                  destination.name = name.getText().toString().trim();
                  destination.note = note.getText().toString();
                }
                dialog.dismiss();
                toast("地点已更新");
                render();
              } catch (IllegalArgumentException | IllegalStateException ex) {
                name.setError(ex.getMessage());
              }
            }));
    dialog.show();
    return dialog;
  }

  public void openMap(long trackId) {
    startActivity(new Intent(this, MapActivity.class).putExtra("track_id", trackId));
  }

  public void mapPrivacy() {
    new AlertDialog.Builder(this)
        .setTitle("地图联网与隐私")
        .setMessage(MapPolicy.DISCLOSURE)
        .setPositiveButton(
            "允许在线底图",
            (d, w) -> {
              MapPolicy.allow(this, true);
              toast("已允许，可打开地图查看");
            })
        .setNeutralButton(
            "撤回联网同意",
            (d, w) -> {
              MapPolicy.allow(this, false);
              toast("已撤回。下次打开地图可选择仅本地轨迹。");
            })
        .setNegativeButton("取消", null)
        .show();
  }

  public void thirdPartyNotices() {
    try (InputStream in = getAssets().open("third-party/eviltransform-LICENSE.txt")) {
      String license = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      new AlertDialog.Builder(this)
          .setTitle("离线坐标转换 · 第三方许可")
          .setMessage(license)
          .setPositiveButton("关闭", null)
          .show();
    } catch (IOException ex) {
      toast("无法打开许可文本");
    }
  }

  public void placeActions(Store.Place p) {
    new AlertDialog.Builder(this)
        .setTitle(p.name)
        .setItems(
            new String[] {"指向这个地点", "复制 WGS84 坐标", "重命名 / 编辑备注", "删除地点"},
            (d, w) -> {
              if (w == 0) {
                destination = p;
                navigate("guide");
              } else if (w == 1) copy(String.format(Locale.US, "%.7f, %.7f (WGS84)", p.lat, p.lon));
              else if (w == 2) editPlaceDialog(p);
              else
                new AlertDialog.Builder(this)
                    .setTitle("删除地点？")
                    .setMessage(p.name)
                    .setPositiveButton(
                        "删除",
                        (x, y) -> {
                          app.store.deletePlace(p.id);
                          render();
                        })
                    .setNegativeButton("取消", null)
                    .show();
            })
        .show();
  }

  public void trackActions(Store.Track t) {
    boolean live = TrackService.active != null && TrackService.active.trackId == t.id;
    String[] choices =
        live
            ? new String[] {"查看轨迹"}
            : new String[] {
              "查看轨迹", "导出 GPX（有效轨迹）", "导出 KML（Google Earth / 我的地图）", "导出 CSV（全部原始点）", "重命名", "删除轨迹"
            };
    new AlertDialog.Builder(this)
        .setTitle(t.name)
        .setItems(
            choices,
            (d, w) -> {
              if (w == 0) {
                selectedTrack = t.id;
                navigate("track");
              } else if (w == 1) export(t, false);
              else if (w == 2) export(t, TrackExport.KML);
              else if (w == 3) export(t, true);
              else if (w == 4) {
                LinearLayout f = form();
                EditText n = input(f, "名称", t.name, false);
                new AlertDialog.Builder(this)
                    .setTitle("重命名")
                    .setView(f)
                    .setPositiveButton(
                        "保存",
                        (x, y) -> {
                          if (!n.getText().toString().trim().isEmpty())
                            app.store.rename(t.id, n.getText().toString().trim());
                          render();
                        })
                    .setNegativeButton("取消", null)
                    .show();
              } else
                new AlertDialog.Builder(this)
                    .setTitle("永久删除这段轨迹？")
                    .setMessage("删除前可先导出备份。此操作不能撤销。")
                    .setPositiveButton(
                        "删除",
                        (x, y) -> {
                          app.store.delete(t.id);
                          render();
                        })
                    .setNegativeButton("取消", null)
                    .show();
            })
        .show();
  }

  public void export(Store.Track t, boolean raw) {
    export(t, raw ? TrackExport.CSV : TrackExport.GPX);
  }

  Intent prepareExport(Store.Track t, TrackExport format) {
    exportId = t.id;
    exportFormat = format;
    return new Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(format.mimeType)
        .putExtra(Intent.EXTRA_TITLE, "startrace-" + t.id + format.extension);
  }

  public void export(Store.Track t, TrackExport format) {
    if (TrackService.active != null && TrackService.active.trackId == t.id) {
      toast("请结束记录后再导出，以获得一致的完整文件。");
      return;
    }
    try {
      startActivityForResult(prepareExport(t, format), 5);
    } catch (ActivityNotFoundException ex) {
      exportId = -1;
      toast("未找到系统文件保存器。");
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 5 && result != RESULT_OK) {
      exportId = -1;
      return;
    }
    if (request == 5
        && result == RESULT_OK
        && data != null
        && data.getData() != null
        && exportId >= 0) {
      long id = exportId;
      TrackExport format = exportFormat;
      exportId = -1;
      Uri uri = data.getData();
      app.io.execute(() -> {
        try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
          if (stream == null) throw new IOException("无法打开文件");
          app.store.export(id, stream, format);
          app.main.post(() -> toast("导出完成，请妥善保管含精确位置的文件。"));
        } catch (Exception ex) {
          app.main.post(() -> toast("导出失败，请检查保存位置及可用空间。"));
        }
      });
    }
  }

  public void coordinateTool() {
    LinearLayout f = form();
    EditText
        lat =
            input(
                f,
                "纬度 · 十进制度或度分秒",
                e.fresh() ? String.format(Locale.US, "%.7f", e.location.getLatitude()) : "",
                true),
        lon =
            input(
                f,
                "经度 · 十进制度或度分秒",
                e.fresh() ? String.format(Locale.US, "%.7f", e.location.getLongitude()) : "",
                true);
    u.note(f, "这是格式转换，不是 WGS84 / GCJ-02 / BD-09 坐标系转换。");
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("坐标格式转换 · WGS84")
            .setView(f)
            .setPositiveButton("转换", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.setOnShowListener(v ->
        dialog
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(b -> {
              try {
                double la = Geo.parse(lat.getText().toString(), true),
                    lo = Geo.parse(lon.getText().toString(), false);
                String out =
                    String.format(
                        Locale.US,
                        "%.7f, %.7f\n%s\n%s\nWGS84",
                        la,
                        lo,
                        Geo.dms(la, true),
                        Geo.dms(lo, false));
                new AlertDialog.Builder(this)
                    .setTitle("转换结果")
                    .setMessage(out)
                    .setPositiveButton("复制", (x, y) -> copy(out))
                    .setNegativeButton("关闭", null)
                    .show();
              } catch (IllegalArgumentException ex) {
                toast(ex.getMessage());
              }
            }));
    dialog.show();
  }

  public void distanceTool() {
    LinearLayout f = form();
    EditText a = input(f, "起点纬度", e.fresh() ? "" + e.location.getLatitude() : "", true),
        b = input(f, "起点经度", e.fresh() ? "" + e.location.getLongitude() : "", true),
        c = input(f, "终点纬度", "", true),
        d = input(f, "终点经度", "", true);
    u.note(f, "两点必须使用相同的 WGS84 坐标系。结果为球面近似直线距离，不是道路里程。");
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("两点测距")
            .setView(f)
            .setPositiveButton("计算", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.setOnShowListener(v ->
        dialog
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(button -> {
              try {
                double la = Geo.parse(a.getText().toString(), true),
                    lo = Geo.parse(b.getText().toString(), false),
                    lb = Geo.parse(c.getText().toString(), true),
                    ln = Geo.parse(d.getText().toString(), false);
                new AlertDialog.Builder(this)
                    .setTitle(Geo.meters(Geo.distance(la, lo, lb, ln)))
                    .setMessage(
                        String.format(
                            Locale.US,
                            "初始方位角 %.1f°（真北）\n不包含道路、障碍或高差。",
                            Geo.bearing(la, lo, lb, ln)))
                    .setPositiveButton("知道了", null)
                    .show();
              } catch (IllegalArgumentException ex) {
                toast(ex.getMessage());
              }
            }));
    dialog.show();
  }

  public void theme() {
    Preferences.theme(this, u.light ? ThemeMode.DARK : ThemeMode.LIGHT);
    buildRoot();
  }

  public void selectTheme() {
    ThemeMode[] modes = ThemeMode.values();
    String[] labels = Arrays.stream(modes).map(m -> m.label).toArray(String[]::new);
    new AlertDialog.Builder(this)
        .setTitle("色彩模式")
        .setSingleChoiceItems(
            labels,
            Preferences.theme(this).ordinal(),
            (dialog, which) -> {
              Preferences.theme(this, modes[which]);
              dialog.dismiss();
              buildRoot();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  public void selectRecordingInterval() {
    String[] labels =
        Arrays.stream(RecordingCadence.SECONDS).mapToObj(i -> i + " 秒 / 点").toArray(String[]::new);
    int selected = 0;
    for (int i = 0; i < RecordingCadence.SECONDS.length; i++)
      if (RecordingCadence.SECONDS[i] == Preferences.interval(this)) selected = i;
    new AlertDialog.Builder(this)
        .setTitle("轨迹点保存间隔")
        .setSingleChoiceItems(
            labels,
            selected,
            (dialog, which) -> {
              int seconds = RecordingCadence.SECONDS[which];
              Preferences.interval(this, seconds);
              if (TrackService.active != null) TrackService.active.setIntervalSeconds(seconds);
              dialog.dismiss();
              render();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  public boolean dms() {
    return getPreferences(MODE_PRIVATE).getBoolean("dms", false);
  }

  public void toggleDms() {
    getPreferences(MODE_PRIVATE).edit().putBoolean("dms", !dms()).apply();
    render();
  }

  public void keepAwake(boolean on) {
    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else clearKeepAwake();
  }

  private void clearKeepAwake() {
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  public void appSettings() {
    openSystem(
        new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName())));
  }

  public String visibleText() {
    StringBuilder text = new StringBuilder();
    collect(root, text);
    return text.toString();
  }

  private void collect(View v, StringBuilder out) {
    if (v instanceof TextView) out.append(((TextView) v).getText()).append('\n');
    if (v instanceof android.view.ViewGroup) {
      android.view.ViewGroup g = (android.view.ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
    }
  }
}

package com.meteorsss.startrace;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class TrackService extends Service {
  public static TrackService active;
  private App app;
  public long trackId = -1;
  public boolean paused;
  public double distance;
  private long accumulated, resumed, lastElapsed = -1;
  private int segment;
  private TrackPoint previous, anchor;
  private boolean ending, unsavedBreak;
  private final RecordingCadence cadence = new RecordingCadence();
  private int intervalSeconds = 1;
  private final TrackAnalytics analytics = new TrackAnalytics();

  public TrackAnalytics.Result insights() {
    return analytics.finish(distance, duration());
  }

  public int intervalSeconds() {
    return intervalSeconds;
  }

  public void setIntervalSeconds(int seconds) {
    int value = RecordingCadence.sanitize(seconds);
    if (value == intervalSeconds) return;
    intervalSeconds = value;
    cadence.reset();
    unsavedBreak = false;
    if (trackId >= 0) {
      segment++;
      previous = null;
      anchor = null;
    }
  }

  void prepareForTests(App application, long id) {
    app = application;
    trackId = id;
    accumulated = 60000; // Synthetic fixture has one minute of prior active recording.
    resumed = SystemClock.elapsedRealtime();
  }

  void fixForTests(Location location) {
    onFix(location);
  }

  public long duration() {
    return accumulated + (!paused && resumed > 0 ? SystemClock.elapsedRealtime() - resumed : 0);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    app = (App) getApplication();
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel("recording", "轨迹记录", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? "" : intent.getAction();
    if ("STOP".equals(action)) {
      finish("finished");
      return START_NOT_STICKY;
    }
    if ("PAUSE".equals(action)) {
      pause();
      return START_NOT_STICKY;
    }
    if ("RESUME".equals(action)) {
      resume();
      return START_NOT_STICKY;
    }
    if (trackId != -1) return START_NOT_STICKY;
    if (!app.engine.precise() || !app.engine.gpsEnabled()) {
      stopSelf();
      return START_NOT_STICKY;
    }
    try {
      intervalSeconds = Preferences.interval(this);
      startForeground(11, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
      trackId =
          app.store.begin(
              "轨迹 " + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date()));
      resumed = SystemClock.elapsedRealtime();
      active = this;
      app.engine.recorder = this::onFix;
      app.engine.setRecording(true);
      app.main.post(ticker);
    } catch (RuntimeException e) {
      if (trackId != -1) app.store.update(trackId, duration(), distance, "interrupted");
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  private final Runnable ticker =
      new Runnable() {
        @Override
        public void run() {
          if (ending || trackId == -1) return;
          if (!app.engine.precise()) {
            finish("interrupted");
            return;
          }
          app.store.update(trackId, duration(), distance, paused ? "paused" : "recording");
          getSystemService(NotificationManager.class).notify(11, notification());
          app.main.postDelayed(this, 5000);
        }
      };

  private void onFix(Location location) {
    if (paused || ending || trackId < 0) return;
    long elapsed = location.getElapsedRealtimeNanos() / 1000000L;
    if (elapsed <= lastElapsed) return;
    lastElapsed = elapsed;
    TrackPoint candidate =
        new TrackPoint(
            location.getLatitude(),
            location.getLongitude(),
            location.hasAltitude() ? location.getAltitude() : Double.NaN,
            location.hasAccuracy() ? location.getAccuracy() : Double.NaN,
            location.hasSpeed() ? location.getSpeed() : Double.NaN,
            location.getTime(),
            elapsed,
            segment,
            false,
            location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : Double.NaN,
            Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()
                ? location.getMslAltitudeMeters()
                : Double.NaN,
            Build.VERSION.SDK_INT >= 34 && location.hasMslAltitudeAccuracy()
                ? location.getMslAltitudeAccuracyMeters()
                : Double.NaN,
            location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : Double.NaN);
    boolean good =
        !location.isMock()
            && TrackFilter.usable(candidate, SystemClock.elapsedRealtime())
            && TrackFilter.plausible(previous, candidate);
    if (!cadence.shouldSave(elapsed, intervalSeconds)) {
      if (!good) unsavedBreak = true;
      return;
    }
    if (unsavedBreak) {
      segment++;
      previous = null;
      anchor = null;
      unsavedBreak = false;
    }
    if (good && previous != null && elapsed - previous.elapsed > 30000) {
      segment++;
      previous = null;
      anchor = null;
    }
    TrackPoint p =
        new TrackPoint(
            candidate.lat,
            candidate.lon,
            candidate.altitude,
            candidate.accuracy,
            candidate.speed,
            candidate.time,
            candidate.elapsed,
            segment,
            good,
            candidate.verticalAccuracy,
            candidate.mslAltitude,
            candidate.mslAccuracy,
            candidate.speedAccuracy);
    try {
      app.store.add(trackId, p, location.isMock());
      analytics.add(p);
      if (good) {
        double step = TrackFilter.distanceContribution(anchor, p);
        distance += step;
        if (anchor == null || step > 0 || p.elapsed - anchor.elapsed > 30000) anchor = p;
        previous = p;
      } else {
        segment++;
        previous = null;
        anchor = null;
      }
      app.store.update(trackId, duration(), distance, "recording");
    } catch (RuntimeException e) {
      finish("interrupted");
    }
  }

  private void pause() {
    if (trackId < 0 || paused) return;
    accumulated = duration();
    paused = true;
    app.engine.setRecording(false);
    app.store.update(trackId, duration(), distance, "paused");
    getSystemService(NotificationManager.class).notify(11, notification());
  }

  private void resume() {
    if (trackId < 0 || !paused || !app.engine.precise()) return;
    segment++;
    previous = null;
    anchor = null;
    cadence.reset();
    unsavedBreak = false;
    paused = false;
    resumed = SystemClock.elapsedRealtime();
    app.engine.setRecording(true);
    app.store.update(trackId, duration(), distance, "recording");
    getSystemService(NotificationManager.class).notify(11, notification());
  }

  private Notification notification() {
    Intent open = new Intent(this, MainActivity.class);
    PendingIntent content =
        PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification.Builder b =
        new Notification.Builder(this, "recording")
            .setSmallIcon(com.meteorsss.startrace.R.drawable.ic_app)
            .setContentTitle(paused ? "星迹 · 已暂停" : "星迹 · 正在记录")
            .setContentText(
                Geo.duration(duration())
                    + " · "
                    + Geo.meters(distance)
                    + " · "
                    + (app.engine.fresh() ? "定位正常" : "等待定位，缺失段不会补线"))
            .setContentIntent(content)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true);
    b.addAction(
        new Notification.Action.Builder(
                null, paused ? "继续" : "暂停", action(paused ? "RESUME" : "PAUSE", 1))
            .build());
    b.addAction(new Notification.Action.Builder(null, "停止", action("STOP", 2)).build());
    return b.build();
  }

  private PendingIntent action(String action, int id) {
    return PendingIntent.getService(
        this,
        id,
        new Intent(this, TrackService.class).setAction(action),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private void finish(String status) {
    if (ending) return;
    ending = true;
    if (trackId >= 0) app.store.update(trackId, duration(), distance, status);
    app.main.removeCallbacks(ticker);
    app.engine.recorder = null;
    app.engine.setRecording(false);
    active = null;
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    if (!ending && trackId >= 0) app.store.update(trackId, duration(), distance, "interrupted");
    app.main.removeCallbacks(ticker);
    if (active == this) {
      active = null;
      app.engine.recorder = null;
      app.engine.setRecording(false);
    }
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}

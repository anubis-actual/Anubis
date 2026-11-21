package com.anubis.app;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.util.Log;

/**
 * Foreground service ONLY while a Coyote is actually connected.
 * Must not be started from Activity.onCreate — on Android 14+ startForeground
 * with FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE throws if no device is linked yet.
 */
public class CoyoteService extends Service {
  public static final String CH = "coyote_live";
  public static final String ACTION_START = "com.anubis.app.FG_START";
  public static final String ACTION_STOP = "com.anubis.app.FG_STOP";
  static final String TAG = "CoyoteService";
  /** v4.31: true while the session service is in the foreground (for the overlay diagnostic line) */
  static volatile boolean running;

  @Override public void onCreate() {
    super.onCreate();
    try {
      if (Build.VERSION.SDK_INT >= 26) {
        NotificationChannel c = new NotificationChannel(CH, "Anubis session", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Keeps the Coyote link alive");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(c);
      }
    } catch (Throwable t) { CrashLog.write(this, t); }
  }

  @Override public int onStartCommand(Intent i, int f, int id) {
    String act = i != null ? i.getAction() : null;
    if (ACTION_STOP.equals(act)) {
      unlock(); running = false;
      OverlayController.hide();
      try { stopForeground(true); } catch (Throwable ignored) {}
      stopSelf();
      return START_NOT_STICKY;
    }
    // Only reach here when JS/native has an active BLE connection.
    try {
      Notification.Builder b = Build.VERSION.SDK_INT >= 26
        ? new Notification.Builder(this, CH) : new Notification.Builder(this);
      Notification n = b.setContentTitle("Anubis")
        .setContentText("Connected · session running")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setOngoing(true).build();
      if (Build.VERSION.SDK_INT >= 29) {
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
      } else {
        startForeground(7, n);
      }
    } catch (Throwable t) {
      // Android 14+: SecurityException if CONNECTED_DEVICE type used without a live link
      Log.e(TAG, "startForeground failed — stopping service", t);
      CrashLog.write(this, t);
      try { stopSelf(); } catch (Throwable ignored) {}
      return START_NOT_STICKY;
    }
    lock(); running = true;
    return START_NOT_STICKY; // never sticky-restart into another crash loop
  }

  /* v4.6: partial wake lock while a unit is connected so the CPU keeps running with the screen off / app in the background (released on stop/destroy) */
  private android.os.PowerManager.WakeLock wl;
  private void lock() {
    try {
      if (wl == null) {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "anubis:session");
      }
      if (wl != null && !wl.isHeld()) wl.acquire(12 * 60 * 60 * 1000L);
    } catch (Throwable t) { Log.e(TAG, "wakelock", t); }
  }
  private void unlock() { try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) {} }
  @Override public void onDestroy() { running = false; unlock(); OverlayController.hide(); super.onDestroy(); }
  @Override public IBinder onBind(Intent i) { return null; }
  @Override public void onTaskRemoved(Intent root) {
    unlock(); running = false;
    OverlayController.hide();
    try {
      CoyoteBle b = MainActivity.BLE_REF;
      if (b != null) { b.stopNow(); b.disconnect(); }
    } catch (Throwable ignored) {}
    try { stopForeground(true); } catch (Throwable ignored) {}
    stopSelf();
  }
}

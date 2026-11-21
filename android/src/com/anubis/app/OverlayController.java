package com.anubis.app;

import android.app.AppOpsManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Floating "A / B / STOP" bubble (v4.37: compact, see build()) drawn over other apps (TYPE_APPLICATION_OVERLAY).
 * Owned by the application context, not by the Activity, so it survives the Activity while a session runs.
 * Live values come from the last B0 the native writer actually sent (CoyoteBle.liveA/liveB, 10 writes/s on the main looper),
 * so they keep updating while the WebView is throttled in the background.
 * v4.30: never shown while Anubis is in the foreground (MainActivity.visible). STOP = native stopNow() + the page's full STOP.
 * A tap (not a drag) brings Anubis to the front on the screen that is sending output.
 * Test overlay ARMS a 15 s preview for 60 s; it appears only when the user leaves the app and then removes itself.
 */
final class OverlayController {
  private OverlayController() {}
  static final String PREFS = "anubis_overlay";
  private static final Handler H = new Handler(Looper.getMainLooper());
  private static Context app;
  private static WindowManager wm;
  private static Bubble bub;
  private static TextView ta, tb;
  private static WindowManager.LayoutParams lp;
  private static TextView tl;
  private static boolean test;
  private static final int TEST_MS = 15000, ARM_MS = 60000;
  private static volatile long armedUntil;
  /** screen the page says is sending output (live, draw, audio, patterns, trivia, remote, aitune, tconfig) */
  static volatile String mode = "live";
  static void setMode(String m) {
    if (m != null && m.matches("live|draw|audio|patterns|trivia|remote|aitune|tconfig")) mode = m;
  }
  /** true once, if Test was pressed less than 60 s ago (the preview is used up when it is shown) */
  static boolean takeArm() { boolean a = armedUntil > System.currentTimeMillis(); armedUntil = 0; return a; }

  // ---------- state ----------
  /** v4.37 bubble look (Settings › App › Appearance › Overlay), kept in the same prefs file. */
  static final int DEF_OP = 70;
  static void setStyle(final Context c, final String j) {
    if (Looper.myLooper() != Looper.getMainLooper()) { H.post(new Runnable() { public void run() { setStyle(c, j); } }); return; }
    try {
      org.json.JSONObject o = new org.json.JSONObject(j == null ? "{}" : j);
      Context x = c.getApplicationContext();
      SharedPreferences sp = x.getSharedPreferences(PREFS, 0);
      String sz = OverlayPolicy.sizeOf(o.optString("size", sp.getString("ts", "m")));
      int op = OverlayPolicy.opacityOf(o.optInt("op", sp.getInt("op", DEF_OP)));
      boolean st = o.optBoolean("stop", sp.getBoolean("stop", true));
      boolean same = sz.equals(sp.getString("ts", "m")) && op == sp.getInt("op", DEF_OP) && st == sp.getBoolean("stop", true);
      sp.edit().putString("ts", sz).putInt("op", op).putBoolean("stop", st).apply();
      if (!same) log("style", true, "Text " + sz.toUpperCase() + " · " + op + "% · STOP " + (st ? "on" : "off"));
      if (!same && bub != null && wm != null) restyle();
    } catch (Throwable t) { log("style", false, err(t)); }
  }
  /** a showing bubble is rebuilt in place with the new look (same position, same test timer) */
  private static void restyle() {
    try {
      Bubble old = bub;
      try { wm.removeViewImmediate(old); } catch (Throwable ignored) {}
      bub = build(); refresh(); wm.addView(bub, lp);
      H.postDelayed(new Runnable() { public void run() { clampNow(); } }, 100);
    } catch (Throwable t) { log("style", false, "Rebuild failed: " + err(t)); cleanup(); }
  }

  static boolean enabled(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, 0).getBoolean("enabled", false); }
  static void setEnabled(Context c, boolean on) { c.getApplicationContext().getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", on).apply(); }

  /** True when Android lets this package draw over other apps. On API 26-27 canDrawOverlays can lag, so the app-op is consulted too. */
  static boolean granted(Context c) {
    try {
      Context a = c.getApplicationContext();
      if (Settings.canDrawOverlays(a)) return true;
      if (Build.VERSION.SDK_INT < 28) {
        AppOpsManager ao = (AppOpsManager) a.getSystemService(Context.APP_OPS_SERVICE);
        if (ao != null) return ao.checkOpNoThrow("android:system_alert_window", Process.myUid(), a.getPackageName()) == AppOpsManager.MODE_ALLOWED;
      }
    } catch (Throwable ignored) {}
    return false;
  }

  static boolean connected() { CoyoteBle b = MainActivity.BLE_REF; return b != null && b.connected; }
  static boolean showing() { return bub != null; }
  private static int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.getResources().getDisplayMetrics())); }

  static String json(boolean ok, boolean granted, boolean enabled, String reason) { return json(ok, granted, enabled, reason, null); }
  static String json(boolean ok, boolean granted, boolean enabled, String reason, String msg) {
    return "{\"ok\":" + ok + ",\"granted\":" + granted + ",\"enabled\":" + enabled + ",\"reason\":\"" + (reason == null ? "" : reason) + "\"" + (msg == null ? "" : ",\"msg\":" + MainActivity.json(msg)) + "}";
  }
  /** v4.28: one step of the overlay path, shown in the page's Data log (and as a toast when ok is false). */
  static void log(String step, boolean ok, String msg) {
    try { MainActivity.emit("ovlog", "{\"step\":" + MainActivity.json(step) + ",\"ok\":" + ok + ",\"msg\":" + MainActivity.json(msg == null ? "" : msg) + "}"); } catch (Throwable ignored) {}
  }
  static String err(Throwable t) { return t == null ? "" : t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()); }

  // ---------- show / hide (always on the main thread) ----------
  static void show(final Context c, final boolean isTest) {
    if (Looper.myLooper() != Looper.getMainLooper()) { H.post(new Runnable() { public void run() { show(c, isTest); } }); return; }
    try {
      app = c.getApplicationContext();
      if (Demo.ENABLED && Demo.exhausted()) return;
      log(isTest ? "test requested" : "show requested", true, "Android " + Build.VERSION.RELEASE + " · SDK " + Build.VERSION.SDK_INT);
      if (!granted(app)) { log("permission check", false, "Display over other apps is not allowed for Anubis"); MainActivity.emit("overlay", json(false, false, enabled(app), "not_granted")); return; }
      log("diag", true, diagLineNow());
      if (MainActivity.visible) {
        /* v4.30: never over Anubis itself. A test is armed and shows when the user leaves the app. */
        if (isTest) { armedUntil = System.currentTimeMillis() + ARM_MS; log("test armed", true, "Leave Anubis within 60 s to see the bubble for 15 s"); }
        else log("not shown", true, "Anubis is open · the bubble shows when you leave the app");
        return;
      }
      if (bub != null) { if (isTest) { test = true; H.removeCallbacks(autoHide); H.postDelayed(autoHide, TEST_MS); } log("already showing", true, ""); return; }
      test = isTest;
      wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
      lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT);
      lp.gravity = Gravity.TOP | Gravity.START;
      SharedPreferences sp = app.getSharedPreferences(PREFS, 0);
      lp.x = sp.getInt("x", dp(12)); lp.y = sp.getInt("y", dp(150));
      bub = build();
      refresh();
      if (wm == null) { cleanup(); log("window manager", false, "No WindowManager"); MainActivity.emit("overlay", json(false, true, enabled(app), "attach_failed", "No WindowManager")); return; }
      try { wm.addView(bub, lp); log("addView", true, "TYPE_APPLICATION_OVERLAY added"); }
      catch (Throwable t) { cleanup(); try { CrashLog.write(app, t); } catch (Throwable ignored) {} log("addView", false, err(t)); MainActivity.emit("overlay", json(false, true, enabled(app), "attach_failed", err(t))); return; }
      H.postDelayed(new Runnable() { public void run() {
        Bubble v = bub;
        if (v != null && !v.isAttachedToWindow()) { cleanup(); log("attach check", false, "Window was not attached after 400 ms"); MainActivity.emit("overlay", json(false, true, enabled(app), "attach_failed", "not attached after 400 ms")); }
        else if (v != null) {
          clampNow();
          int vis = v.getWindowVisibility();
          log("attach check", vis == View.VISIBLE, vis == View.VISIBLE ? "Bubble visible at " + lp.x + "," + lp.y : "Attached but hidden by Android (visibility " + vis + ")");
          if (vis != View.VISIBLE) MainActivity.emit("overlay", json(false, true, enabled(app), "hidden", "visibility " + vis));
        }
      }}, 400);
      H.removeCallbacks(pump); H.postDelayed(pump, 250);
      if (test) { H.removeCallbacks(autoHide); H.postDelayed(autoHide, TEST_MS); }
    } catch (Throwable t) {
      try { CrashLog.write(c.getApplicationContext(), t); } catch (Throwable ignored) {}
      cleanup();
      log("show", false, err(t));
      MainActivity.emit("overlay", json(false, true, true, "attach_failed", err(t)));
    }
  }

  static void hide() {
    if (Looper.myLooper() != Looper.getMainLooper()) { H.post(new Runnable() { public void run() { hide(); } }); return; }
    cleanup();
  }

  private static void cleanup() {
    H.removeCallbacks(pump); H.removeCallbacks(autoHide);
    Bubble v = bub; bub = null; ta = tb = tl = null; stopV = null; test = false;
    if (v != null && wm != null) { try { wm.removeViewImmediate(v); } catch (Throwable ignored) {} }
  }

  /** end of a test preview: remove it; if the real bubble is due (enabled, connected, app in the background) it takes over */
  private static final Runnable autoHide = new Runnable() { public void run() { boolean wasTest = test; cleanup(); if (wasTest) { log("test ended", true, "Preview removed after 15 s"); MainActivity.overlayMaybeShow(); } } };

  private static final Runnable pump = new Runnable() {
    public void run() {
      if (bub == null) return;
      try {
        if (MainActivity.visible) { log("hidden", true, "Anubis is open"); cleanup(); return; }
        if (!granted(app)) { log("hidden", true, "Permission was turned off"); cleanup(); return; }
        if (!test && (!connected() || !granted(app) || (Demo.ENABLED && Demo.exhausted()))) { log("hidden", true, !connected() ? "Coyote disconnected" : !granted(app) ? "Permission was turned off" : "Demo ended"); cleanup(); return; }
        refresh();
      } catch (Throwable ignored) {}
      H.postDelayed(this, 250);
    }
  };

  /** v4.31: live output = what the B0 writer last sent on the ONE shared helper. No sample numbers ever:
   *  with no Coyote the bubble reads a grey "No Coyote · A – B –". */
  private static void refresh() {
    if (ta == null || tb == null) return;
    CoyoteBle c = MainActivity.BLE_REF;
    boolean live = c != null && c.connected;
    String[] t = OverlayPolicy.bubbleText(live, live ? c.liveA() : 0, live ? c.liveB() : 0);
    ta.setText(t[1]); tb.setText(t[2]);
    ta.setTextColor(Color.parseColor(live ? "#F0B24A" : GREY)); tb.setTextColor(Color.parseColor(live ? "#5AC8E0" : GREY));
    if (tl != null) { tl.setText(t[0].replace(" \u00b7", "")); tl.setVisibility(t[0].length() > 0 ? View.VISIBLE : View.GONE); }
  }
  private static final String GREY = "#8A93A6";

  /** v4.31 diagnostic: the facts that decide what the bubble shows, as JSON fields and as one readable line */
  static String diagFields() {
    CoyoteBle c = MainActivity.BLE_REF; MainActivity a = MainActivity.I; long now = System.currentTimeMillis();
    Context x = app != null ? app : (a != null ? a.getApplicationContext() : null);
    boolean g = x != null && granted(x), en = x != null && enabled(x);
    String line = OverlayPolicy.diagLine(c != null && c.connected, c != null && c.armed, c != null && c.wantRun, CoyoteBle.INSTANCES.get(),
      c != null && a != null && a.ble == c, c == null || c.outAt == 0 ? -1 : now - c.outAt, c == null ? 0 : c.outA, c == null ? 0 : c.outB,
      c == null ? 0 : c.tgtA, c == null ? 0 : c.tgtB, c == null ? -1 : now - c.lastJs, g, en, MainActivity.visible, CoyoteService.running, showing(), test, mode);
    return "\"bleRef\":" + (c != null) + ",\"armed\":" + (c != null && c.armed) + ",\"run\":" + (c != null && c.wantRun) + ",\"bleInstances\":" + CoyoteBle.INSTANCES.get()
      + ",\"lastSentAgeMs\":" + (c == null || c.outAt == 0 ? -1 : now - c.outAt) + ",\"outA\":" + (c == null ? 0 : c.outA) + ",\"outB\":" + (c == null ? 0 : c.outB)
      + ",\"visible\":" + MainActivity.visible + ",\"service\":" + CoyoteService.running + ",\"test\":" + test + ",\"mode\":" + MainActivity.json(mode)
      + ",\"line\":" + MainActivity.json(line);
  }
  static String diagLineNow() { String f = diagFields(); int i = f.indexOf("\"line\":"); try { return new org.json.JSONObject("{" + f + "}").optString("line"); } catch (Throwable t) { return f.substring(Math.max(0, i)); } }

  /** STOP on the bubble: zero on the wire first (native, instant), then the page runs its full STOP (same as the in-app STOP on A+B). */
  private static void stopPressed() {
    boolean ok = false;
    try { CoyoteBle c = MainActivity.BLE_REF; if (c != null) { c.stopNow(); ok = c.connected; } } catch (Throwable ignored) {}
    log("STOP", true, ok ? "Bubble STOP · 0 sent to the Coyote" : "Bubble STOP · no Coyote connected");
    MainActivity.emit("overlayStop", "{}");
    if (test) cleanup();
    else refresh();
  }

  /** A tap on the bubble body: bring Anubis to the front on the screen that is sending output. Direct startActivity is allowed
   *  from the background here because the app holds SYSTEM_ALERT_WINDOW and has a visible overlay window (the bubble that was tapped).
   *  If a ROM still refuses, a PendingIntent with background-activity-start allowed (API 34+) is tried. */
  private static void openApp() {
    final String m = mode;
    Intent i = new Intent(app, MainActivity.class);
    i.setAction(MainActivity.ACTION_FROM_OVERLAY);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    i.putExtra(MainActivity.EXTRA_MODE, m);
    try { app.startActivity(i); log("tap", true, "Opening Anubis · " + m); return; }
    catch (Throwable t) { log("tap", false, "startActivity: " + err(t) + " · trying PendingIntent"); }
    try {
      PendingIntent pi = PendingIntent.getActivity(app, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      if (Build.VERSION.SDK_INT >= 34) {
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        pi.send(app, 0, null, null, null, null, o.toBundle());
      } else pi.send();
      log("tap", true, "Opening Anubis via PendingIntent · " + m);
    } catch (Throwable t) { log("tap", false, "Could not open Anubis: " + err(t)); }
  }

  private static void clampNow() {
    try {
      if (bub == null || lp == null) return;
      DisplayMetrics dm = app.getResources().getDisplayMetrics();
      int nx = OverlayPolicy.clamp(lp.x, 0, dm.widthPixels - bub.getWidth());
      int ny = OverlayPolicy.clamp(lp.y, dp(24), dm.heightPixels - bub.getHeight() - dp(24));
      if (nx != lp.x || ny != lp.y) { lp.x = nx; lp.y = ny; wm.updateViewLayout(bub, lp); }
    } catch (Throwable ignored) {}
  }

  // ---------- view ----------
  /** v4.37 compact bubble: a rounded square about the size of a home-screen icon (52 / 60 / 72 dp for Small / Medium / Large text),
   *  A and B stacked in small text, semi-transparent (default 70 %). STOP is a separate round dot on the top-right corner
   *  (28 dp visible inside a 44 dp touch target at Medium, never below 85 % opacity) so it stays easy to hit; it can be switched off. */
  private static TextView stopV;
  private static Bubble build() {
    SharedPreferences sp = app.getSharedPreferences(PREFS, 0);
    String sz = OverlayPolicy.sizeOf(sp.getString("ts", "m"));
    int op = OverlayPolicy.opacityOf(sp.getInt("op", DEF_OP));
    boolean st = sp.getBoolean("stop", true);
    int[] d = OverlayPolicy.bubbleDims(sz);
    Bubble box = new Bubble(app);
    LinearLayout body = new LinearLayout(app);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setGravity(Gravity.CENTER);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(Color.parseColor("#F012253A")); bg.setCornerRadius(dp(d[4])); bg.setStroke(dp(1.5f), Color.parseColor("#F0B24A"));
    body.setBackground(bg);
    body.setPadding(dp(6), dp(4), dp(6), dp(4));
    body.setMinimumWidth(dp(d[1])); body.setMinimumHeight(dp(d[1]));
    body.setAlpha(op / 100f);
    tl = new TextView(app);
    tl.setText("No Coyote"); tl.setTextColor(Color.parseColor(GREY)); tl.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(8f, d[0] * 0.72f));
    tl.setGravity(Gravity.CENTER); tl.setIncludeFontPadding(false); tl.setVisibility(View.GONE);
    ta = readout("#F0B24A", d[0]); tb = readout("#5AC8E0", d[0]);
    body.addView(tl); body.addView(ta); body.addView(tb);
    FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
    if (st) { bl.topMargin = dp(d[3] / 2f); bl.rightMargin = dp(d[3] / 2f); }
    box.addView(body, bl);
    stopV = null;
    if (st) {
      TextView s = new TextView(app);
      s.setText("\u25A0"); s.setTextColor(Color.WHITE); s.setTextSize(TypedValue.COMPLEX_UNIT_DIP, d[2] * 0.42f);
      s.setGravity(Gravity.CENTER); s.setIncludeFontPadding(false); s.setClickable(true); s.setContentDescription("STOP");
      GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL);
      dot.setColor(Color.parseColor("#E5484D")); dot.setStroke(dp(1.5f), Color.WHITE);
      int in = dp((d[3] - d[2]) / 2f);
      s.setBackground(new InsetDrawable(dot, in));
      s.setAlpha(OverlayPolicy.dotAlpha(op));
      s.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stopPressed(); } });
      box.addView(s, new FrameLayout.LayoutParams(dp(d[3]), dp(d[3]), Gravity.TOP | Gravity.END));
      stopV = s;
    }
    return box;
  }

  private static TextView readout(String color, int sp) {
    TextView t = new TextView(app);
    t.setTextColor(Color.parseColor(color)); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    t.setGravity(Gravity.CENTER); t.setIncludeFontPadding(false); t.setSingleLine(true);
    t.setPadding(0, dp(1), 0, dp(1));
    return t;
  }

  /** Drags by its body; a quick tap on STOP still reaches the button (drag starts only past the touch slop, and can start on STOP). */
  private static final class Bubble extends FrameLayout {
    private float dx, dy; private int sx, sy; private boolean drag; private final int slop;
    /* v4.30: a drag starts past max(touch slop, 10 dp); anything shorter that ends with UP is a tap */
    Bubble(Context c) { super(c); slop = Math.max(ViewConfiguration.get(c).getScaledTouchSlop(), Math.round(10 * c.getResources().getDisplayMetrics().density)); }
    private void down(MotionEvent e) { dx = e.getRawX(); dy = e.getRawY(); sx = lp.x; sy = lp.y; drag = false; }
    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: down(e); return false;
        case MotionEvent.ACTION_MOVE:
          if (!drag && (Math.abs(e.getRawX() - dx) > slop || Math.abs(e.getRawY() - dy) > slop)) drag = true;
          return drag;
        default: return false;
      }
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: down(e); return true;
        case MotionEvent.ACTION_MOVE: {
          if (!drag && (Math.abs(e.getRawX() - dx) > slop || Math.abs(e.getRawY() - dy) > slop)) drag = true;
          if (drag && bub == this) {
            DisplayMetrics m = getResources().getDisplayMetrics();
            lp.x = OverlayPolicy.clamp(sx + Math.round(e.getRawX() - dx), 0, m.widthPixels - getWidth());
            lp.y = OverlayPolicy.clamp(sy + Math.round(e.getRawY() - dy), dp(24), m.heightPixels - getHeight() - dp(24));
            try { wm.updateViewLayout(this, lp); } catch (Throwable ignored) {}
          }
          return true;
        }
        case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
          if (drag) { try { app.getSharedPreferences(PREFS, 0).edit().putInt("x", lp.x).putInt("y", lp.y).apply(); } catch (Throwable ignored) {} log("moved", true, "Bubble at " + lp.x + "," + lp.y); }
          else if (e.getActionMasked() == MotionEvent.ACTION_UP && bub == this) openApp();
          drag = false; return true;
      }
      return true;
    }
  }
}

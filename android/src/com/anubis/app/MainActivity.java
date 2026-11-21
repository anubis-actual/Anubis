package com.anubis.app;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.media.*;
import android.media.projection.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/**
 * Startup order (critical on Android 14+/Samsung):
 *  1) setContentView(WebView) immediately — never block, never finish()
 *  2) load index.html
 *  3) only then, off the critical path, init BLE helper (NOT a foreground service)
 * Foreground service starts only after a real Coyote is connected.
 */
public class MainActivity extends Activity {
  static final String TAG = "CoyoteMain";
  /* v4.30: bubble tap → Anubis on the screen that is sending output */
  static final String ACTION_FROM_OVERLAY = "com.anubis.app.FROM_OVERLAY", EXTRA_MODE = "anubis.mode";
  volatile String pendingOpen;
  static MainActivity I;
  WebView w;
  CoyoteBridge bridge;
  CoyoteBle ble; // owned by Activity, not Service — lazy, never blocks UI
  FrameLayout root;
  volatile boolean ready;
  static final int REQ_PERM = 41, REQ_AUD = 43, REQ_FILE = 44, REQ_DIR = 45, REQ_REC = 46, REQ_CHOOSE = 47, REQ_AUDF = 48, REQ_SAVE = 49, REQ_CAM = 50;
  Runnable pending, pendingRec;
  /** v4.38: a Remote partner session is open (either side); keeps the background pump running without a Coyote */
  volatile boolean remoteActive;
  ValueCallback<Uri[]> fileCb;
  volatile boolean audioPickPending;
  Thread anTh;

  /* v4.49 volume-key emergency STOP (Settings › Session & safety). Foreground only: the Activity sees volume keys only while Anubis is on screen. */
  static final VolKey VOLK = new VolKey();
  final Handler volH = new Handler(Looper.getMainLooper());
  final Runnable volCheck = new Runnable() { public void run() {
    if (VOLK.due(SystemClock.uptimeMillis())) {
      try { CoyoteBle b = BLE_REF; if (b != null) b.stopNow(); } catch (Throwable ignored) {}
      emit("volStop", "{}");
    } else if (VOLK.held != 0) volH.postDelayed(this, 100);
  } };
  @Override public boolean dispatchKeyEvent(KeyEvent e) {
    try {
      int k = e.getKeyCode();
      if (VOLK.on && VolKey.isVol(k)) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
          boolean eat = VOLK.down(k, e.getRepeatCount(), e.getDownTime());
          if (e.getRepeatCount() == 0) { volH.removeCallbacks(volCheck); volH.postDelayed(volCheck, VolKey.HOLD_MS); }
          if (eat) return true;
        } else if (e.getAction() == KeyEvent.ACTION_UP) { VOLK.up(k, e.getEventTime()); volH.removeCallbacks(volCheck); }
      }
    } catch (Throwable ignored) {}
    return super.dispatchKeyEvent(e);
  }

  public static void emit(String e, String j) {
    final MainActivity a = I;
    if (a == null || a.w == null) return;
    final String ee = e, jj = j;
    a.runOnUiThread(new Runnable() {
      public void run() {
        try {
          a.w.evaluateJavascript("try{Native.on(" + json(ee) + "," + jj + ")}catch(x){}", null);
        } catch (Throwable ignored) {}
      }
    });
  }

  static String json(String s) {
    if (s == null) return "\"\"";
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    I = this;
    try { takeOverlayIntent(getIntent()); } catch (Throwable ignored) {}
    try { CrashLog.install(getApplicationContext()); } catch (Throwable ignored) {}
    APP = getApplicationContext();
    if (Demo.ENABLED && Demo.gate(this)) return;   // demo build only: exhausted -> native lock screen, the WebView is never built

    // --- 1) Visible UI first. Nothing else before this. ---
    try {
      w = new WebView(this);
      try { if (Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false); } catch (Throwable ignored) {}
      w.setBackgroundColor(Color.parseColor("#05080C"));
      root = new FrameLayout(this);
      root.setBackgroundColor(Color.parseColor("#05080C"));
      root.addView(w, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      setContentView(root);
      installInsets();
    } catch (Throwable t) {
      // Absolute last resort: plain TextView so the user sees something
      TextView tv = new TextView(this);
      tv.setTextColor(Color.WHITE);
      tv.setBackgroundColor(Color.BLACK);
      tv.setPadding(40, 80, 40, 40);
      tv.setText("Anubis failed to create WebView:\n" + t);
      setContentView(tv);
      CrashLog.write(this, t);
      return;
    }

    // --- 2) Configure WebView + load asset (still no BLE/service) ---
    try {
      WebSettings s = w.getSettings();
      s.setJavaScriptEnabled(true);
      s.setDomStorageEnabled(true);
      s.setAllowFileAccess(true);
      s.setAllowContentAccess(true);
      s.setMediaPlaybackRequiresUserGesture(false);
      if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
      bridge = new CoyoteBridge(this);
      w.addJavascriptInterface(bridge, "CoyoteNative");
      w.setWebChromeClient(new WebChromeClient() {
        @Override public void onPermissionRequest(PermissionRequest r) {
          try { r.grant(r.getResources()); } catch (Throwable ignored) {}
        }
        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
          return openChooser(cb);
        }
      });
      w.setWebViewClient(new WebViewClient() {
        @Override public void onPageFinished(WebView v, String url) {
          ready = true;
          final String po = pendingOpen; pendingOpen = null;
          if (po != null) emit("overlayOpen", "{\"mode\":" + json(po) + ",\"from\":\"overlay\"}");
          // Push any previous crash to JS so the user can copy it
          final String crash = CrashLog.read(MainActivity.this);
          if (crash != null && crash.length() > 0) {
            emit("crashlog", json(crash));
          }
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
      });
      w.loadUrl("file:///android_asset/index.html");
    } catch (Throwable t) {
      CrashLog.write(this, t);
      Log.e(TAG, "WebView setup failed", t);
    }

    // v4.17: no first-run record any more (the 72 h beta gate is gone)

    // Lazy BLE helper on a background thread after first frame — not a Service
    w.post(new Runnable() {
      public void run() {
        new Thread(new Runnable() {
          public void run() {
            try { ensureBle(); } catch (Throwable t) {
              CrashLog.write(MainActivity.this, t);
              Log.e(TAG, "BLE init failed — demo mode only", t);
              emit("error", "{\"msg\":\"Native BLE init failed; using Demo device\"}");
            }
          }
        }, "ble-init").start();
      }
    });
  }

  /** Create CoyoteBle helper in-process. Does NOT start a foreground service. */
  synchronized void ensureBle() {
    if (ble != null) return;
    if (Demo.ENABLED && Demo.exhausted()) return;
    /* v4.31: ONE helper per process. A re-created Activity re-uses the existing one (it may hold the live GATT) and takes over its events,
       so STOP, the watchdog and the bubble always talk to the connected helper. */
    synchronized (MainActivity.class) {
      CoyoteBle ex = BLE_REF;
      if (ex != null) { ble = ex; ex.L = bleListener(); OverlayController.log("bluetooth", true, "Re-using the existing Bluetooth helper · connected=" + ex.connected); return; }
    }
    try {
      ble = new CoyoteBle(getApplicationContext(), bleListener());
      BLE_REF = ble;
    } catch (Throwable t) {
      CrashLog.write(this, t);
      ble = null;
      throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
    }
  }

  CoyoteBle.Listener bleListener() {
    return new CoyoteBle.Listener() {
        public void on(String e, String j) {
          MainActivity.emit(e, j);
          // Promote to FGS only once we have a real connection
          /* v4.28: Android 15+ only lets a background app with "display over other apps" start a foreground service once its
             overlay window is visible, so when the link comes up while Anubis is in the background the bubble goes first. */
          if ("ready".equals(e) || "connected".equals(e)) {
            if (visible) { startFgSafe(); overlayMaybeShow(); }
            else { overlayMaybeShow(); bgH.postDelayed(new Runnable() { public void run() { startFgSafe(); } }, 600); }
          }
          if ("disconnected".equals(e)) { stopFgSafe(); OverlayController.hide(); }
        }
      };
  }

  static Context APP;
  static volatile CoyoteBle BLE_REF;
  /** demo build: true while the phone is actually being driven (BLE connected and a non-zero target on A or B) */
  static boolean drivingNow() { CoyoteBle b = BLE_REF; return b != null && b.connected && (b.tgtA != 0 || b.tgtB != 0); }
  /** demo build, on exhaustion: everything to zero, BLE + service + overlay + audio capture off (works with or without a live activity) */
  static void demoKill() {
    try { CoyoteBle b = BLE_REF; if (b != null) { b.stopNow(); b.disconnect(); } } catch (Throwable ignored) {}
    try {
      if (APP != null) { Intent i = new Intent(APP, CoyoteService.class); i.setAction(CoyoteService.ACTION_STOP); APP.startService(i); }
    } catch (Throwable ignored) {}
    try { OverlayController.hide(); } catch (Throwable ignored) {}
    MainActivity a = I;
    if (a != null) { try { a.stopAudioCapture(); } catch (Throwable ignored) {} }
  }
  /** demo build: drop the WebView (and with it every page script) before the lock screen is shown */
  void demoTearDown() {
    bgStop();
    try { if (w != null) { w.loadUrl("about:blank"); if (root != null) root.removeView(w); w.destroy(); } } catch (Throwable ignored) {}
    w = null;
  }

  CoyoteBle bleOrNull() {
    try { ensureBle(); } catch (Throwable ignored) {}
    return ble;
  }

  void startFgSafe() {
    try {
      Intent i = new Intent(this, CoyoteService.class);
      i.setAction(CoyoteService.ACTION_START);
      if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
      else startService(i);
    } catch (Throwable t) {
      Log.e(TAG, "startFgSafe", t);
      CrashLog.write(this, t);
      OverlayController.log("foreground service", false, "Could not start the session service: " + OverlayController.err(t));
    }
  }

  void startFg() { startFgSafe(); } // bridge compat

  void stopFgSafe() {
    try {
      Intent i = new Intent(this, CoyoteService.class);
      i.setAction(CoyoteService.ACTION_STOP);
      startService(i);
    } catch (Throwable ignored) {}
  }

  
  String permStateJson() {
    boolean scan=true, conn=true, loc=true, notif=true;
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        scan = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        conn = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
      }
      loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
         || Build.VERSION.SDK_INT >= 31; // not required with neverForLocation on 12+
      if (Build.VERSION.SDK_INT >= 33)
        notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    } catch (Throwable ignored) {}
    boolean locSvc = true;
    try {
      int mode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
      locSvc = mode != Settings.Secure.LOCATION_MODE_OFF;
    } catch (Throwable ignored) {}
    return "{\"scan\":"+scan+",\"connect\":"+conn+",\"locationPerm\":"+loc+",\"locationOn\":"+locSvc+",\"notifications\":"+notif+",\"sdk\":"+Build.VERSION.SDK_INT+"}";
  }


  void ensurePermsThen(Runnable r) {
    List<String> need = new ArrayList<String>();
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_SCAN);
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_CONNECT);
      } else {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
      }
      if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        need.add(Manifest.permission.POST_NOTIFICATIONS);
    } catch (Throwable t) { CrashLog.write(this, t); }
    if (need.isEmpty()) { try { r.run(); } catch (Throwable t) { CrashLog.write(this, t); } }
    else { pending = r; try { askPerms(need.toArray(new String[0]), REQ_PERM); } catch (Throwable t) { CrashLog.write(this, t); } }
  }

  @Override public void onRequestPermissionsResult(int c, String[] p, int[] g) {
    if (c == REQ_CAM) {
      boolean ok = g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED;
      emit("camPerm", "{\"ok\":" + ok + "}");
      return;
    }
    if (c == REQ_REC) {
      Runnable r = pendingRec; pendingRec = null;
      boolean ok = g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED;
      if (ok && r != null) { try { r.run(); } catch (Throwable t) { CrashLog.write(this, t); } }
      else emit("audio", "{\"ok\":false,\"msg\":\"Microphone / audio permission was denied. Allow it in Settings → Apps → Anubis → Permissions to use Mic or Phone audio.\"}");
      return;
    }
    if (c == REQ_PERM && pending != null) {
      boolean ok = true;
      for (int x : g) if (x != PackageManager.PERMISSION_GRANTED) ok = false;
      Runnable r = pending; pending = null;
            if (ok) {
        emit("log", "{\"msg\":\"Permissions granted - starting requested BLE action\"}");
        try { r.run(); } catch (Throwable t) { CrashLog.write(this, t); }
      } else {
        emit("log", "{\"msg\":\"Permission DENIED - enable Bluetooth permissions in system Settings\"}");
        emit("error", "{\"msg\":\"permission denied\"}");
      }
    }
  }

  /* v4.6: ask Android (and Samsung) not to restrict Anubis in the background so output keeps running when another full-screen app is in front */
  public boolean battOk() {
    try {
      PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
      return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    } catch (Throwable t) { return true; }
  }
  public void requestBatt() {
    try {
      if (battOk()) return;
      startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
    } catch (Throwable t) {
      try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
      catch (Throwable t2) { CrashLog.write(this, t2); }
    }
  }

  // ---------------- Overlay (display over other apps) ----------------
  /** true after the user was sent to Android's overlay settings; consumed when they come back */
  volatile boolean pendingOverlayReq, overlayLeft;
  static volatile boolean visible;

  /** Show the bubble if the user enabled it, Android granted it, a Coyote is connected and the app is in the background. */
  static void overlayMaybeShow() {
    try {
      Context c = APP; if (c == null) return;
      boolean en = OverlayController.enabled(c), g = OverlayController.granted(c), cn = OverlayController.connected();
      /* v4.30: an armed Test preview shows once, the first time the app is left within 60 s (live numbers if connected, else "demo") */
      if (!visible && g && OverlayController.takeArm()) { OverlayController.show(c, true); return; }
      if (OverlayPolicy.shouldShow(en, g, cn, visible)) OverlayController.show(c, false);
      else if (!visible && en) OverlayController.log("not shown", !g ? false : true, !g ? "Android permission is off" : !cn ? "No Coyote connected" : "");
    } catch (Throwable t) { OverlayController.log("not shown", false, OverlayController.err(t)); }
  }

  /** Toggle on: already granted -> enabled right away; otherwise open Android's settings and wait for the user to come back. */
  public void requestOverlay() {
    try {
      if (OverlayController.granted(this)) {
        pendingOverlayReq = false; overlayLeft = false;
        OverlayController.setEnabled(this, true);
        emit("overlay", OverlayController.json(true, true, true, ""));
        return;
      }
      pendingOverlayReq = true; overlayLeft = false;
      OverlayController.log("permission", true, "Opening Android settings" + (sideloaded() ? " · installed from a file, so Android may mark it Restricted" : ""));
      if (!launchOverlaySettings()) {
        pendingOverlayReq = false;
        OverlayController.log("open settings", false, "No settings screen could be opened");
        emit("overlay", OverlayController.json(false, false, false, "no_settings"));
      }
    } catch (Throwable t) { CrashLog.write(this, t); pendingOverlayReq = false; OverlayController.log("permission", false, OverlayController.err(t)); emit("overlay", OverlayController.json(false, false, false, "error", OverlayController.err(t))); }
  }

  /** The "Open settings" link after a denial: same flow, so granting there also turns the overlay on when the user returns. */
  public void openOverlaySettings() {
    try {
      if (OverlayController.granted(this)) { syncOverlay(false); return; }
      pendingOverlayReq = true; overlayLeft = false;
      if (!launchOverlaySettings()) { pendingOverlayReq = false; emit("overlay", OverlayController.json(false, false, false, "no_settings")); }
    } catch (Throwable t) { CrashLog.write(this, t); }
  }

  /** startActivity only (no result code is delivered for this screen). Falls back to the plain list, then App info. */
  boolean launchOverlaySettings() {
    Uri pk = Uri.parse("package:" + getPackageName());
    Intent[] tries = new Intent[] {
      new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pk),
      new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
      new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pk)
    };
    String[] names = {"overlay settings for Anubis", "overlay settings list", "App info"};
    for (int k = 0; k < tries.length; k++) {
      try { startActivity(tries[k]); OverlayController.log("open settings", true, names[k]); return true; }
      catch (Throwable t) { OverlayController.log("open settings", false, names[k] + ": " + OverlayController.err(t)); }
    }
    return false;
  }

  /** v4.28: App info, where Android 15+ puts "Allow restricted settings" (⋮ menu) for apps installed from a file. */
  public void openAppInfo() {
    pendingOverlayReq = true; overlayLeft = false;
    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); OverlayController.log("open App info", true, ""); }
    catch (Throwable t) { pendingOverlayReq = false; OverlayController.log("open App info", false, OverlayController.err(t)); emit("overlay", OverlayController.json(false, OverlayController.granted(this), false, "no_settings")); }
  }

  /** v4.28: true when Android recorded this APK as installed from a downloaded or local file (Android 13+), the case that Android 15+ "restricted settings" blocks. */
  boolean sideloaded() {
    try {
      if (Build.VERSION.SDK_INT >= 33) {
        int src = getPackageManager().getInstallSourceInfo(getPackageName()).getPackageSource();
        return src == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || src == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE;
      }
    } catch (Throwable ignored) {}
    return false;
  }

  /** v4.28: everything the overlay depends on, for the page's diagnostics line and the Data log. */
  String overlayDiagJson() {
    String inst = "";
    try { if (Build.VERSION.SDK_INT >= 30) { String ip = getPackageManager().getInstallSourceInfo(getPackageName()).getInstallingPackageName(); inst = ip == null ? "" : ip; } } catch (Throwable ignored) {}
    return "{\"sdk\":" + Build.VERSION.SDK_INT + ",\"release\":" + json(Build.VERSION.RELEASE) + ",\"maker\":" + json(Build.MANUFACTURER) + ",\"model\":" + json(Build.MODEL)
      + ",\"granted\":" + OverlayController.granted(this) + ",\"enabled\":" + OverlayController.enabled(this) + ",\"connected\":" + OverlayController.connected()
      + ",\"showing\":" + OverlayController.showing() + ",\"sideloaded\":" + sideloaded() + ",\"installer\":" + json(inst)
      + "," + OverlayController.diagFields() + "}";
  }

  /** Toggle off (or on, which goes through the permission flow). Turning off never touches Android's grant; it hides the bubble and clears the saved flag. */
  public void setOverlayEnabled(boolean on) {
    if (on) { requestOverlay(); return; }
    pendingOverlayReq = false; overlayLeft = false;
    OverlayController.setEnabled(this, false);
    OverlayController.hide();
    emit("overlay", OverlayController.json(false, OverlayController.granted(this), false, ""));
  }

  /** {granted, enabled, canShow}; a saved "enabled" with the permission gone is cleared here too. */
  String overlayStateJson() {
    boolean g = OverlayController.granted(this), en = OverlayController.enabled(this);
    if (en && !g && !(pendingOverlayReq && overlayLeft)) { en = false; OverlayController.setEnabled(this, false); }
    return "{\"granted\":" + g + ",\"enabled\":" + (en && g) + ",\"switchOn\":" + en + ",\"connected\":" + OverlayController.connected() + ",\"canShow\":" + (en && g && !(Demo.ENABLED && Demo.exhausted())) + ",\"waiting\":" + pendingOverlayReq + "}";
  }

  /** Re-check after returning from Android settings / on focus; emits the truth so the page always matches. */
  void syncOverlay(boolean allowRetry) {
    try {
      boolean g = OverlayController.granted(this);
      boolean pend = pendingOverlayReq && overlayLeft;
      if (pend && !g && allowRetry && Build.VERSION.SDK_INT <= 27) {   // API 26-27: canDrawOverlays can read false for a moment after a grant
        bgH.postDelayed(new Runnable() { public void run() { syncOverlay(false); } }, 400);
        return;
      }
      /* v4.28: some phones (seen on Samsung) report the new grant a moment after the user comes back, so check once more before calling it denied */
      if (pend && !g && allowRetry) {
        bgH.postDelayed(new Runnable() { public void run() { syncOverlay(false); } }, 700);
        return;
      }
      if (pend) OverlayController.log("back from settings", g, g ? "Permission allowed" : "Permission still off" + (sideloaded() ? " · Android may have blocked it as a restricted setting" : ""));
      String[] r = OverlayPolicy.reconcile(OverlayController.enabled(this), g, pend);
      boolean en = "1".equals(r[0]);
      if (pend) { pendingOverlayReq = false; overlayLeft = false; }
      if (pend || en != OverlayController.enabled(this)) OverlayController.setEnabled(this, en);
      String why = r[1];
      if ("denied".equals(why) && sideloaded()) why = "restricted";
      emit("overlay", OverlayController.json(en && g, g, en, why));
    } catch (Throwable t) { CrashLog.write(this, t); OverlayController.log("sync", false, OverlayController.err(t)); }
  }

  // ---------------- Insets (Android 15 forces edge-to-edge): pad the root so the web UI is never under system bars ----------------
  void installInsets() {
    root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
        int l, t, r, b;
        if (Build.VERSION.SDK_INT >= 30) {
          android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          android.graphics.Insets ime = in.getInsets(WindowInsets.Type.ime());
          l = bars.left; t = bars.top; r = bars.right; b = Math.max(bars.bottom, ime.bottom);
        } else {
          l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop(); r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
        }
        v.setPadding(l, t, r, b);
        return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : in.consumeSystemWindowInsets();
      }
    });
    root.requestApplyInsets();
  }

  // ---------------- File chooser (real Android document picker; no share/chooser sheet) ----------------
  boolean openChooser(ValueCallback<Uri[]> cb) {
    try {
      if (fileCb != null) { try { fileCb.onReceiveValue(null); } catch (Throwable ignored) {} }
      fileCb = cb;
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("*/*");
      i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*", "*/*"});
      startActivityForResult(i, audioPickPending ? REQ_AUDF : REQ_CHOOSE);
      return true;
    } catch (Throwable t) {
      CrashLog.write(this, t);
      fileCb = null;
      try { cb.onReceiveValue(null); } catch (Throwable ignored) {}
      emit("audioErr", "{\"msg\":\"Android has no file picker available on this phone.\"}");
      return false;
    }
  }

  void finishChooser(Uri u) {
    ValueCallback<Uri[]> cb = fileCb; fileCb = null;
    if (cb != null) { try { cb.onReceiveValue(u == null ? null : new Uri[]{u}); } catch (Throwable ignored) {} }
  }

  String[] queryNameSize(Uri u) {
    String name = null, size = "0";
    Cursor c = null;
    try {
      c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE}, null, null, null);
      if (c != null && c.moveToFirst()) {
        name = c.getString(0);
        if (!c.isNull(1)) size = String.valueOf(c.getLong(1));
      }
    } catch (Throwable ignored) {} finally { try { if (c != null) c.close(); } catch (Throwable ignored) {} }
    if (name == null) { String ls = u.getLastPathSegment(); name = ls == null ? "audio" : ls; }
    return new String[]{name, size};
  }

  /** Analyze a file or folder document natively (streaming), reporting progress/done/error events. */
  public void analyzeUri(final String uriStr, String nameHint) {
    try {
      final Uri u = Uri.parse(uriStr);
      final String[] ns = queryNameSize(u);
      final String name = (nameHint != null && nameHint.length() > 0) ? nameHint : ns[0];
      try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) {}
      cancelAnalyze();
      emit("audioProg", "{\"p\":0,\"name\":" + json(name) + "}");
      anTh = new Thread(new Runnable() {
        public void run() {
          try {
            String meta = AudioAnalyzer.analyze(MainActivity.this, u, name);
            emit("audioDone", meta.substring(0, meta.length() - 1) + ",\"name\":" + json(name) + ",\"size\":" + ns[1] + "}");
          } catch (Throwable t) {
            String m = t.getMessage() == null ? AudioAnalyzer.human(t, null) : t.getMessage();
            if (t instanceof OutOfMemoryError) m = AudioAnalyzer.human(t, null);
            if (!"Cancelled".equals(m)) emit("audioErr", "{\"msg\":" + json(m) + ",\"name\":" + json(name) + "}");
          }
        }
      }, "aud-an");
      anTh.start();
    } catch (Throwable t) { CrashLog.write(this, t); emit("audioErr", "{\"msg\":" + json(AudioAnalyzer.human(t, null)) + "}"); }
  }

  public void cancelAnalyze() {
    AudioAnalyzer.cancel = true;
    try { if (anTh != null && anTh.isAlive()) anTh.join(1500); } catch (Throwable ignored) {}
    AudioAnalyzer.cancel = false;
  }

  // ---------------- Folder (SAF tree) browsing ----------------
  public void releaseTree(String tree) {
    try { getContentResolver().releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) {}
  }

  public boolean treeOk(String tree) {
    try {
      for (UriPermission p : getContentResolver().getPersistedUriPermissions())
        if (p.getUri().toString().equals(tree) && p.isReadPermission()) return true;
    } catch (Throwable ignored) {}
    return false;
  }

  static boolean mediaName(String n) {
    String l = n.toLowerCase(Locale.US);
    int d = l.lastIndexOf('.'); if (d < 0) return false;
    String e = l.substring(d + 1);
    return ",mp3,m4a,aac,wav,flac,ogg,oga,opus,wma,mka,amr,3gp,mid,mp4,m4v,mkv,webm,mov,avi,ts,hwl,".contains("," + e + ",");
  }

  public String listDir(String tree, String docId) {
    Cursor c = null;
    try {
      Uri t = Uri.parse(tree);
      String id = (docId == null || docId.length() == 0) ? DocumentsContract.getTreeDocumentId(t) : docId;
      Uri ch = DocumentsContract.buildChildDocumentsUriUsingTree(t, id);
      c = getContentResolver().query(ch, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
      List<String[]> dirs = new ArrayList<String[]>(), files = new ArrayList<String[]>();
      int hidden = 0;
      while (c != null && c.moveToNext()) {
        String did = c.getString(0), nm = c.getString(1), mime = c.getString(2);
        String sz = c.isNull(3) ? "0" : String.valueOf(c.getLong(3));
        if (nm == null) nm = did;
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) dirs.add(new String[]{did, nm, "1", "0"});
        else if ((mime != null && (mime.startsWith("audio/") || mime.startsWith("video/"))) || mediaName(nm)) files.add(new String[]{did, nm, "0", sz});
        else hidden++;
      }
      Comparator<String[]> cmp = new Comparator<String[]>() { public int compare(String[] a, String[] b) { return a[1].compareToIgnoreCase(b[1]); } };
      Collections.sort(dirs, cmp); Collections.sort(files, cmp);
      StringBuilder sb = new StringBuilder("{\"ok\":true,\"id\":" + json(id) + ",\"hidden\":" + hidden + ",\"items\":[");
      boolean first = true;
      List<String[]> all = new ArrayList<String[]>(dirs); all.addAll(files);
      for (String[] r : all) {
        if (!first) sb.append(","); first = false;
        sb.append("{\"id\":" + json(r[0]) + ",\"name\":" + json(r[1]) + ",\"dir\":" + r[2] + ",\"size\":" + r[3] + "}");
      }
      return sb.append("]}").toString();
    } catch (SecurityException se) {
      return "{\"ok\":false,\"msg\":\"Access to this folder was revoked. Choose the folder again.\"}";
    } catch (Throwable t) {
      return "{\"ok\":false,\"msg\":" + json("Couldn't list this folder: " + t.getMessage()) + "}";
    } finally { try { if (c != null) c.close(); } catch (Throwable ignored) {} }
  }

  public String docUri(String tree, String docId) {
    try { return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), docId).toString(); } catch (Throwable t) { return ""; }
  }

  // ---------------- Live audio: Phone audio (MediaProjection) and Mic ----------------
  void ensureRecordAudio(Runnable r) {
    try {
      if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { r.run(); return; }
      pendingRec = r;
      askPerms(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_REC);
    } catch (Throwable t) { CrashLog.write(this, t); emit("audio", "{\"ok\":false,\"msg\":" + json(AudioCapture.human(t)) + "}"); }
  }

  public void requestAudioCapture() {
    try {
      if (Build.VERSION.SDK_INT < 29) {
        emit("audio", "{\"ok\":false,\"src\":\"phone\",\"msg\":\"Phone audio capture needs Android 10 or newer. Use Mic instead.\"}");
        return;
      }
      ensureRecordAudio(new Runnable() { public void run() { launchConsent(); } });
    } catch (Throwable t) { CrashLog.write(this, t); emit("audio", "{\"ok\":false,\"src\":\"phone\",\"msg\":" + json(AudioCapture.human(t)) + "}"); }
  }

  void launchConsent() {
    try {
      MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
      Intent ci = Build.VERSION.SDK_INT >= 34
        ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        : mpm.createScreenCaptureIntent();
      startActivityForResult(ci, REQ_AUD);
    } catch (Throwable t) { CrashLog.write(this, t); emit("audio", "{\"ok\":false,\"src\":\"phone\",\"msg\":" + json(AudioCapture.human(t)) + "}"); }
  }

  public void startMic() {
    ensureRecordAudio(new Runnable() { public void run() { startCaptureService(CaptureService.ACT_MIC, 0, null); } });
  }

  void startCaptureService(String act, int code, Intent data) {
    try {
      Intent i = new Intent(this, CaptureService.class);
      i.setAction(act);
      if (data != null) { i.putExtra("code", code); i.putExtra("data", data); }
      if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    } catch (Throwable t) {
      CrashLog.write(this, t);
      emit("audio", "{\"ok\":false,\"msg\":" + json(AudioCapture.human(t)) + "}");
    }
  }

  public void stopAudioCapture() {
    try { AudioCapture.running = false; } catch (Throwable ignored) {}
    CaptureService s = CaptureService.I;
    if (s != null) { try { s.shutdown(); } catch (Throwable ignored) {} }
  }

  public String audioLevelsJson() { return AudioCapture.json(); }

  public void shareText(String name, String mime, String body) {
    try {
      Intent i = new Intent(Intent.ACTION_SEND);
      i.setType(mime == null ? "text/plain" : mime);
      i.putExtra(Intent.EXTRA_SUBJECT, name);
      i.putExtra(Intent.EXTRA_TEXT, body);
      startActivity(Intent.createChooser(i, "Share " + name));
    } catch (Exception e) { emit("error", "{\"msg\":" + json(e.getMessage()) + "}"); }
  }

  public void pickBackup() {
    try {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      /* v4.28: files shared to Drive or Downloads are often typed text/plain or octet-stream, so offer those too */
      i.setType("*/*");
      i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
      startActivityForResult(i, REQ_FILE);
    } catch (Throwable t) { CrashLog.write(this, t); emit("backupErr", "{\"msg\":" + json(OverlayController.err(t)) + "}"); }
  }

  /** v4.28 Save profile: Android's "save as" screen, then the text is written straight to the chosen file (no share-sheet size limit). */
  String pendingSave;
  public void saveFile(String name, String mime, String body) {
    try {
      pendingSave = body;
      Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType(mime == null ? "application/json" : mime);
      i.putExtra(Intent.EXTRA_TITLE, name);
      startActivityForResult(i, REQ_SAVE);
    } catch (Throwable t) { pendingSave = null; CrashLog.write(this, t); emit("saved", "{\"ok\":false,\"msg\":" + json(OverlayController.err(t)) + "}"); }
  }

  public void pickAudioFolder() {
    try {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      startActivityForResult(i, REQ_DIR);
    }
    catch (Throwable t) { CrashLog.write(this, t); }
  }

  @Override protected void onActivityResult(int c, int r, Intent d) {
    super.onActivityResult(c, r, d);
    try {
      if (c == REQ_AUD) {
        if (r == RESULT_OK && d != null) startCaptureService(CaptureService.ACT_PHONE, r, d);
        else emit("audio", "{\"ok\":false,\"src\":\"phone\",\"denied\":true,\"msg\":\"You declined Android's permission prompt, so phone audio can't start. Tap Start to try again, or use Mic.\"}");
      } else if (c == REQ_CHOOSE) {
        finishChooser(r == RESULT_OK && d != null ? d.getData() : null);
      } else if (c == REQ_AUDF) {
        audioPickPending = false;
        finishChooser(null);
        if (r == RESULT_OK && d != null && d.getData() != null) {
          Uri u = d.getData();
          String[] ns = queryNameSize(u);
          emit("picked", "{\"uri\":" + json(u.toString()) + ",\"name\":" + json(ns[0]) + ",\"size\":" + ns[1] + "}");
          analyzeUri(u.toString(), ns[0]);
        }
      } else if (c == REQ_SAVE) {
        String body = pendingSave; pendingSave = null;
        if (r != RESULT_OK || d == null || d.getData() == null || body == null) { emit("saved", "{\"ok\":false,\"cancel\":true}"); return; }
        try (OutputStream os = getContentResolver().openOutputStream(d.getData(), "wt")) {
          os.write(body.getBytes("UTF-8")); os.flush();
          emit("saved", "{\"ok\":true,\"name\":" + json(queryNameSize(d.getData())[0]) + ",\"bytes\":" + body.length() + "}");
        } catch (Throwable we) { CrashLog.write(this, we); emit("saved", "{\"ok\":false,\"msg\":" + json(OverlayController.err(we)) + "}"); }
      } else if (c == REQ_FILE && r == RESULT_OK && d != null) {
        try {
          InputStream in = getContentResolver().openInputStream(d.getData());
          ByteArrayOutputStream o = new ByteArrayOutputStream();
          byte[] buf = new byte[16384]; int n;
          while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
          in.close();
          emit("backup", json(o.toString("UTF-8")));
        } catch (Throwable re) { CrashLog.write(this, re); emit("backupErr", "{\"msg\":" + json(OverlayController.err(re)) + "}"); }
      } else if (c == REQ_DIR && r == RESULT_OK && d != null) {
        int take = d.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (take == 0) take = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try { getContentResolver().takePersistableUriPermission(d.getData(), take); } catch (Throwable pt) { CrashLog.write(this, pt); }
        emit("audioDir", "{\"uri\":" + json(d.getData().toString()) + "}");
      }
    } catch (Throwable t) { CrashLog.write(this, t); }
  }

  /* v4.22 Android Back: the page reports (setBackActive) when it has something to back out of — a settings page, a Pawprints tab,
     an open sheet. Then Back asks window.anubisBack(), which closes one level and returns true. Anything it does not consume keeps the
     old behaviour. SDK 33+ (and Android 16/17 predictive back, where onBackPressed is no longer called) uses OnBackInvokedDispatcher. */
  boolean backActive = false; Object backCb = null;
  void setBackActive(boolean on) {
    backActive = on;
    if (Build.VERSION.SDK_INT >= 33) {
      try {
        if (on && backCb == null) {
          android.window.OnBackInvokedCallback cb = new android.window.OnBackInvokedCallback() { public void onBackInvoked() { pageBack(true); } };
          getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb); backCb = cb;
        } else if (!on && backCb != null) {
          getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCb); backCb = null;
        }
      } catch (Throwable t) { Log.w(TAG, "back callback", t); }
    }
  }
  void pageBack(final boolean modern) {
    if (w == null) { oldBack(modern); return; }
    try {
      w.evaluateJavascript("(function(){try{return !!(window.anubisBack&&window.anubisBack())}catch(e){return false}})()", new ValueCallback<String>() {
        public void onReceiveValue(String v) { if (!"true".equals(v)) oldBack(modern); }
      });
    } catch (Throwable t) { oldBack(modern); }
  }
  void oldBack(boolean modern) {
    if (w != null && w.canGoBack()) { w.goBack(); return; }
    if (modern) moveTaskToBack(true); else super.onBackPressed();
  }
  @Override public void onBackPressed() { if (backActive && w != null) pageBack(false); else oldBack(false); }
  /* v4.6 background keep-alive. Once the activity is hidden Android throttles WebView timers to ~1/s, which starved the 1 s native watchdog and zeroed the output
     as soon as the user switched apps. While hidden we call window.bgTick() every 100 ms (evaluateJavascript is not throttled). bgTick runs the missed engine ticks and
     pushes the output; its returned "1" is the liveness proof that feeds the watchdog. If the page hangs the call never returns, the heartbeat goes stale and the
     watchdog zeroes the output exactly as before. onDestroy still zeroes. */
  final Handler bgH = new Handler(Looper.getMainLooper());
  volatile boolean bgOn, bgPending;
  final Runnable bgPump = new Runnable() {
    public void run() {
      if (!bgOn) return;
      try {
        final CoyoteBle b = ble;
        if (((b != null && b.connected) || remoteActive) && w != null && !bgPending) {
          bgPending = true;
          w.evaluateJavascript("(window.bgTick?bgTick():0)", new ValueCallback<String>() {
            public void onReceiveValue(String v) {
              bgPending = false;
              if ("1".equals(v)) { CoyoteBle b2 = ble; if (b2 != null && b2.connected) b2.heartbeat(); }
            }
          });
        }
      } catch (Throwable t) { bgPending = false; }
      bgH.postDelayed(this, 100);
    }
  };
  void bgStart() {
    try { if (ble != null) ble.bg = true; } catch (Throwable ignored) {}
    if (bgOn) return;
    bgOn = true; bgPending = false;
    bgH.postDelayed(bgPump, 100);
  }
  void bgStop() {
    bgOn = false; bgPending = false;
    bgH.removeCallbacks(bgPump);
    try { if (ble != null) ble.bg = false; } catch (Throwable ignored) {}
  }
  /** v4.30: an Intent from the bubble carries the screen to show; the page switches to it (now, or once it has loaded) */
  boolean takeOverlayIntent(Intent i) {
    if (i == null || !ACTION_FROM_OVERLAY.equals(i.getAction())) return false;
    String m = i.getStringExtra(EXTRA_MODE); if (m == null) m = "live";
    i.setAction(Intent.ACTION_MAIN);
    if (ready) emit("overlayOpen", "{\"mode\":" + json(m) + ",\"from\":\"overlay\"}"); else pendingOpen = m;
    OverlayController.log("back in Anubis", true, "From the bubble · " + m);
    return true;
  }
  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); try { takeOverlayIntent(i); } catch (Throwable t) { CrashLog.write(this, t); } }
  /** Home or Recents pressed: the bubble may show right away (onStop can come late, or not at all, on the Recents screen) */
  @Override protected void onUserLeaveHint() {
    super.onUserLeaveHint();
    if (OverlayPolicy.skipLeave(internalLaunch)) { OverlayController.log("not shown", true, "Anubis opened a system screen"); return; }
    visible = false; overlayMaybeShow();
  }
  /** v4.31: set while Anubis itself has a system screen open (picker, permission dialog, Save as, share sheet, Android settings):
   *  the app counts as still open, so the bubble does not appear over those. Cleared on the next onResume. */
  static volatile boolean internalLaunch;
  @Override public void startActivityForResult(Intent i, int code, Bundle o) { internalLaunch = true; super.startActivityForResult(i, code, o); }
  /** v4.38: camera for scanning a partner's invite QR (asked only when the user taps Scan QR) */
  boolean camOk() {
    try { return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; } catch (Throwable t) { return false; }
  }
  void requestCamera() {
    if (camOk()) { emit("camPerm", "{\"ok\":true}"); return; }
    try { askPerms(new String[]{Manifest.permission.CAMERA}, REQ_CAM); } catch (Throwable t) { CrashLog.write(this, t); emit("camPerm", "{\"ok\":false}"); }
  }
  void askPerms(String[] p, int code) { internalLaunch = true; requestPermissions(p, code); }
  @Override protected void onStart() { super.onStart(); visible = true; OverlayController.hide(); }
  @Override protected void onPause() { super.onPause(); VOLK.cancel(); volH.removeCallbacks(volCheck); bgStart(); if (pendingOverlayReq) overlayLeft = true; if (Demo.ENABLED) Demo.onPause(this); }
  @Override protected void onResume() { super.onResume(); visible = true; internalLaunch = false; bgStop(); OverlayController.hide(); syncOverlay(true); if (Demo.ENABLED) Demo.onResume(this); }
  @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) syncOverlay(true); }
  @Override protected void onStop() {
    super.onStop();
    if (Demo.ENABLED) Demo.onStop(this);
    if (OverlayPolicy.skipLeave(internalLaunch)) return;   // our own system screen is in front: still "in the app"
    visible = false; overlayMaybeShow();
  }
  @Override protected void onDestroy() {
    bgStop();
    try { if (ble != null) { ble.stopNow(); ble.disconnect(); } } catch (Throwable ignored) {}
    try { if (CoyoteBridge.paw != null) CoyoteBridge.paw.disconnectAll(); } catch (Throwable ignored) {} /* v4.20 Pawprint test links */
    if (!OverlayController.connected()) OverlayController.hide();
    stopAudioCapture();
    try { AudioAnalyzer.cancel = true; } catch (Throwable ignored) {}
    I = null;
    super.onDestroy();
  }
}

package com.anubis.app;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;

public class CoyoteBridge {
  final MainActivity a;
  CoyoteBridge(MainActivity a) { this.a = a; }
  CoyoteBle ble() { return a.bleOrNull(); }

  @JavascriptInterface public String platform() { return "android"; }
  @JavascriptInterface public String demoInfo() { return Demo.info(); }
  /** v4.16.1: which build this is, decided at compile time by the variant's Demo.ENABLED. The page only ever shows the demo
      clock or the demo lock when this says "demo"; a live build can never be mistaken for the demo. */
  @JavascriptInterface public String build() {
    String pkg = "", ver = ""; long code = 0;
    try { pkg = a.getPackageName(); android.content.pm.PackageInfo pi = a.getPackageManager().getPackageInfo(pkg, 0);
          ver = pi.versionName == null ? "" : pi.versionName; code = pi.versionCode; } catch (Throwable ignored) {}
    return "{\"flavor\":\"" + (Demo.ENABLED ? "demo" : "live") + "\",\"pkg\":" + MainActivity.json(pkg) + ",\"version\":" + MainActivity.json(ver) + ",\"code\":" + code + "}";
  }
  @JavascriptInterface public String status() {
    CoyoteBle b = ble();
    return b == null ? "{\"demo\":true,\"connected\":false,\"native\":true}" : b.statusJson();
  }
  @JavascriptInterface public String remembered() {
    CoyoteBle b = ble(); return b == null ? "null" : b.remembered();
  }
  @JavascriptInterface public String lastCrash() {
    String c = CrashLog.read(a); return c == null ? "" : c;
  }
  @JavascriptInterface public void clearCrash() { CrashLog.clear(a); }
  @JavascriptInterface public void startScan() {
    a.runOnUiThread(new Runnable() { public void run() {
      a.ensurePermsThen(new Runnable() { public void run() {
        CoyoteBle b = ble();
        if (b != null) b.startScan(false);
        else MainActivity.emit("error", "{\"msg\":\"BLE unavailable - use Demo device\"}");
      }});
    }});
  }
  @JavascriptInterface public void startDebugScan() {
    a.runOnUiThread(new Runnable() { public void run() {
      a.ensurePermsThen(new Runnable() { public void run() {
        CoyoteBle b = ble();
        if (b != null) b.startScan(true);
        else MainActivity.emit("error", "{\"msg\":\"BLE unavailable\"}");
      }});
    }});
  }
  @JavascriptInterface public String permState() { return a.permStateJson(); }
  @JavascriptInterface public void stopScan() { CoyoteBle b = ble(); if (b != null) b.stopScan(); }
  @JavascriptInterface public void connect(final String addr) {
    a.runOnUiThread(new Runnable() { public void run() {
      a.ensurePermsThen(new Runnable() { public void run() {
        CoyoteBle b = ble(); if (b != null) b.connect(addr);
      }});
    }});
  }
  @JavascriptInterface public void autoConnect() {
    a.runOnUiThread(new Runnable() { public void run() {
      a.ensurePermsThen(new Runnable() { public void run() {
        CoyoteBle b = ble(); if (b != null) b.autoConnect();
      }});
    }});
  }
  @JavascriptInterface public void disconnect() {
    CoyoteBle b = ble(); if (b != null) b.disconnect();
    OverlayController.hide();
    a.stopFgSafe();
  }
  @JavascriptInterface public void remember(String addr, String name) { CoyoteBle b = ble(); if (b != null) b.remember(addr, name); }
  @JavascriptInterface public void setCaps(int aCap, int bCap) { CoyoteBle b = ble(); if (b != null) b.setCaps(aCap, bCap); }
  @JavascriptInterface public void setOutput(int aa, int bb, String slotsJson) {
    if (Demo.ENABLED && Demo.exhausted()) { CoyoteBle z = ble(); if (z != null) z.stopNow(); return; }
    CoyoteBle bl = ble(); if (bl == null) return;
    try {
      JSONObject o = new JSONObject(slotsJson == null || slotsJson.isEmpty() ? "{}" : slotsJson);
      bl.setTargets(aa, bb, arr(o.optJSONArray("fA"), 10), arr(o.optJSONArray("iA"), 0), arr(o.optJSONArray("fB"), 10), arr(o.optJSONArray("iB"), 0));
    } catch (Exception e) { bl.setTargets(aa, bb, null, null, null, null); }
  }
  /** v4.49: volume-key emergency STOP on/off (page setting coyote.volStop) */
  @JavascriptInterface public void volStop(boolean on) { MainActivity.VOLK.on = on; if (!on) MainActivity.VOLK.cancel(); }
  @JavascriptInterface public void stopNow() { CoyoteBle b = ble(); if (b != null) b.stopNow(); }
  @JavascriptInterface public void ackHardwareStop(int token) { CoyoteBle b = ble(); if (b != null) b.ackHardwareStop(token); }
  @JavascriptInterface public void dialAck(int n) { CoyoteBle b = ble(); if (b != null) b.dialAck(n); }
  @JavascriptInterface public String bleLog() { CoyoteBle b = ble(); return b == null ? "No Coyote helper yet" : b.bleLog(); }
  @JavascriptInterface public void clearBleLog() { CoyoteBle b = ble(); if (b != null) b.clearBleLog(); }
  @JavascriptInterface public void heartbeat() { CoyoteBle b = ble(); if (b != null) b.heartbeat(); }
  @JavascriptInterface public void overlay(final boolean on) {
    a.runOnUiThread(new Runnable() { public void run() { a.setOverlayEnabled(on); } });
  }
  @JavascriptInterface public boolean overlayGranted() { return OverlayController.granted(a); }
  @JavascriptInterface public String overlayState() { return a.overlayStateJson(); }
  @JavascriptInterface public void openOverlaySettings() {
    a.runOnUiThread(new Runnable() { public void run() { a.openOverlaySettings(); } });
  }
  /** v4.30: the page tells native which screen is sending output; a bubble tap returns there. */
  @JavascriptInterface public void overlayMode(String m) { OverlayController.setMode(m); }
  /** v4.37: bubble look from Settings › Overlay: {"size":"s|m|l","op":20-100,"stop":true|false}. Saved natively; a showing bubble is rebuilt. */
  @JavascriptInterface public void overlayStyle(String j) { OverlayController.setStyle(a.getApplicationContext(), j); }
  /** v4.30: arms a 15 s preview that appears when the user leaves Anubis within 60 s (needs the permission; live numbers if connected, else "demo"). */
  @JavascriptInterface public void testOverlay() {
    a.runOnUiThread(new Runnable() { public void run() { OverlayController.show(a.getApplicationContext(), true); } });
  }
  /* v4.28 overlay diagnostics + App info (Android 15+ "Allow restricted settings" for apps installed from a file) */
  @JavascriptInterface public String overlayDiag() { return a.overlayDiagJson(); }
  @JavascriptInterface public void openAppInfo() {
    a.runOnUiThread(new Runnable() { public void run() { a.openAppInfo(); } });
  }
  /* v4.28 Save profile: Android "save as" screen; the result comes back as the "saved" event */
  @JavascriptInterface public void saveFile(final String name, final String mime, final String body) {
    a.runOnUiThread(new Runnable() { public void run() { a.saveFile(name, mime, body); } });
  }
  @JavascriptInterface public boolean battOk() { return a.battOk(); }
  @JavascriptInterface public void requestBatt() {
    a.runOnUiThread(new Runnable() { public void run() { a.requestBatt(); } });
  }
  @JavascriptInterface public void requestOverlay() {
    a.runOnUiThread(new Runnable() { public void run() { a.requestOverlay(); } });
  }
  @JavascriptInterface public void requestAudioCapture() {
    a.runOnUiThread(new Runnable() { public void run() { a.requestAudioCapture(); } });
  }
  @JavascriptInterface public void stopAudioCapture() {
    a.runOnUiThread(new Runnable() { public void run() { a.stopAudioCapture(); } });
  }
  /* ---- v4.20: Pawprint test / diagnostic (Settings → Accessories → Test). Separate BLE link; drives no output. ---- */
  static PawBle paw;
  PawBle paw() { if (paw == null) paw = new PawBle(a.getApplicationContext()); return paw; }
  @JavascriptInterface public void pawScan() {
    a.runOnUiThread(new Runnable() { public void run() {
      a.ensurePermsThen(new Runnable() { public void run() { paw().startScan(); } });
    } });
  }
  @JavascriptInterface public void pawStopScan() { if (paw != null) paw.stopScan(); }
  @JavascriptInterface public void pawConnect(final String addr) {
    a.runOnUiThread(new Runnable() { public void run() {
      a.ensurePermsThen(new Runnable() { public void run() { paw().connect(addr); } });
    } });
  }
  @JavascriptInterface public void pawDisconnect(final String addr) { if (paw != null) paw.disconnect(addr); }
  @JavascriptInterface public void pawDisconnectAll() { if (paw != null) paw.disconnectAll(); }
  @JavascriptInterface public void pawLight(String addr, int c1, int c2, int sp) { if (paw != null) paw.light(addr, c1, c2, sp); }
  @JavascriptInterface public void pawReconnect(final String addr) {
    a.runOnUiThread(new Runnable() { public void run() {
      a.ensurePermsThen(new Runnable() { public void run() { paw().reconnect(addr); } });
    } });
  }
  /* v4.22 */
  @JavascriptInterface public void pawConnectC(final String addr, final int color) {
    a.runOnUiThread(new Runnable() { public void run() {
      a.ensurePermsThen(new Runnable() { public void run() { paw().connect(addr, color); } });
    } });
  }
  @JavascriptInterface public String pawEvents(double since) { return paw == null ? "{\"now\":0,\"last\":0,\"ev\":[],\"devs\":[]}" : paw.events((long) since); }
  /** v4.22: the page says whether Android Back has something to close inside it (settings page, sheet, tab). */
  @JavascriptInterface public void setBackActive(final boolean on) {
    a.runOnUiThread(new Runnable() { public void run() { a.setBackActive(on); } });
  }
  @JavascriptInterface public String pawLog() { return paw == null ? "No Pawprint session yet" : paw.fullLog(); }
  /** v4.21: plain-text clipboard copy (WebView navigator.clipboard is unreliable). Returns true when it was placed. */
  @JavascriptInterface public boolean copyText(String label, String text) {
    try {
      android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
      cm.setPrimaryClip(android.content.ClipData.newPlainText(label == null ? "Anubis" : label, text == null ? "" : text));
      return true;
    } catch (Throwable t) { return false; }
  }
  /** v4.21: Draw / Tempo library lives in app-private file storage (no localStorage size limit). */
  private static final Object LIB_LOCK = new Object();
  private java.io.File libFile() { return new java.io.File(a.getFilesDir(), "drawlib.json"); }
  /** Returns the saved library JSON, or "" when there is no file yet. */
  @JavascriptInterface public String libRead() {
    synchronized (LIB_LOCK) {
      java.io.File f = libFile();
      if (!f.exists()) return "";
      try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return new String(bo.toByteArray(), "UTF-8");
      } catch (Throwable t) { return ""; }
    }
  }
  /** Atomic write: temp file then rename, so a crash mid-write never leaves a half file. */
  @JavascriptInterface public boolean libWrite(String json) {
    if (json == null) return false;
    synchronized (LIB_LOCK) {
      java.io.File f = libFile(), tmp = new java.io.File(a.getFilesDir(), "drawlib.json.tmp");
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
        out.write(json.getBytes("UTF-8")); out.getFD().sync();
      } catch (Throwable t) { tmp.delete(); return false; }
      if (tmp.renameTo(f)) return true;
      f.delete(); return tmp.renameTo(f);
    }
  }
  @JavascriptInterface public String pawState() { return paw == null ? "{\"scanning\":false,\"found\":[],\"devs\":[],\"log\":[]}" : paw.state(); }
  @JavascriptInterface public String readAudioLevels() { return a.audioLevelsJson(); }
  @JavascriptInterface public void shareText(final String name, final String mime, final String body) {
    a.runOnUiThread(new Runnable() { public void run() { a.shareText(name, mime, body); } });
  }
  @JavascriptInterface public void pickBackup() {
    a.runOnUiThread(new Runnable() { public void run() { a.pickBackup(); } });
  }
  @JavascriptInterface public void pickAudioFolder() {
    a.runOnUiThread(new Runnable() { public void run() { a.pickAudioFolder(); } });
  }
  @JavascriptInterface public void startMic() {
    a.runOnUiThread(new Runnable() { public void run() { a.startMic(); } });
  }
  @JavascriptInterface public void markAudioPick(boolean on) { a.audioPickPending = on; }
  @JavascriptInterface public void analyzeUri(final String uri, final String name) {
    a.runOnUiThread(new Runnable() { public void run() { a.analyzeUri(uri, name); } });
  }
  @JavascriptInterface public void cancelAnalyze() { AudioAnalyzer.cancel = true; }
  @JavascriptInterface public String result() {
    byte[] r = AudioAnalyzer.result;
    return r == null ? "" : android.util.Base64.encodeToString(r, android.util.Base64.NO_WRAP);
  }
  @JavascriptInterface public String listDir(String tree, String docId) { return a.listDir(tree, docId); }
  @JavascriptInterface public String docUri(String tree, String docId) { return a.docUri(tree, docId); }
  @JavascriptInterface public boolean treeOk(String tree) { return a.treeOk(tree); }
  @JavascriptInterface public void releaseTree(String tree) { a.releaseTree(tree); }
  @JavascriptInterface public void freeResult() { AudioAnalyzer.result = null; }
  static int[] arr(JSONArray a, int d) {
    int[] o = new int[]{d, d, d, d};
    if (a == null) return o;
    for (int i = 0; i < 4 && i < a.length(); i++) o[i] = a.optInt(i, d);
    return o;
  }
  /* ---------- v4.38 Remote partner ---------- */
  @JavascriptInterface public void setRemoteActive(boolean on) { a.remoteActive = on; }
  @JavascriptInterface public boolean camOk() { return a.camOk(); }
  @JavascriptInterface public void requestCamera() {
    a.runOnUiThread(new Runnable() { public void run() { a.requestCamera(); } });
  }
  /** Copies a PNG (base64, no data: prefix) to the clipboard as an image, served read-only by QrProvider. */
  @JavascriptInterface public boolean copyImage(String label, String b64) {
    try {
      byte[] png = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
      if (png.length == 0 || png.length > 2000000) return false;
      java.io.File f = QrProvider.file(a);
      java.io.FileOutputStream o = new java.io.FileOutputStream(f);
      try { o.write(png); } finally { o.close(); }
      android.net.Uri u = QrProvider.uri(a);
      android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
      cm.setPrimaryClip(android.content.ClipData.newUri(a.getContentResolver(), label == null ? "Invite QR" : label, u));
      return true;
    } catch (Throwable t) { CrashLog.write(a, t); return false; }
  }
}

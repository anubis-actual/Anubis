package com.anubis.app;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import java.util.*;

/**
 * v4.21 (diag) · DG-Lab Pawprint (47L120…) test / diagnostic link. Its own scanner and GATT links, next to CoyoteBle.
 * Protocol: Pawprint V1.1 (service 0x180C, write 0x150A, notify 0x150B).
 * Connect: TRANSPORT_LE → discover (full service dump logged) → CCCD on 0x150B (status logged) →
 * 50 cc 00 (17 bytes, write type picked from the characteristic's properties) → wait for 51 → 50 cc D0 01.
 * No 51 within 1.2 s → retry ladder (same type, other write type, official-app init 50 02 + 13×00), then D0 anyway.
 * Every write (+ return code + callback status), every notify (hex), every state change (+ GATT status name) is logged.
 * Pawprints drive NO output: this class only reads sensors and sets the lights. The page polls state().
 */
@SuppressLint("MissingPermission")
public class PawBle {
  static final String TAG = "PawBle";
  static final UUID S_MAIN = CoyoteBle.S_MAIN, C_WRITE = CoyoteBle.C_WRITE, C_NOTIFY = CoyoteBle.C_NOTIFY, CCCD = CoyoteBle.CCCD;
  static final int MAX = 6, LOG_MAX = 30000, WAIT51 = 1200, EV_MAX = 400;
  static final int[] COLS = {1, 5, 3, 6, 2, 4}; // v4.22: Paw 1-6 = Yellow, Cyan, Purple, Green, Red, Blue
  final java.text.SimpleDateFormat logFmt = new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
  /* v4.22: button edges for the trigger engine, filled at D0 time (id, addr, elapsed ms, pressed, raw) */
  final LinkedList<Object[]> evs = new LinkedList<Object[]>(); long evId = 0;

  final Context ctx;
  final Handler h = new Handler(Looper.getMainLooper());
  BluetoothAdapter ad;
  ScanCallback scanCb;
  volatile boolean scanning;
  final Map<String, String[]> found = new LinkedHashMap<String, String[]>(); // addr → {name, rssi, time}
  final Map<String, Dev> devs = new LinkedHashMap<String, Dev>();
  final LinkedList<String> log = new LinkedList<String>();
  final long born = SystemClock.elapsedRealtime();
  int dropped = 0;

  class Dev {
    final String addr; String name = ""; volatile String state = "connecting"; String why = "";
    BluetoothGatt g; BluetoothGattCharacteristic w, n;
    int color, devType = -1, bat = -1, seq = -1, btn = -1, acc = -1, ax = 0, ay = 0, az = 0, ext = -1, d0 = 0, msgs = 0, n53 = 0, n51 = 0, seqGaps = 0;
    long lastAt = 0, lastD0 = 0, t0 = 0, connAt = 0, sent50At = 0, got51At = 0, lastD0Req = 0, lastD0E = 0;
    String raw = "", last51 = "", last53 = "", lastEv = "", lastOther = "", props = "", svcs = "";
    long lastAutoRe = 0, firstD0Req = 0; int autoRe = 0; boolean pressed;
    boolean streaming, user, retried133; int wtype = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, watchGen = -1, tries = 0, mtu = 23, lastStatus = -1, gen = 0;
    final LinkedList<byte[]> q = new LinkedList<byte[]>(); boolean busy; int wid;
    Dev(String a, int c) { addr = a; color = c; }
    String tag() { return addr.substring(Math.max(0, addr.length() - 5)) + (t0 > 0 ? " +" + (SystemClock.elapsedRealtime() - t0) + "ms" : ""); }
  }

  PawBle(Context c) {
    ctx = c.getApplicationContext();
    try { ad = ((BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter(); } catch (Exception e) { ad = null; }
    log("PawBle up · Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ") · " + Build.MANUFACTURER + " " + Build.MODEL);
  }

  synchronized void log(String s) {
    Log.i(TAG, s);
    log.add(logFmt.format(new Date()) + " " + s);
    while (log.size() > LOG_MAX) { log.removeFirst(); dropped++; }
  }

  static String gs(int s) {
    switch (s) {
      case 0: return "0 SUCCESS"; case 1: return "1 INVALID_HANDLE"; case 2: return "2 READ_NOT_PERMITTED"; case 3: return "3 WRITE_NOT_PERMITTED";
      case 5: return "5 INSUFFICIENT_AUTHENTICATION"; case 6: return "6 REQUEST_NOT_SUPPORTED"; case 7: return "7 INVALID_OFFSET";
      case 8: return "8 CONN_TIMEOUT (link supervision timeout · out of range / device went quiet)";
      case 13: return "13 INVALID_ATTRIBUTE_LENGTH"; case 15: return "15 INSUFFICIENT_ENCRYPTION";
      case 19: return "19 REMOTE_USER_TERMINATED (the Pawprint ended the link)";
      case 22: return "22 LOCAL_HOST_TERMINATED (this phone ended the link)";
      case 34: return "34 LMP_RESPONSE_TIMEOUT"; case 62: return "62 FAIL_TO_ESTABLISH"; case 133: return "133 GATT_ERROR (generic · often retry)";
      case 257: return "257 FAILURE"; default: return s + " (?)";
    }
  }
  static String wt(int t) { return t == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ? "no-response" : "with-response"; }
  static String propStr(int p) {
    StringBuilder b = new StringBuilder();
    if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) b.append("R ");
    if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) b.append("W ");
    if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) b.append("Wnr ");
    if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) b.append("N ");
    if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) b.append("I ");
    return b.toString().trim() + " (0x" + Integer.toHexString(p) + ")";
  }
  static String sh(UUID u) {
    String s = u.toString();
    return s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000") ? s.substring(4, 8) : s;
  }
  static String hex(byte[] v) {
    if (v == null) return "";
    StringBuilder b = new StringBuilder();
    for (byte x : v) { if (b.length() > 0) b.append(' '); b.append(String.format("%02X", x & 0xff)); }
    return b.toString();
  }
  static String q(String s) {
    if (s == null) return "\"\"";
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      if (c == '"' || c == '\\') b.append('\\').append(c);
      else if (c < 32) b.append(String.format("\\u%04x", (int) c));
      else b.append(c);
    }
    return b.append('"').toString();
  }

  /* ---------- scan (Pawprints only: name starts 47L120) ---------- */
  public void startScan() {
    if (ad == null || !ad.isEnabled()) { log("Bluetooth is off or unavailable"); return; }
    stopScan();
    final BluetoothLeScanner sc = ad.getBluetoothLeScanner();
    if (sc == null) { log("No LE scanner"); return; }
    scanCb = new ScanCallback() {
      public void onScanResult(int c, ScanResult r) { onFound(r); }
      public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) onFound(r); }
      public void onScanFailed(int e) { scanning = false; log("Scan failed " + e); }
    };
    ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
    try { sc.startScan(null, st, scanCb); scanning = true; log("Scanning for Pawprints (47L120…)"); }
    catch (Exception e) { log("startScan: " + e); scanning = false; }
    h.postDelayed(new Runnable() { public void run() { stopScan(); } }, 20000);
  }
  public void stopScan() {
    if (ad != null && scanCb != null) { try { ad.getBluetoothLeScanner().stopScan(scanCb); } catch (Exception ignored) {} }
    if (scanning) log("Scan stopped");
    scanCb = null; scanning = false;
  }
  synchronized void onFound(ScanResult r) {
    try {
      BluetoothDevice d = r.getDevice();
      ScanRecord rec = r.getScanRecord();
      String n = rec != null ? rec.getDeviceName() : null;
      if (n == null) try { n = d.getName(); } catch (Exception ignored) {}
      if (!CoyoteBle.isPawName(n)) return;
      if (!found.containsKey(d.getAddress())) log("Saw Pawprint " + n + " " + d.getAddress() + " rssi=" + r.getRssi() + (rec != null ? " adv=" + hex(rec.getBytes()) : ""));
      found.put(d.getAddress(), new String[]{n, String.valueOf(r.getRssi()), String.valueOf(System.currentTimeMillis())});
    } catch (Throwable t) { log("onFound: " + t); }
  }

  /* ---------- connect ---------- */
  public synchronized void connect(String a) { connect(a, 0); }
  /** v4.22: connect with the page's stable Paw colour (Paw n keeps its colour across reconnects); 0 = first free colour. */
  public synchronized void connect(String a, int color) {
    if (ad == null || a == null) return;
    if (devs.containsKey(a)) { log(a + " already in a slot"); return; }
    if (devs.size() >= MAX) { log("All " + MAX + " Pawprint slots are in use"); return; }
    int col = color;
    if (col < 1 || col > 7) { col = COLS[devs.size() % COLS.length];
      for (int c : COLS) { boolean used = false; for (Dev o : devs.values()) if (o.color == c) used = true; if (!used) { col = c; break; } } }
    final Dev dv = new Dev(a, col);
    String[] f = found.get(a); if (f != null) dv.name = f[0];
    devs.put(a, dv);
    rememberPaw(a);
    open(dv);
  }
  /** Re-open a dropped slot (same colour, counters reset). */
  public synchronized void reconnect(String a) {
    Dev d = devs.get(a);
    if (d == null) { connect(a); return; }
    if (d.g != null) { log(a + " is still open · reconnect ignored"); return; }
    log("Reconnect " + a);
    d.autoRe = 0; resetCounters(d);
    open(d);
  }
  void resetCounters(Dev d) {
    d.devType = -1; d.bat = -1; d.seq = -1; d.btn = -1; d.acc = -1; d.ext = -1; d.d0 = 0; d.msgs = 0; d.n53 = 0; d.n51 = 0; d.seqGaps = 0;
    d.lastAt = 0; d.lastD0 = 0; d.lastD0E = 0; d.raw = ""; d.last51 = ""; d.last53 = ""; d.lastEv = ""; d.lastOther = ""; d.streaming = false; d.tries = 0; d.why = "";
    d.retried133 = false; d.firstD0Req = 0; d.pressed = false;
    synchronized (d.q) { d.q.clear(); d.busy = false; }
  }
  /** v4.22: the D0 stream stalled (~3 s with nothing) → full close + reopen, like the Reconnect button. At most every 8 s, max 5 in a row. */
  synchronized void autoReconnect(final Dev d, String why) {
    long now = SystemClock.elapsedRealtime();
    if (now - d.lastAutoRe < 8000 || d.user) return;
    if (d.autoRe >= 5) { log("D0 stalled again (" + why + ") · 5 automatic reconnects in a row failed · giving up (" + d.tag() + ")"); d.why = "D0 stalled · auto reconnect gave up"; d.state = "dropped";
      BluetoothGatt g = d.g; d.g = null; d.streaming = false; try { if (g != null) { g.disconnect(); g.close(); } } catch (Exception ignored) {} return; }
    d.lastAutoRe = now; d.autoRe++;
    log("D0 stalled (" + why + ") · automatic full reconnect #" + d.autoRe + " (" + d.tag() + ")");
    BluetoothGatt g = d.g; d.g = null; d.streaming = false; d.connAt = 0; d.state = "reconnecting"; d.why = "D0 stalled · reconnecting";
    try { if (g != null) { g.disconnect(); g.close(); } } catch (Exception ignored) {}
    if (d.pressed) { d.pressed = false; pushEv(d, false, "stall"); }
    h.postDelayed(new Runnable() { public void run() { synchronized (PawBle.this) {
      if (devs.get(d.addr) == d && d.g == null && "reconnecting".equals(d.state)) { resetCounters(d); open(d); }
    } } }, 700);
  }
  synchronized void pushEv(Dev d, boolean p, String raw) {
    evs.add(new Object[]{++evId, d.addr, SystemClock.elapsedRealtime(), p ? 1 : 0, raw});
    while (evs.size() > EV_MAX) evs.removeFirst();
  }
  void open(final Dev dv) {
    try {
      BluetoothDevice d = ad.getRemoteDevice(dv.addr);
      try { String dn = d.getName(); if (dn != null && !dn.isEmpty()) dv.name = dn; } catch (Exception ignored) {}
      int bond = -1; try { bond = d.getBondState(); } catch (Exception ignored) {}
      dv.t0 = SystemClock.elapsedRealtime(); dv.state = "connecting"; dv.user = false; final int gen = ++dv.gen;
      log("Connect " + dv.addr + " (" + dv.name + ") bond=" + bond + " · connectGatt(autoConnect=false, LE)");
      dv.g = Build.VERSION.SDK_INT >= 23 ? d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) : d.connectGatt(ctx, false, cb);
      if (dv.g == null) { log("connectGatt returned null"); dv.state = "error"; dv.why = "connectGatt null"; return; }
      h.postDelayed(new Runnable() { public void run() {
        if (dv.gen == gen && ("connecting".equals(dv.state) || "discovering".equals(dv.state) || "subscribing".equals(dv.state))) {
          log("Connect timeout " + dv.tag() + " in state " + dv.state); dv.why = "timeout in " + dv.state; dv.state = "timeout"; close(dv);
        }
      }}, 15000);
    } catch (Exception e) { log("connect: " + e); dv.state = "error"; dv.why = String.valueOf(e); }
  }
  public synchronized void disconnect(String a) {
    Dev d = devs.remove(a);
    if (d != null) { d.user = true; log("Disconnect (user) " + d.tag()); close(d); }
  }
  public synchronized void disconnectAll() { for (String a : new ArrayList<String>(devs.keySet())) disconnect(a); stopScan(); }
  void close(Dev d) {
    try { if (d.g != null) { d.g.disconnect(); d.g.close(); } } catch (Exception ignored) {}
    d.g = null; d.streaming = false;
    if (!"timeout".equals(d.state) && !"error".equals(d.state)) d.state = "disconnected";
  }
  void rememberPaw(String a) {
    try {
      SharedPreferences p = ctx.getSharedPreferences("paw", 0);
      String s = p.getString("addrs", "");
      if (!s.toUpperCase(Locale.US).contains(a.toUpperCase(Locale.US))) p.edit().putString("addrs", s.isEmpty() ? a : s + "," + a).apply();
    } catch (Exception ignored) {}
  }
  /** Strict match on the gatt object (an old, closed gatt for the same address never touches a newer link). */
  synchronized Dev of(BluetoothGatt g) {
    for (Dev d : devs.values()) if (d.g == g) return d;
    return null;
  }

  /* ---------- writes (one at a time per device) ---------- */
  static byte[] cmd50(int color, boolean d0) {
    byte[] b = new byte[17];
    b[0] = 0x50; b[1] = (byte) color;
    if (d0) { b[2] = (byte) 0xD0; b[3] = 0x01; }
    return b;
  }
  /** What DG-Kit says the official app writes on every connect (DeviceManager.initOta): 50 02 + 13×00 (15 bytes). */
  static final byte[] APP_INIT = {0x50, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
  void send(Dev d, byte[] v) {
    synchronized (d.q) { d.q.add(v); }
    pump(d);
  }
  void pump(final Dev d) {
    byte[] v; BluetoothGatt g; int type;
    synchronized (d.q) { if (d.busy || d.q.isEmpty() || d.g == null || d.w == null) return; v = d.q.removeFirst(); d.busy = true; d.wid++; g = d.g; type = d.wtype; }
    String rc;
    try {
      if (Build.VERSION.SDK_INT >= 33) rc = "rc=" + g.writeCharacteristic(d.w, v, type);
      else { d.w.setValue(v); d.w.setWriteType(type); rc = "ok=" + g.writeCharacteristic(d.w); }
    } catch (Exception e) { rc = "threw " + e; }
    log("→ " + d.tag() + " [" + v.length + "B " + wt(type) + " " + rc + "] " + hex(v));
    if (v.length > 0 && v[0] == 0x50) d.sent50At = SystemClock.elapsedRealtime();
    final int id;
    synchronized (d.q) { id = d.wid; }
    /* safety net: a lost write callback never blocks the queue */
    h.postDelayed(new Runnable() { public void run() { synchronized (d.q) { if (!d.busy || d.wid != id) return; d.busy = false; } log("write callback missing after 300 ms · " + d.tag()); pump(d); } }, 300);
  }
  /** shoulder lights: c2 < 0 → "70 c1" (one colour); else "70 c1 c2 sp" (flash: sp 1 slow, 2 fast, 3 stop) */
  public synchronized void light(String a, int c1, int c2, int sp) {
    Dev d = devs.get(a); if (d == null) return;
    byte[] v = c2 < 0 ? new byte[]{0x70, (byte) c1} : new byte[]{0x70, (byte) c1, (byte) c2, (byte) sp};
    send(d, v);
  }

  /* 51 wait ladder: try 1 = 50 cc 00 (17B, chosen type) · 2 = same again · 3 = other write type · 4 = app init 50 02 (15B) · then D0 anyway */
  void armWait51(final Dev d) {
    final int gen = d.gen, t = d.tries;
    h.postDelayed(new Runnable() { public void run() {
      if (d.gen != gen || d.g == null || d.n51 > 0 || d.tries != t) return;
      d.tries++;
      if (d.tries == 2) { log("No 51 after " + WAIT51 + " ms · resending 50 no-trigger (" + d.tag() + ")"); send(d, cmd50(d.color, false)); armWait51(d); }
      else if (d.tries == 3) {
        int other = d.wtype == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        log("Still no 51 · switching write type " + wt(d.wtype) + " → " + wt(other)); d.wtype = other; send(d, cmd50(d.color, false)); armWait51(d);
      }
      else if (d.tries == 4) { log("Still no 51 · trying the official-app init 50 02 + 13×00 (15 B)"); send(d, APP_INIT); armWait51(d); }
      else { log("No 51 after 4 tries · requesting the D0 stream anyway"); d.streaming = true; d.lastD0Req = SystemClock.elapsedRealtime(); if (d.firstD0Req == 0) d.firstD0Req = d.lastD0Req; send(d, cmd50(d.color, true)); watchD0(d); }
    }}, WAIT51);
  }
  /* keepalive: in D0 mode, if no D0 for 1.5 s while linked, re-send the D0 request (at most every 3 s) */
  void watchD0(final Dev d) {
    if (d.watchGen == d.gen) return; // one watcher per link
    d.watchGen = d.gen;
    final int gen = d.gen;
    h.postDelayed(new Runnable() { public void run() {
      if (d.gen != gen || d.g == null) return;
      long now = SystemClock.elapsedRealtime();
      long last = Math.max(d.lastD0E, d.lastD0Req); // last D0 or the last request, both on the elapsed clock
      if (d.lastD0E > 0 && now - d.lastD0E > 3000) { autoReconnect(d, "no D0 for " + (now - d.lastD0E) + " ms"); if (d.g == null) return; }
      else if (d.lastD0E == 0 && d.firstD0Req > 0 && now - d.firstD0Req > 6000) { autoReconnect(d, "D0 never started"); if (d.g == null) return; }
      if (now - last > 1500 && now - d.lastD0Req > 3000) { log("No D0 for " + (now - Math.max(d.lastD0E, d.connAt)) + " ms · re-sending D0 request (" + d.tag() + ")"); d.lastD0Req = now; send(d, cmd50(d.color, true)); }
      h.postDelayed(this, 500);
    }}, 500);
  }

  final BluetoothGattCallback cb = new BluetoothGattCallback() {
    public void onConnectionStateChange(BluetoothGatt g, int status, int ns) {
      Dev d = of(g);
      if (d == null) { log("state on a stale gatt " + g.getDevice().getAddress() + " status=" + gs(status) + " new=" + ns + " · closing it"); try { g.close(); } catch (Exception ignored) {} return; }
      d.lastStatus = status;
      log("state " + d.tag() + " new=" + (ns == 2 ? "CONNECTED" : ns == 0 ? "DISCONNECTED" : String.valueOf(ns)) + " status=" + gs(status));
      if (ns == BluetoothProfile.STATE_CONNECTED) {
        d.connAt = SystemClock.elapsedRealtime(); d.state = "discovering";
        boolean ok = g.discoverServices(); log("discoverServices → " + ok);
      } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
        long up = d.connAt > 0 ? SystemClock.elapsedRealtime() - d.connAt : -1;
        log("LINK DOWN " + d.tag() + " after " + up + " ms linked · msgs=" + d.msgs + " 51=" + d.n51 + " 53=" + d.n53 + " D0=" + d.d0 + " · " + gs(status));
        d.streaming = false; try { g.close(); } catch (Exception ignored) {} d.g = null;
        if (d.pressed) { d.pressed = false; pushEv(d, false, "link down"); }
        d.why = gs(status) + (up >= 0 ? " · after " + (up / 100) / 10.0 + " s" : " · never linked");
        d.state = d.user ? "disconnected" : "dropped";
        if (status == 133 && d.connAt == 0 && !d.retried133 && !d.user) {
          d.retried133 = true; log("133 before linking · one automatic retry in 600 ms");
          final Dev dd = d; h.postDelayed(new Runnable() { public void run() { synchronized (PawBle.this) { if (devs.get(dd.addr) == dd && dd.g == null) open(dd); } } }, 600);
        }
        d.connAt = 0;
      }
    }
    public void onServicesDiscovered(BluetoothGatt g, int st) {
      Dev d = of(g); if (d == null) return;
      StringBuilder sv = new StringBuilder();
      try { for (BluetoothGattService s : g.getServices()) { sv.append(sh(s.getUuid())).append('{'); boolean f = true;
        for (BluetoothGattCharacteristic c : s.getCharacteristics()) { if (!f) sv.append(", "); f = false; sv.append(sh(c.getUuid())).append(' ').append(propStr(c.getProperties())); }
        sv.append("} "); } } catch (Exception e) { sv.append("dump failed ").append(e); }
      d.svcs = sv.toString().trim();
      log("services " + d.tag() + " status=" + gs(st) + " · " + d.svcs);
      BluetoothGattService s = g.getService(S_MAIN);
      if (s == null) { log("No 0x180C on " + d.addr); d.state = "error"; d.why = "no 0x180C"; close(d); return; }
      d.w = s.getCharacteristic(C_WRITE); d.n = s.getCharacteristic(C_NOTIFY);
      if (d.w == null || d.n == null) { log("Missing 0x150A/0x150B on " + d.addr); d.state = "error"; d.why = "no 150A/150B"; close(d); return; }
      int p = d.w.getProperties(); d.props = propStr(p);
      /* Both community libraries (DG-Kit, dglab-controller-python) write without response; use that when 0x150A offers it */
      d.wtype = (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
      log("0x150A props " + d.props + " → write type " + wt(d.wtype) + " · 0x150B props " + propStr(d.n.getProperties()));
      try { String gn = g.getDevice().getName(); if (gn != null) d.name = gn; } catch (Exception ignored) {}
      d.state = "subscribing";
      boolean sn = g.setCharacteristicNotification(d.n, true);
      BluetoothGattDescriptor cd = d.n.getDescriptor(CCCD);
      if (cd != null) {
        String rc;
        if (Build.VERSION.SDK_INT >= 33) rc = "rc=" + g.writeDescriptor(cd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        else { cd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); rc = "ok=" + g.writeDescriptor(cd); }
        log("setNotification → " + sn + " · CCCD write 01 00 " + rc);
      } else { log("0x150B has no CCCD · going on"); afterCccd(d); }
    }
    public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor ds, int status) {
      Dev d = of(g); if (d == null) return;
      log("CCCD written " + d.tag() + " status=" + gs(status));
      if (CCCD.equals(ds.getUuid())) afterCccd(d);
    }
    void afterCccd(Dev d) {
      if (!"subscribing".equals(d.state)) return;
      d.state = "configuring"; d.tries = 1;
      log("Notify on · writing 50 no-trigger · waiting for 51 (" + d.tag() + ")");
      send(d, cmd50(d.color, false));
      armWait51(d);
    }
    public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
      Dev d = of(g); if (d == null) return;
      log("✓ write cb " + d.tag() + " status=" + gs(st));
      synchronized (d.q) { d.busy = false; }
      pump(d);
    }
    public void onMtuChanged(BluetoothGatt g, int mtu, int st) {
      Dev d = of(g); if (d == null) return;
      d.mtu = mtu; log("MTU " + mtu + " status=" + gs(st) + " (" + d.tag() + ")");
    }
    /* Android 13+ calls only the 3-arg form when it is overridden; older versions call only the 2-arg form */
    public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) { if (Build.VERSION.SDK_INT < 33) onMsg(g, c, c.getValue()); }
    public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) { onMsg(g, c, v); }
  };

  void onMsg(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
    if (v == null || v.length == 0) return;
    Dev d = of(g); if (d == null) return;
    synchronized (this) {
      String hx = hex(v);
      d.raw = hx; d.lastAt = System.currentTimeMillis(); d.msgs++;
      int t = v[0] & 0xff;
      boolean other = c != null && !C_NOTIFY.equals(c.getUuid());
      if (t == 0xD0 && v.length >= 9 && !other) {
        int s = v[2] & 0xff;
        if (d.seq >= 0 && s != ((d.seq + 1) & 0xff)) d.seqGaps++;
        d.seq = s; d.btn = v[3] & 0xff; d.acc = v[4] & 0xff; d.ax = v[5]; d.ay = v[6]; d.az = v[7]; d.ext = v[8] & 0xff;
        d.d0++; d.lastD0 = d.lastAt; d.lastD0E = SystemClock.elapsedRealtime(); d.streaming = true;
        if (!"streaming".equals(d.state)) { d.state = "streaming"; d.why = ""; log("D0 stream live (" + d.tag() + ")" + (d.autoRe > 0 ? " after automatic reconnect #" + d.autoRe : "")); }
        if (d.d0 > 20) d.autoRe = 0; // a healthy stream resets the give-up counter
        boolean p = d.btn != 0; // v4.22: any non-zero button value = pressed
        if (p != d.pressed) { d.pressed = p; pushEv(d, p, hx); }
        log("← " + d.tag() + " " + hx); // v4.22: every D0 logged (no thinning)
        return;
      }
      log("← " + d.tag() + (other ? " on " + sh(c.getUuid()) : "") + " [" + v.length + "B] " + hx);
      if (other) { d.lastOther = hx; return; }
      if (t == 0x51 && v.length >= 4) {
        d.n51++; d.devType = v[2] & 0xff; d.bat = v[3] & 0xff; d.last51 = hx; d.got51At = SystemClock.elapsedRealtime();
        if ("configuring".equals(d.state)) d.state = "ready";
        log("51 · colour " + (v[1] & 0xff) + " · type " + d.devType + " · battery " + d.bat + (d.sent50At > 0 ? " · " + (d.got51At - d.sent50At) + " ms after last 50" : ""));
        if (!d.streaming) { d.streaming = true; d.lastD0Req = SystemClock.elapsedRealtime(); if (d.firstD0Req == 0) d.firstD0Req = d.lastD0Req; log("starting D0 stream"); send(d, cmd50(d.color, true)); watchD0(d); }
      } else if (t == 0x53) {
        /* Not in the V1.1 doc. Seen on a real 47L120300: 53 01 01 12 D9 41, last 4 bytes = the device's own MAC tail. Logged, never answered. */
        d.n53++; d.last53 = hx;
        String mac = d.addr.replace(":", "").toUpperCase(Locale.US), tail = hx.replace(" ", "");
        boolean macTail = v.length >= 6 && mac.endsWith(tail.substring(4));
        log("53 (undocumented) #" + d.n53 + " · byte1=" + (v.length > 1 ? (v[1] & 0xff) : -1) + " · rest " + (macTail ? "= own MAC tail" : "≠ MAC tail")
          + (d.sent50At > 0 ? " · " + (SystemClock.elapsedRealtime() - d.sent50At) + " ms after last 50" : " · before any 50"));
      } else if (t == 0x5A || t == 0x5B || t == 0x5C || t == 0xF1) {
        d.lastEv = hx;
      } else d.lastOther = hx;
    }
  }

  /* ---------- state for the page ---------- */
  public synchronized String state() {
    long now = System.currentTimeMillis();
    StringBuilder b = new StringBuilder("{\"scanning\":").append(scanning).append(",\"bt\":").append(ad != null && ad.isEnabled()).append(",\"found\":[");
    boolean first = true;
    for (Map.Entry<String, String[]> e : found.entrySet()) {
      if (!first) b.append(','); first = false;
      String[] f = e.getValue();
      b.append("{\"addr\":").append(q(e.getKey())).append(",\"name\":").append(q(f[0])).append(",\"rssi\":").append(f[1]).append(",\"age\":").append(now - Long.parseLong(f[2])).append('}');
    }
    b.append("],\"devs\":["); first = true;
    for (Dev d : devs.values()) {
      if (!first) b.append(','); first = false;
      b.append("{\"addr\":").append(q(d.addr)).append(",\"name\":").append(q(d.name)).append(",\"state\":").append(q(d.state)).append(",\"why\":").append(q(d.why))
        .append(",\"linked\":").append(d.g != null && d.connAt > 0)
        .append(",\"color\":").append(d.color).append(",\"devType\":").append(d.devType).append(",\"bat\":").append(d.bat)
        .append(",\"seq\":").append(d.seq).append(",\"btn\":").append(d.btn).append(",\"acc\":").append(d.acc)
        .append(",\"ax\":").append(d.ax).append(",\"ay\":").append(d.ay).append(",\"az\":").append(d.az).append(",\"ext\":").append(d.ext)
        .append(",\"d0\":").append(d.d0).append(",\"gaps\":").append(d.seqGaps).append(",\"msgs\":").append(d.msgs).append(",\"n51\":").append(d.n51).append(",\"n53\":").append(d.n53)
        .append(",\"streaming\":").append(d.streaming).append(",\"wtype\":").append(q(wt(d.wtype))).append(",\"props\":").append(q(d.props)).append(",\"mtu\":").append(d.mtu)
        .append(",\"age\":").append(d.lastAt > 0 ? now - d.lastAt : -1).append(",\"d0age\":").append(d.lastD0 > 0 ? now - d.lastD0 : -1)
        .append(",\"raw\":").append(q(d.raw)).append(",\"last51\":").append(q(d.last51)).append(",\"last53\":").append(q(d.last53))
        .append(",\"lastEv\":").append(q(d.lastEv)).append(",\"lastOther\":").append(q(d.lastOther)).append('}');
    }
    b.append("],\"logN\":").append(log.size() + dropped).append(",\"log\":[");
    first = true;
    int from = Math.max(0, log.size() - 40), i = 0;
    for (String s : log) { if (i++ < from) continue; if (!first) b.append(','); first = false; b.append(q(s)); }
    return b.append("]}").toString();
  }
  /** v4.22: everything the trigger engine needs in one call: button edges since an id + live readings. */
  public synchronized String events(long since) {
    long now = SystemClock.elapsedRealtime();
    StringBuilder b = new StringBuilder("{\"now\":").append(now).append(",\"last\":").append(evId).append(",\"ev\":[");
    boolean first = true;
    for (Object[] e : evs) { if ((Long) e[0] <= since) continue; if (!first) b.append(','); first = false;
      b.append('[').append(e[0]).append(',').append(q((String) e[1])).append(',').append(e[2]).append(',').append(e[3]).append(',').append(q((String) e[4])).append(']'); }
    b.append("],\"devs\":["); first = true;
    for (Dev d : devs.values()) { if (!first) b.append(','); first = false;
      b.append("{\"addr\":").append(q(d.addr)).append(",\"state\":").append(q(d.state)).append(",\"linked\":").append(d.g != null && d.connAt > 0)
       .append(",\"color\":").append(d.color).append(",\"btn\":").append(d.btn).append(",\"acc\":").append(d.acc)
       .append(",\"ax\":").append(d.ax).append(",\"ay\":").append(d.ay).append(",\"az\":").append(d.az)
       .append(",\"d0\":").append(d.d0).append(",\"d0age\":").append(d.lastD0E > 0 ? now - d.lastD0E : -1).append(",\"raw\":").append(q(d.raw)).append('}'); }
    return b.append("]}").toString();
  }
  /** Full log as plain text, for Copy log / Share log. */
  public synchronized String fullLog() {
    StringBuilder b = new StringBuilder("Anubis Pawprint test log · " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())
      + "\nAndroid " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ") · " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
    for (Dev d : devs.values()) b.append("slot ").append(d.addr).append(" ").append(d.name).append(" · ").append(d.state).append(d.why.isEmpty() ? "" : " (" + d.why + ")")
      .append(" · 0x150A ").append(d.props).append(" · ").append(wt(d.wtype)).append(" · msgs ").append(d.msgs).append(" · 51×").append(d.n51).append(" · 53×").append(d.n53)
      .append(" · D0×").append(d.d0).append(" gaps ").append(d.seqGaps).append("\n  services ").append(d.svcs).append('\n');
    if (dropped > 0) b.append("(").append(dropped).append(" oldest lines dropped)\n");
    for (String s : log) b.append(s).append('\n');
    return b.toString();
  }
}

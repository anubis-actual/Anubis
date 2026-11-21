package com.anubis.app;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coyote 3 BLE — DG-LAB open protocol coyote/v3 @ 0155a23.
 *
 * Scan strategy (post firmware 4.0 / issue #48):
 *  - DO NOT filter by LocalName or by service UUID in the advert — privacy
 *    broadcast omits LocalName and may omit 0x180C from the advertisement.
 *  - Match Howl: unfiltered LE scan; prefer name "47L121000" when present
 *    (pairing mode / dials held restores discoverability).
 *  - Debug mode lists every nearby BLE device so the user can pick manually.
 *  - After GATT connect, REQUIRE service 0x180C or disconnect.
 */
@SuppressLint("MissingPermission")
public class CoyoteBle {
  static final String TAG = "CoyoteBle";
  static final UUID S_MAIN = u(0x180C), C_WRITE = u(0x150A), C_NOTIFY = u(0x150B);
  static final UUID S_INFO = u(0x180A), C_BATT = u(0x1500);
  static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  static final String COYOTE_NAME = "47L121000";

  static UUID u(int s) {
    return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", s));
  }

  public interface Listener { void on(String e, String j); }

  final Context ctx;
  /** v4.31: swappable, so a re-created Activity re-uses this one helper (and its live GATT) and receives its events */
  volatile Listener L;
  static final java.util.concurrent.atomic.AtomicInteger INSTANCES = new java.util.concurrent.atomic.AtomicInteger();
  BluetoothAdapter ad;
  BluetoothGatt gatt;
  BluetoothGattCharacteristic wCh, nCh, bCh;
  ScanCallback scanCb;
  Handler h = new Handler(Looper.getMainLooper());
  final AtomicInteger seq = new AtomicInteger(0);
  volatile boolean connected, armed, wantRun, debugScan, bg;
  // A hardware zero wins over queued JS/Auto output until the page acknowledges cleanup.
  volatile boolean hardwareStop;
  int hardwareStopToken;
  boolean notifyReady;
  volatile String addr, name = "Coyote 3";
  volatile int bat = 100, rssi = -60, capA = 100, capB = 100, devA = 0, devB = 0;
  volatile int faA = 200, faB = 200, iaA = 0, iaB = 0;
  final int[] fA = new int[4], iA = new int[4], fB = new int[4], iB = new int[4];
  volatile int tgtA = 0, tgtB = 0;
  /** v4.30: the A / B strength in the last B0 actually written to the unit (after Max Output, soft start and the native caps), and when. Feeds the overlay bubble. */
  volatile int outA = 0, outB = 0; volatile long outAt = 0;
  final int[] sentA = new int[16], sentB = new int[16]; final long[] sentAt = new long[16];
  volatile long lastJs = System.currentTimeMillis();
  int connectAttempts;
  final Set<String> seenLog = new HashSet<String>();
  /** v4.62: strength handshake, unit wheel turns / presses, and the shareable BLE log (every inbound B1, every strength write). */
  final CoyoteLink link = new CoyoteLink(new CoyoteLink.Out() {
    public void stop(String why, int a, int b) {
      // Runs on the BLE handler thread inside handleNotification, before the next 100 ms writer tick.
      hardwareStop = true; hardwareStopToken++;
      stopNow(); // absolute 0 on both channels + zero pulse intensity, written immediately
      emit("dialStop", "{\"token\":" + hardwareStopToken + ",\"a\":" + a + ",\"b\":" + b + ",\"why\":" + q(why) + "}");
    }
    public void dial(int n, char ch, int d, int lvl) {
      emit("dial", "{\"n\":" + n + ",\"ch\":\"" + ch + "\",\"d\":" + d + ",\"lvl\":" + lvl + "}");
    }
  });

  Runnable tick = new Runnable() {
    public void run() {
      try { step(); } catch (Exception e) { Log.e(TAG, "tick", e); }
      h.postDelayed(this, 100);
    }
  };

  public CoyoteBle(Context c, Listener l) {
    ctx = c.getApplicationContext();
    L = l;
    INSTANCES.incrementAndGet();
    try { ad = BluetoothAdapter.getDefaultAdapter(); }
    catch (Throwable t) { ad = null; Log.e(TAG, "adapter", t); }
    for (int i = 0; i < 4; i++) { fA[i] = fB[i] = 10; iA[i] = iB[i] = 0; }
    try { h.post(tick); } catch (Throwable t) { Log.e(TAG, "tick schedule", t); }
    log("BLE helper ready · adapter=" + (ad == null ? "null" : (ad.isEnabled() ? "on" : "off")));
  }

  void emit(String e, String j) { try { L.on(e, j); } catch (Throwable ignored) {} }
  void log(String msg) {
    Log.i(TAG, msg);
    link.log(System.currentTimeMillis(), msg);
    emit("log", "{\"msg\":" + q(msg) + ",\"t\":" + System.currentTimeMillis() + "}");
  }

  public synchronized void setCaps(int a, int b) {
    capA = clamp(a, 0, 200); capB = clamp(b, 0, 200);
    link.caps(capA, capB, System.currentTimeMillis());
    if (connected) writeBf();
  }

  public synchronized void setTargets(int a, int b, int[] fa, int[] ia, int[] fb, int[] ib) {
    lastJs = System.currentTimeMillis();
    if (hardwareStop) return;
    tgtA = clamp(a, 0, capA); tgtB = clamp(b, 0, capB);
    if (fa != null) for (int i = 0; i < 4; i++) {
      fA[i] = clamp(fa[i], 10, 240); iA[i] = clamp(ia[i], 0, 100);
      fB[i] = clamp(fb[i], 10, 240); iB[i] = clamp(ib[i], 0, 100);
    }
  }

  public void stopNow() {
    tgtA = tgtB = 0;
    for (int i = 0; i < 4; i++) { iA[i] = iB[i] = 0; }
    sendB0(true);
  }

  public synchronized void ackHardwareStop(int token) {
    if (hardwareStop && token == hardwareStopToken) {
      tgtA = tgtB = 0;
      hardwareStop = false;
      link.unlatch(System.currentTimeMillis());
      log("Hardware STOP cleanup acknowledged · output remains 0");
    }
  }

  /** v4.62: the page adopted unit wheel turn n (its next setTargets already carries the new level). */
  public void dialAck(int n) { link.ack(n, System.currentTimeMillis()); }

  /** v4.62: the BLE log as text, newest last. */
  public String bleLog() {
    return "Anubis Coyote BLE log · " + new java.util.Date() + "\n"
      + "unit " + name + " " + (addr == null ? "" : addr) + " · connected=" + connected + " · caps A=" + capA + " B=" + capB
      + " · unit level A=" + link.devA + " B=" + link.devB + " · presses " + link.pressN + " · wheel turns " + link.dialN
      + " · strength writes " + link.txCount + "\n"
      + "Lines: RX B1 = every notification from the unit (seq 0 = the unit's own change), TX B0 = every strength write (pulse-only B0 are counted, not listed).\n\n"
      + link.dump();
  }
  public void clearBleLog() { link.clearLog(System.currentTimeMillis()); }

  public void heartbeat() { lastJs = System.currentTimeMillis(); }

  public String statusJson() {
    return "{\"connected\":" + connected + ",\"armed\":" + armed
      + ",\"name\":" + q(name) + ",\"addr\":" + q(addr == null ? "" : addr)
      + ",\"battery\":" + bat + ",\"rssi\":" + rssi
      + ",\"capA\":" + capA + ",\"capB\":" + capB
      + ",\"devA\":" + devA + ",\"devB\":" + devB
      + ",\"btOn\":" + (ad != null && ad.isEnabled())
      + ",\"demo\":false}";
  }

  static String q(String s) {
    if (s == null) s = "";
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
  static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

  /** Unfiltered scan. debug=true → emit every BLE device; false → only Coyote-like names. */
  public void startScan(boolean debug) {
    debugScan = debug;
    seenLog.clear();
    if (ad == null) { log("ERROR: no Bluetooth adapter"); emit("error", "{\"msg\":\"No Bluetooth adapter\"}"); return; }
    if (!ad.isEnabled()) { log("ERROR: Bluetooth is off"); emit("error", "{\"msg\":\"Bluetooth off\"}"); return; }
    BluetoothLeScanner sc = ad.getBluetoothLeScanner();
    if (sc == null) { log("ERROR: no LE scanner"); emit("error", "{\"msg\":\"No LE scanner\"}"); return; }
    stopScan();
    log(debug ? "Debug scan: listing ALL nearby BLE (no filter)…"
              : "Scanning unfiltered for Coyote 3 (name 47L121000 when advertising)…");
    log("TIP: power on the unit, then pull BOTH dials DOWN and hold until yellow/blue flash (pairing mode).");
    emit("scan", "{\"state\":\"start\",\"debug\":" + debug + "}");

    scanCb = new ScanCallback() {
      public void onScanResult(int c, ScanResult r) { onFound(r); }
      public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) onFound(r); }
      public void onScanFailed(int e) {
        log("Scan FAILED code=" + e + " (1=already started 2=app reg 3=internal 4=feature unsupported 5=out of hw resources 6=scanning too frequently)");
        emit("error", "{\"msg\":\"scan failed " + e + "\"}");
      }
    };
    // CRITICAL: null filter list — firmware 4.0 privacy broadcast may omit LocalName AND 0x180C from adv
    ScanSettings ss = new ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
      .build();
    try {
      sc.startScan(/* filters */ null, ss, scanCb);
      log("startScan(null filters) OK");
    } catch (SecurityException se) {
      log("ERROR: SecurityException starting scan — need BLUETOOTH_SCAN granted: " + se.getMessage());
      emit("error", "{\"msg\":\"Scan permission denied\"}");
    } catch (Exception e) {
      log("ERROR startScan: " + e);
      emit("error", "{\"msg\":" + q(String.valueOf(e.getMessage())) + "}");
    }
    h.postDelayed(new Runnable() {
      public void run() {
        if (scanCb != null) {
          log("Scan window ended (120s). If nothing was found: hold both dials down (pairing) + Location ON (Samsung).");
          stopScan();
          emit("scanEnd", "{}");
        }
      }
    }, 120000);
  }

  public void startScan() { startScan(false); }

  void onFound(ScanResult r) {
    try {
      BluetoothDevice d = r.getDevice();
      String a = d.getAddress();
      ScanRecord rec = r.getScanRecord();
      String advName = rec != null ? rec.getDeviceName() : null;
      String devName = null;
      try { devName = d.getName(); } catch (SecurityException ignored) {}
      String n = advName != null ? advName : (devName != null ? devName : "");
      int rssiV = r.getRssi();
      boolean has180c = false;
      if (rec != null && rec.getServiceUuids() != null) {
        for (ParcelUuid pu : rec.getServiceUuids()) {
          if (S_MAIN.equals(pu.getUuid())) { has180c = true; break; }
        }
      }
      byte[] mfg = rec != null ? rec.getManufacturerSpecificData(0xFFFF) : null; // unused; just logging presence
      String mfgNote = (rec != null && rec.getManufacturerSpecificData() != null && rec.getManufacturerSpecificData().size() > 0)
        ? " mfgKeys=" + rec.getManufacturerSpecificData().size() : "";

      String remembered = ctx.getSharedPreferences("coyote", 0).getString("addr", "");
      /* v4.20: only names starting 47L121 are a Coyote. 47L120… is a Pawprint (same 0x180C service) and is never
         treated, auto-picked or remembered as the Coyote. An unnamed 0x180C device is an "unknown DG-Lab device":
         listed so it can be picked by hand, never auto-picked; the name is checked again after connect. */
      boolean paw = isPawName(n) || isPawAddr(a);
      boolean unk = !paw && n.isEmpty() && has180c;
      boolean coyoteLike = !paw && (isCoyoteName(n)
        || (!remembered.isEmpty() && remembered.equalsIgnoreCase(a)));
      if (!seenLog.contains(a)) {
        seenLog.add(a);
        log("Saw " + a + " rssi=" + rssiV
          + " name=" + (n.isEmpty() ? "(none)" : n)
          + (has180c ? " [0x180C in adv]" : "")
          + mfgNote);
      }
      if (paw) return; /* Pawprints belong to the Accessories test screen (PawBle), never the Coyote list */
      if (!debugScan && !coyoteLike && !unk) return;

      String label = n.isEmpty() ? (coyoteLike ? "Coyote 3?" : unk ? "Unknown DG-Lab device" : "BLE device") : n;
      if (coyoteLike && n.isEmpty()) label = "Coyote 3 (remembered)";
      emit("found", "{\"name\":" + q(label) + ",\"addr\":" + q(a)
        + ",\"rssi\":" + rssiV + ",\"coyote\":" + coyoteLike + ",\"unk\":" + unk
        + ",\"hasService\":" + has180c + ",\"rawName\":" + q(n) + "}");
    } catch (Throwable t) { log("onFound error: " + t); }
  }

  static boolean isCoyoteName(String n) {
    if (n == null || n.isEmpty()) return false;
    String u = n.toUpperCase(Locale.US);
    if (u.startsWith("47L120")) return false;
    return u.startsWith("47L121") || u.contains("COYOTE");
  }

  /** v4.20: DG-Lab Pawprints advertise as 47L120… */
  static boolean isPawName(String n) {
    return n != null && n.toUpperCase(Locale.US).startsWith("47L120");
  }

  /** an address the Accessories test screen has seen as a Pawprint (prefs "paw") */
  boolean isPawAddr(String a) {
    if (a == null || a.isEmpty()) return false;
    try { return ctx.getSharedPreferences("paw", 0).getString("addrs", "").toUpperCase(Locale.US).contains(a.toUpperCase(Locale.US)); }
    catch (Exception e) { return false; }
  }

  public void stopScan() {
    if (ad == null || scanCb == null) return;
    try { ad.getBluetoothLeScanner().stopScan(scanCb); } catch (Exception ignored) {}
    scanCb = null;
    emit("scan", "{\"state\":\"stop\"}");
  }

  public void connect(String a) {
    stopScan();
    if (ad == null) { log("connect: no adapter"); return; }
    connectAttempts = 0;
    doConnect(a);
  }

  volatile int connGen = 0; volatile boolean wantConnectWatch = true;
  void doConnect(String a) {
    connectAttempts++;
    try {
      BluetoothDevice d = ad.getRemoteDevice(a);
      addr = a;
      name = "Coyote 3";
      try {
        String dn = d.getName();
        if (dn != null && !dn.isEmpty()) name = dn;
      } catch (Exception ignored) {}
      log("GATT connect attempt " + connectAttempts + " → " + a + " (autoConnect=false, TRANSPORT_LE)");
      if (gatt != null) try { gatt.close(); } catch (Exception ignored) {}
      gatt = null;
      if (Build.VERSION.SDK_INT >= 23)
        gatt = d.connectGatt(ctx, /*autoConnect*/ false, cb, BluetoothDevice.TRANSPORT_LE);
      else
        gatt = d.connectGatt(ctx, false, cb);
      emit("connecting", "{\"addr\":" + q(a) + ",\"attempt\":" + connectAttempts + "}");
      final int gen = ++connGen;
      h.postDelayed(new Runnable() { public void run() {
        if (gen == connGen && !armed && wantConnectWatch) {
          log("Connect timeout (20s) - closing GATT");
          try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Exception ignored) {}
          gatt = null; connected = false;
          emit("error", "{\"msg\":\"Connection timed out. Hold both dials down on the unit and try again.\"}");
        }
      }}, 20000);
    } catch (SecurityException se) {
      log("ERROR connect SecurityException — need BLUETOOTH_CONNECT: " + se.getMessage());
      emit("error", "{\"msg\":\"Connect permission denied\"}");
    } catch (Exception e) {
      log("ERROR connect: " + e);
      emit("error", "{\"msg\":" + q(String.valueOf(e.getMessage())) + "}");
    }
  }

  public void disconnect() {
    wantRun = false;
    connGen++;
    stopNow();
    if (gatt != null) {
      try { gatt.disconnect(); } catch (Exception ignored) {}
      try { gatt.close(); } catch (Exception ignored) {}
    }
    gatt = null; connected = armed = false;
    emit("disconnected", "{}");
    log("Disconnected");
  }

  public void remember(String a, String n) {
    if (isPawName(n) || isPawName(name) && a != null && a.equalsIgnoreCase(addr) || (a != null && !a.isEmpty() && isPawAddr(a))) {
      log("remember refused: " + a + " is a Pawprint (47L120…), not the Coyote");
      emit("error", "{\"msg\":\"That is a Pawprint, not the Coyote. It was not remembered.\"}");
      return;
    }
    ctx.getSharedPreferences("coyote", 0).edit()
      .putString("addr", a == null ? "" : a)
      .putString("name", n == null ? "" : n).apply();
  }

  public String remembered() {
    SharedPreferences p = ctx.getSharedPreferences("coyote", 0);
    String a = p.getString("addr", "");
    return a.isEmpty() ? "null" : "{\"addr\":" + q(a) + ",\"name\":" + q(p.getString("name", "")) + "}";
  }

  public void autoConnect() {
    SharedPreferences cp = ctx.getSharedPreferences("coyote", 0);
    if (isPawName(cp.getString("name", "")) || isPawAddr(cp.getString("addr", ""))) {
      log("Remembered unit was a Pawprint (47L120…) - forgetting it");
      cp.edit().putString("addr", "").putString("name", "").apply();
    }
    String a = cp.getString("addr", "");
    if (!a.isEmpty()) {
      log("Remembered address " + a + " — trying direct GATT (no scan)");
      connect(a);
    } else {
      log("No remembered address — starting scan");
      startScan(false);
    }
  }

  final BluetoothGattCallback cb = new BluetoothGattCallback() {
    public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
      log("onConnectionStateChange status=" + status + " newState=" + newState
        + " (0=OK, 133=GATT_ERROR often needs retry)");
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
          log("Connected with non-success status " + status + " — continuing anyway");
        }
        connected = true;
        connectAttempts = 0;
        link.reset("connected " + addr, System.currentTimeMillis());
        emit("connected", "{\"name\":" + q(name) + ",\"addr\":" + q(addr) + "}");
        try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) {}
        try {
          boolean mtu = g.requestMtu(64);
          log("requestMtu(64) → " + mtu);
          if (!mtu) g.discoverServices();
        } catch (Exception e) {
          log("requestMtu failed, discoverServices directly: " + e);
          g.discoverServices();
        }
        try { g.readRemoteRssi(); } catch (Exception ignored) {}
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        boolean was = connected;
        connected = armed = false;
        // GATT 133 / transient: retry a few times
        if (was == false && status == 133 && connectAttempts < 4 && addr != null) {
          log("GATT 133 — retrying in 800ms (attempt " + connectAttempts + ")");
          final String retryAddr = addr;
          try { g.close(); } catch (Exception ignored) {}
          gatt = null;
          h.postDelayed(new Runnable() {
            public void run() { doConnect(retryAddr); }
          }, 800);
          return;
        }
        try { g.close(); } catch (Exception ignored) {}
        gatt = null;
        stopNow();
        emit("disconnected", "{\"was\":" + was + ",\"status\":" + status + "}");
        log("Disconnected was=" + was + " status=" + status);
      }
    }

    public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
      log("onMtuChanged mtu=" + mtu + " status=" + status);
      g.discoverServices();
    }

    public void onServicesDiscovered(BluetoothGatt g, int st) {
      log("onServicesDiscovered status=" + st);
      if (st != BluetoothGatt.GATT_SUCCESS) {
        log("Service discovery failed status=" + st);
        emit("error", "{\"msg\":\"service discovery " + st + "\"}");
        return;
      }
      // Log all services for debug
      for (BluetoothGattService s : g.getServices()) {
        log("  service " + s.getUuid());
      }
      /* v4.20: a Pawprint (47L120…) has the same 0x180C service; after connect, its GATT name drops the Coyote role */
      String gn = null;
      try { gn = g.getDevice().getName(); } catch (Exception ignored) {}
      if (isPawName(gn) || isPawName(name)) {
        log("ERROR: " + gn + " is a Pawprint (47L120…), not a Coyote. Disconnecting.");
        try {
          SharedPreferences cp = ctx.getSharedPreferences("coyote", 0);
          if (addr != null && addr.equalsIgnoreCase(cp.getString("addr", ""))) cp.edit().putString("addr", "").putString("name", "").apply();
        } catch (Exception ignored) {}
        emit("error", "{\"msg\":\"That is a Pawprint, not the Coyote. Use Settings → Accessories → Test for Pawprints.\"}");
        disconnect();
        return;
      }
      BluetoothGattService s = g.getService(S_MAIN);
      if (s == null) {
        log("ERROR: service 0x180C not found — this is not a Coyote 3 (or wrong device). Disconnecting.");
        emit("error", "{\"msg\":\"No Coyote service 0x180C on this device\"}");
        disconnect();
        return;
      }
      wCh = s.getCharacteristic(C_WRITE);
      nCh = s.getCharacteristic(C_NOTIFY);
      BluetoothGattService si = g.getService(S_INFO);
      if (si != null) bCh = si.getCharacteristic(C_BATT);
      if (wCh == null || nCh == null) {
        log("ERROR: missing 0x150A write or 0x150B notify");
        emit("error", "{\"msg\":\"missing Coyote characteristics\"}");
        disconnect();
        return;
      }
      log("Got 0x180C / write 0x150A / notify 0x150B" + (bCh != null ? " / battery 0x1500" : ""));
      // Enable notify (must complete CCCD write before other writes).
      notifyReady = false;
      if (!g.setCharacteristicNotification(nCh, true)) {
        log("ERROR: 150B notification registration failed"); disconnect(); return;
      }
      BluetoothGattDescriptor d = nCh.getDescriptor(CCCD);
      if (d != null) {
        if (Build.VERSION.SDK_INT >= 33) {
          int rc = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
          log("writeDescriptor CCCD (API33+) rc=" + rc);
          if (rc != 0) { disconnect(); return; }
        } else {
          d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
          boolean ok = g.writeDescriptor(d);
          log("writeDescriptor CCCD → " + ok);
          if (!ok) { disconnect(); return; }
        }
      } else {
        log("ERROR: no CCCD on 150B — cannot safely monitor hardware STOP");
        disconnect();
      }
    }

    public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
      log("onDescriptorWrite " + d.getUuid() + " status=" + status);
      // Battery CCCD has the SAME UUID. Do not recursively re-enable it / re-run READY.
      if (CCCD.equals(d.getUuid()) && C_NOTIFY.equals(d.getCharacteristic().getUuid())) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
          log("ERROR: 150B subscription failed status=" + status); disconnect(); return;
        }
        if (!notifyReady) { notifyReady = true; afterNotifyEnabled(g); }
      }
    }

    void afterNotifyEnabled(BluetoothGatt g) {
      if (bCh != null) {
        try {
          g.setCharacteristicNotification(bCh, true);
          BluetoothGattDescriptor bd = bCh.getDescriptor(CCCD);
          if (bd != null) {
            if (Build.VERSION.SDK_INT >= 33)
              g.writeDescriptor(bd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            else {
              bd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
              g.writeDescriptor(bd);
            }
          }
          // battery read after a beat so we don't collide with CCCD write
          h.postDelayed(new Runnable() {
            public void run() {
              try { g.readCharacteristic(bCh); } catch (Exception ignored) {}
              finishReady();
            }
          }, 300);
          return;
        } catch (Exception e) { log("battery setup: " + e); }
      }
      finishReady();
    }

    void finishReady() {
      writeBf();
      armed = true;
      wantRun = true;
      /* v4.1: no silent remember - the app asks the user first */
      log("READY — BF soft limits written A=" + capA + " B=" + capB);
      emit("ready", statusJson());
    }

    public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
      if (Build.VERSION.SDK_INT < 33) receive(g, c, c.getValue());
    }

    public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
      receive(g, c, value);
    }

    void receive(final BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
      if (value == null || value.length == 0) return;
      final UUID id = c.getUuid();
      final byte[] v = value.clone(); // API 33's immutable value, not c.getValue()'s stale cache
      h.post(new Runnable() { public void run() { handleNotification(g, id, v); } });
    }

    public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
      if (c.getUuid().equals(C_BATT) && c.getValue() != null) {
        bat = c.getValue()[0] & 0xff;
        emit("battery", "{\"pct\":" + bat + "}");
        log("Battery read " + bat + "%");
      }
    }

    public void onReadRemoteRssi(BluetoothGatt g, int r, int st) {
      rssi = r;
      emit("rssi", "{\"rssi\":" + r + "}");
      final BluetoothGatt gg = g;
      h.postDelayed(new Runnable() {
        public void run() {
          if (connected) try { gg.readRemoteRssi(); } catch (Exception ignored) {}
        }
      }, 3000);
    }
  };

  static String hex(byte[] value) {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < value.length && i < 20; i++) {
      if (i > 0) s.append(' ');
      s.append(String.format("%02X", value[i] & 0xff));
    }
    return s.toString();
  }

  synchronized void handleNotification(BluetoothGatt g, UUID id, byte[] v) {
    if (g != gatt) return; // ignore notifications from an obsolete connection
    long now = System.currentTimeMillis();
    if (C_NOTIFY.equals(id)) {
      if (v[0] != (byte) 0xB1 || v.length < 4) {
        // v4.62: the protocol defines nothing but B1 here. While output is up, treat anything else as a press.
        link.onOther(hex(v), outA > 0 || outB > 0 || tgtA > 0 || tgtB > 0 || link.devA > 0 || link.devB > 0, now);
        return;
      }
      int a = v[2] & 0xff, b = v[3] & 0xff, bs = v[1] & 0xff;
      if (a > 200 || b > 200 || bs > 15) { link.log(now, "RX B1 raw=" + hex(v) + " invalid, ignored"); return; }
      int ea = bs > 0 ? link.sentA[bs] : -1, eb = bs > 0 ? link.sentB[bs] : -1;
      String kind = link.onB1(bs, a, b, hex(v), now);   // may STOP (synchronously zeroes output) or report a wheel turn
      devA = a; devB = b;
      emit("b1", "{\"seq\":" + bs + ",\"a\":" + a + ",\"b\":" + b + ",\"kind\":\"" + kind + "\""
          + ",\"nativeStop\":" + hardwareStop + ",\"ca\":" + ea + ",\"cb\":" + eb + "}");
    } else if (C_BATT.equals(id)) {
      bat = v[0] & 0xff; emit("battery", "{\"pct\":" + bat + "}"); log("Battery " + bat + "%");
    }
  }

  synchronized void writeBf() {
    if (wCh == null || gatt == null) return;
    byte[] cmd = new byte[]{(byte) 0xBF, (byte) capA, (byte) capB, (byte) faA, (byte) faB, (byte) iaA, (byte) iaB};
    try {
      if (Build.VERSION.SDK_INT >= 33) {
        int rc = gatt.writeCharacteristic(wCh, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        log("BF write API33+ rc=" + rc);
      } else {
        wCh.setValue(cmd);
        wCh.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean ok = gatt.writeCharacteristic(wCh);
        log("BF write → " + ok);
      }
      emit("bf", "{\"a\":" + capA + ",\"b\":" + capB + "}");
    } catch (Exception e) { log("BF error: " + e); }
  }

  synchronized void sendB0(boolean forceZero) {
    if (!connected || wCh == null || gatt == null) return;
    forceZero = forceZero || hardwareStop;
    int wa = forceZero ? 0 : clamp(tgtA, 0, capA);
    int wb = forceZero ? 0 : clamp(tgtB, 0, capB);
    /* v4.62: CoyoteLink decides whether this B0 carries a strength change (seq 1..15, only the channel that differs,
       one outstanding) or is pulses only (seq 0, mode 0000), and holds a unit wheel turn until the page adopts it. */
    int[] p = link.plan(wa, wb, forceZero, System.currentTimeMillis());
    int s = p[0], mode = p[1], ba = p[2], bb = p[3], a = p[4], b = p[5];
    boolean zeroPulse = forceZero || link.stopLatched;
    if (s > 0) { sentA[s] = a; sentB[s] = b; sentAt[s] = System.currentTimeMillis(); }
    outA = zeroPulse ? 0 : a; outB = zeroPulse ? 0 : b; outAt = System.currentTimeMillis();
    byte[] cmd = new byte[20];
    cmd[0] = (byte) 0xB0;
    cmd[1] = (byte) ((s << 4) | mode);
    cmd[2] = (byte) ba;   // absolute level, or the size of a relative step
    cmd[3] = (byte) bb;
    for (int i = 0; i < 4; i++) {
      cmd[4 + i] = (byte) (zeroPulse ? 10 : fA[i]);
      cmd[8 + i] = (byte) (zeroPulse ? 0 : iA[i]);
      cmd[12 + i] = (byte) (zeroPulse ? 10 : fB[i]);
      cmd[16 + i] = (byte) (zeroPulse ? 0 : iB[i]);
    }
    try {
      // Howl / protocol: B0 is high-rate — NO_RESPONSE is appropriate
      if (Build.VERSION.SDK_INT >= 33) {
        gatt.writeCharacteristic(wCh, cmd, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
      } else {
        wCh.setValue(cmd);
        wCh.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        gatt.writeCharacteristic(wCh);
      }
    } catch (Exception e) { Log.e(TAG, "B0", e); }
  }

  void step() {
    long ago = System.currentTimeMillis() - lastJs;
    if (ago > 1000) {
      if (tgtA != 0 || tgtB != 0) {
        tgtA = tgtB = 0;
        for (int i = 0; i < 4; i++) iA[i] = iB[i] = 0;
        emit("watchdog", "{\"ago\":" + ago + ",\"bg\":" + bg + "}");
      }
    }
    if (connected && wantRun) sendB0(false);
    else { outA = 0; outB = 0; }
  }

  /** v4.30: live output for the bubble. 0 when nothing has been written for 1.5 s (link idle / not running). */
  int liveA() { return connected && System.currentTimeMillis() - outAt < 1500 ? outA : 0; }
  int liveB() { return connected && System.currentTimeMillis() - outAt < 1500 ? outB : 0; }
}

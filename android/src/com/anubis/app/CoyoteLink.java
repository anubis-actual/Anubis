package com.anubis.app;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * v4.62: Coyote 3 strength handshake + unit dial (wheel) handling. Pure Java (no Android types) so it can be
 * tested off-device with simulated B1 notifications interleaved with our B0 writes.
 *
 * DG-LAB V3 protocol (coyote/v3 README):
 *  - B0 byte1 = seq(4 bits) | mode(4 bits): mode bits per channel 00 keep, 01 +, 10 -, 11 absolute (A = high pair).
 *  - With seq != 0 the unit answers on 0x150B with B1 + that seq + actual A + actual B. Wait for it before the next
 *    strength change.
 *  - Any strength change made on the unit itself (wheel turn, wheel press) is reported as B1 with seq 0.
 *
 * Before v4.62 the app wrote ABSOLUTE A and B with a new seq every 100 ms and never waited, so every wheel change was
 * overwritten within 100 ms and a press could be swallowed into (or overwritten by) one of our own echoes.
 * Now: strength is only written when the wanted level differs from what the unit last reported, only for the channel
 * that differs, with one write outstanding at a time. Ticks with no strength change send B0 mode 0000 (pulses only),
 * which can never re-raise a level the unit dropped.
 */
final class CoyoteLink {
  interface Out {
    /** a unit press (or any unit-side drop to 0, or an unknown 150B message while output is up): STOP everything. */
    void stop(String why, int a, int b);
    /** a unit wheel turn: ch 'A'/'B', d = change, lvl = unit level now. The app adopts it and calls ack(n). */
    void dial(int n, char ch, int d, int lvl);
  }

  static final long ACK_TIMEOUT = 250, HOLD_MS = 1500, ZERO_REPEAT = 500;
  static final int LOG_MAX = 4000;

  final Out out;
  int devA = -1, devB = -1;          // last level the unit reported (-1 = unknown)
  int capA = 200, capB = 200;        // BF soft limits we last wrote
  boolean b1Seen, stopLatched;
  int pendSeq; long pendAt;
  final int[] sentA = new int[16], sentB = new int[16]; final long[] sentAt = new long[16];
  /** per seq: signed relative step written for the channel, or NONE for absolute / not written */
  final int[] relA = new int[16], relB = new int[16];
  static final int NONE = Integer.MIN_VALUE;
  boolean resync = true, absPend;
  int seqCtr;
  int holdA = -1, holdB = -1, holdN; long holdUntil;
  int dialN, pressN;
  long lastZeroAt = -1;
  long t0 = -1;
  final ArrayDeque<String> ring = new ArrayDeque<String>();
  int txCount, pulseOnly;

  CoyoteLink(Out o) { out = o; for (int i = 0; i < 16; i++) { sentA[i] = sentB[i] = -1; relA[i] = relB[i] = NONE; } }

  static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

  synchronized void reset(String why, long now) {
    devA = devB = -1; b1Seen = false; stopLatched = false; pendSeq = 0; holdA = holdB = -1; lastZeroAt = -1; resync = true;
    for (int i = 0; i < 16; i++) { sentA[i] = sentB[i] = -1; sentAt[i] = 0; relA[i] = relB[i] = NONE; }
    t0 = now;
    log(now, "LINK reset · " + why);
  }

  synchronized void log(long now, String s) {
    if (t0 < 0) t0 = now;
    String ts;
    try { ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(now)); } catch (Throwable e) { ts = String.valueOf(now); }
    ring.addLast(ts + " +" + (now - t0) + "ms " + s);
    while (ring.size() > LOG_MAX) ring.removeFirst();
  }

  synchronized String dump() {
    StringBuilder b = new StringBuilder();
    for (String s : ring) b.append(s).append('\n');
    return b.toString();
  }
  synchronized int lines() { return ring.size(); }
  synchronized void clearLog(long now) { ring.clear(); log(now, "log cleared"); }

  /**
   * Plan one B0. Returns {seq, mode, byteA, byteB, levelA, levelB}: mode 0 = pulses only (strength bytes ignored).
   * forceZero (STOP, watchdog, hardware press latch) always writes absolute 0 for both channels immediately,
   * whatever is outstanding, and repeats it until the unit confirms 0.
   * Raises and non-zero lowers are written as RELATIVE steps (+n / -n) from the level the unit last reported, so a
   * press the unit applied just before our write is never undone (0 + n stays low and the echo shows the press).
   * Absolute is used for 0, before the first B1, and once after a write got no echo (resync).
   */
  synchronized int[] plan(int wantA, int wantB, boolean forceZero, long now) {
    if (stopLatched) forceZero = true;
    if (pendSeq != 0 && now - pendAt >= ACK_TIMEOUT) {
      log(now, "no echo for seq " + pendSeq + " in " + ACK_TIMEOUT + " ms → next strength write is absolute (resync)");
      pendSeq = 0; resync = true;
    }
    if (forceZero) {
      holdA = holdB = -1;
      boolean send = devA != 0 || devB != 0 || lastZeroAt < 0 || now - lastZeroAt >= ZERO_REPEAT;
      if (!send) { pulseOnly++; return new int[]{0, 0, 0, 0, 0, 0}; }
      lastZeroAt = now;
      return write(0, 0, true, true, false, now, " ZERO");
    }
    lastZeroAt = -1;
    if (now < holdUntil) { if (holdA >= 0) wantA = holdA; if (holdB >= 0) wantB = holdB; }
    else { holdA = holdB = -1; }
    wantA = clamp(wantA, 0, capA); wantB = clamp(wantB, 0, capB);
    if (!b1Seen) {
      // No B1 from this unit yet: behave like v4.61 (absolute, every tick) so output still works if notify is broken.
      return write(wantA, wantB, true, true, false, now, " (no B1 yet)");
    }
    boolean needA = devA < 0 || wantA != devA, needB = devB < 0 || wantB != devB;
    if (!needA && !needB) { pulseOnly++; return new int[]{0, 0, 0, 0, wantA, wantB}; }
    boolean pendingLive = pendSeq != 0;
    boolean drop = (needA && wantA < devA) || (needB && wantB < devB);  // lowering never waits
    if (pendingLive && !drop) { pulseOnly++; return new int[]{0, 0, 0, 0, wantA, wantB}; }
    return write(wantA, wantB, needA, needB, !resync && !pendingLive, now, "");
  }

  private int[] write(int a, int b, boolean wA, boolean wB, boolean rel, long now, String note) {
    int s = (seqCtr++ % 15) + 1;   // 1..15; 0 is the unit's own changes
    int mA = 0, mB = 0, bA = 0, bB = 0;
    int rA = NONE, rB = NONE;
    if (wA) { if (rel && a != 0 && devA >= 0) { rA = a - devA; mA = rA > 0 ? 1 : 2; bA = Math.abs(rA); } else { mA = 3; bA = a; } }
    if (wB) { if (rel && b != 0 && devB >= 0) { rB = b - devB; mB = rB > 0 ? 1 : 2; bB = Math.abs(rB); } else { mB = 3; bB = b; } }
    int mode = (mA << 2) | mB;
    sentA[s] = wA ? a : -1; sentB[s] = wB ? b : -1; sentAt[s] = now; relA[s] = rA; relB[s] = rB;
    pendSeq = s; pendAt = now; txCount++;
    if (mA == 3 || mB == 3) absPend = true;
    log(now, "TX B0 seq=" + s + " mode=" + bits(mode) + " A=" + desc(wA, mA, bA, a) + " B=" + desc(wB, mB, bB, b) + note
        + (pulseOnly > 0 ? " (+" + pulseOnly + " pulse-only B0 since last)" : ""));
    pulseOnly = 0;
    return new int[]{s, mode, bA, bB, a, b};
  }

  static String desc(boolean w, int m, int byt, int lvl) {
    if (!w) return "keep";
    return m == 3 ? "=" + lvl : (m == 1 ? "+" : "-") + byt + "→" + lvl;
  }

  static String bits(int m) { String s = Integer.toBinaryString(m | 0x10); return s.substring(1); }

  /** Inbound B1. appA/appB = what the app currently wants (for logging). Returns the classification. */
  synchronized String onB1(int seq, int a, int b, String raw, long now) {
    b1Seen = true;
    int pa = devA, pb = devB;
    int xA, xB;                       // what the level should be if only we changed it
    boolean echo = seq != 0, relEcho = false;
    int stepA = 0, stepB = 0;
    if (echo) {
      boolean fresh = now - sentAt[seq] < 3000;
      int eA = fresh ? sentA[seq] : -1, eB = fresh ? sentB[seq] : -1;
      if (seq == pendSeq) pendSeq = 0;
      if (fresh && relA[seq] == NONE && relB[seq] == NONE && (eA >= 0 || eB >= 0)) resync = false; // an absolute write came back
      // relative step: the level the unit had just before (pa, includes every earlier report) plus our step
      xA = !fresh || eA < 0 ? pa : (relA[seq] != NONE ? (pa < 0 ? -1 : clamp(pa + relA[seq], 0, capA)) : eA);
      xB = !fresh || eB < 0 ? pb : (relB[seq] != NONE ? (pb < 0 ? -1 : clamp(pb + relB[seq], 0, capB)) : eB);
      relEcho = fresh && (relA[seq] != NONE || relB[seq] != NONE);
      stepA = fresh && relA[seq] != NONE ? relA[seq] : 0; stepB = fresh && relB[seq] != NONE ? relB[seq] : 0;
      // If the unit folded a later write of ours into this echo, the level matches that write: still ours.
      if (fresh && a != xA && wroteSince(true, sentAt[seq], a, now)) xA = a;
      if (fresh && b != xB && wroteSince(false, sentAt[seq], b, now)) xB = b;
    } else { xA = pa; xB = pb; }
    devA = a; devB = b;
    String head = "RX B1 raw=" + raw + " seq=" + seq + " A=" + a + " B=" + b + " (was " + pa + "/" + pb
        + (echo ? ", sent " + (sentA[seq]) + "/" + (sentB[seq]) : "") + ")";
    if (xA < 0 || xB < 0) {
      // first report: just learn the baseline (a 0 here cannot be a press: nothing was known to be running)
      log(now, head + " → baseline");
      return "baseline";
    }
    // A BF soft-limit clamp (Max Output lowered / Boost released) is not the user: unit level == our cap, below before.
    boolean clampA = a == capA && xA > capA, clampB = b == capB && xB > capB;
    boolean dropA = a == 0 && xA > 0 && !clampA, dropB = b == 0 && xB > 0 && !clampB;
    // A relative raise that lands on a pressed (zeroed) unit comes back as just our step: that is a press too.
    if (relEcho) {
      if (stepA > 0 && a == stepA && xA > a && !clampA) dropA = true;
      if (stepB > 0 && b == stepB && xB > b && !clampB) dropB = true;
    }
    if (dropA || dropB) {
      pressN++;
      holdA = holdB = -1;
      String why = "unit press · " + (dropA && dropB ? "A+B" : dropA ? "A" : "B") + " dropped to 0" + (echo ? " (inside our echo seq " + seq + ")" : "");
      if (!stopLatched) {
        stopLatched = true;
        log(now, head + " → PRESS → STOP #" + pressN + " (" + why + ")");
        out.stop(why, a, b);
      } else log(now, head + " → press while already stopped (ignored)");
      return "press";
    }
    if (stopLatched) { log(now, head + " → ignored (STOP latched)"); return "latched"; }
    int dA = clampA ? 0 : a - xA, dB = clampB ? 0 : b - xB;
    // On an absolute echo a level BELOW what we sent can be a late BF clamp, so only rises count as wheel turns there.
    if (echo) {
      boolean absA = relA[seq] == NONE, absB = relB[seq] == NONE;
      if (dA < 0 && (absA || a == capA)) dA = 0;
      if (dB < 0 && (absB || b == capB)) dB = 0;
    }
    String cls = echo ? "echo" : (clampA || clampB ? "clamp" : "unit");
    if (dA != 0 || dB != 0) {
      cls = "dial";
      holdUntil = now + HOLD_MS; holdN = ++dialN;
      if (dA != 0) holdA = a;
      if (dB != 0) holdB = b;
      log(now, head + " → DIAL #" + holdN + (dA != 0 ? " A" + sgn(dA) : "") + (dB != 0 ? " B" + sgn(dB) : "") + " · holding unit level until the app adopts it");
      if (dA != 0) out.dial(holdN, 'A', dA, a);
      if (dB != 0) out.dial(holdN, 'B', dB, b);
      return cls;
    }
    log(now, head + " → " + cls);
    return cls;
  }

  private boolean wroteSince(boolean chA, long t, int v, long now) {
    for (int i = 1; i < 16; i++) {
      if (sentAt[i] >= t && now - sentAt[i] < 3000 && (chA ? sentA[i] : sentB[i]) == v) return true;
    }
    return false;
  }

  static String sgn(int d) { return d > 0 ? "+" + d : String.valueOf(d); }

  /** Any non-B1 notification on 0x150B. The protocol defines none, so while output is up it is treated as a press. */
  synchronized String onOther(String raw, boolean outputUp, long now) {
    if (outputUp && !stopLatched) {
      stopLatched = true; pressN++;
      log(now, "RX 150B raw=" + raw + " (not B1) while output up → STOP #" + pressN);
      out.stop("unknown unit message " + raw, devA, devB);
      return "press";
    }
    log(now, "RX 150B raw=" + raw + " (not B1)");
    return "other";
  }

  synchronized void ack(int n, long now) {
    if (n == holdN) { holdA = holdB = -1; holdUntil = 0; log(now, "dial #" + n + " adopted by the app"); }
  }

  synchronized void unlatch(long now) { if (stopLatched) { stopLatched = false; log(now, "STOP cleanup acknowledged by the app · output stays 0 until you start again"); } }

  synchronized void caps(int a, int b, long now) { capA = clamp(a, 0, 200); capB = clamp(b, 0, 200); log(now, "BF caps A=" + capA + " B=" + capB); }
}

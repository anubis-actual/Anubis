package com.anubis.app;

/** Pure decision logic for the floating output bubble (unit-testable, no Android classes). */
final class OverlayPolicy {
  private OverlayPolicy() {}

  /** The bubble shows only while the app is NOT visible, the user enabled it, Android granted it and a Coyote is connected. */
  static boolean shouldShow(boolean enabled, boolean granted, boolean connected, boolean foreground) {
    return enabled && granted && connected && !foreground;
  }

  /**
   * What the saved "enabled" flag should become after the user returns from Android's settings screen.
   * pendingReturn = we sent the user there and they have now come back. A grant turns it on, anything else leaves it off.
   * Without a pending request, a revoked permission switches an enabled overlay off.
   * Returns {enabledAfter, reason} where reason is "", "denied" or "revoked".
   */
  static String[] reconcile(boolean enabled, boolean granted, boolean pendingReturn) {
    if (pendingReturn) return new String[]{granted ? "1" : "0", granted ? "" : "denied"};
    if (enabled && !granted) return new String[]{"0", "revoked"};
    return new String[]{enabled ? "1" : "0", ""};
  }

  /** v4.31: bubble text {label, A, B}. Connected: the values the B0 writer last sent, no label. Not connected: "No Coyote ·", A –, B –. Never sample numbers. */
  static String[] bubbleText(boolean connected, int a, int b) {
    return connected ? new String[]{"", "A " + a, "B " + b} : new String[]{"No Coyote ·", "A \u2013", "B \u2013"};
  }

  /** v4.31: while Anubis itself has a system screen open (picker, permission dialog, Save as, share, settings) leaving is not "leaving". */
  static boolean skipLeave(boolean internalLaunch) { return internalLaunch; }

  /** v4.31: one-line overlay diagnostic for the Data log and the Test toast. */
  static String diagLine(boolean connected, boolean armed, boolean run, int instances, boolean same, long sentAgeMs, int outA, int outB,
                         int tgtA, int tgtB, long jsAgoMs, boolean granted, boolean switchOn, boolean visible, boolean service, boolean showing, boolean test, String mode) {
    return "connected=" + yn(connected) + " armed=" + yn(armed) + " run=" + yn(run) + " bleInstances=" + instances + " bleSame=" + yn(same)
      + " lastSentAgeMs=" + (sentAgeMs < 0 ? "never" : String.valueOf(sentAgeMs)) + " A/B=" + outA + "/" + outB + " tgt=" + tgtA + "/" + tgtB
      + " jsAgoMs=" + (jsAgoMs < 0 ? "-" : String.valueOf(jsAgoMs)) + " granted=" + yn(granted) + " switch=" + (switchOn ? "on" : "off")
      + " visible=" + yn(visible) + " service=" + (service ? "running" : "off") + " showing=" + yn(showing) + " test=" + yn(test) + " mode=" + mode;
  }
  private static String yn(boolean v) { return v ? "yes" : "no"; }

  /** v4.37 compact bubble sizes for text size s / m / l (anything else = m).
   *  {text sp, body min dp (square), STOP dot visible dp, STOP dot touch dp, corner radius dp} */
  static int[] bubbleDims(String size) {
    if ("s".equals(size)) return new int[]{10, 52, 24, 40, 16};
    if ("l".equals(size)) return new int[]{15, 72, 32, 48, 22};
    return new int[]{12, 60, 28, 44, 18};
  }
  static String sizeOf(String size) { return "s".equals(size) || "l".equals(size) ? size : "m"; }
  /** opacity in whole percent, 20-100 (default 70) */
  static int opacityOf(int op) { return op < 20 ? 20 : op > 100 ? 100 : op; }
  /** the STOP dot never fades below 85 % so it stays easy to find */
  static float dotAlpha(int op) { return Math.max(opacityOf(op), 85) / 100f; }

  /** Keep a dragged position fully reachable on screen. */
  static int clamp(int v, int min, int max) { return max < min ? min : Math.max(min, Math.min(max, v)); }
}

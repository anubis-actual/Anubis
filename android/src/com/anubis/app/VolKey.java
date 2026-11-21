package com.anubis.app;

/** v4.49 volume-key emergency STOP (pure logic, unit-tested in tests49/VolKeyTest.java).
 *  Holding either volume key for HOLD_MS fires once. The first key-down and the key-up go to Android as usual,
 *  so a short press still changes the volume by one step; auto-repeat downs are swallowed while the setting is on,
 *  so a long hold does not run the volume to the end. A release, or the app losing focus, cancels the hold. */
final class VolKey {
  static final int UP = 24, DOWN = 25; // KeyEvent.KEYCODE_VOLUME_UP / _DOWN
  static final long HOLD_MS = 3000;
  volatile boolean on;
  int held = 0; long t0 = 0; boolean fired = false;
  static boolean isVol(int code) { return code == UP || code == DOWN; }
  /** @return true = consume the event (do not pass it to Android) */
  synchronized boolean down(int code, int repeat, long t) {
    if (!on || !isVol(code)) return false;
    if (repeat == 0 || held == 0) { held = code; t0 = t; fired = false; return repeat != 0; }
    return true;
  }
  synchronized boolean up(int code, long t) { if (isVol(code) && code == held) { held = 0; fired = false; } return false; }
  /** true exactly once when the key has been held HOLD_MS */
  synchronized boolean due(long t) { if (on && held != 0 && !fired && t - t0 >= HOLD_MS) { fired = true; return true; } return false; }
  synchronized void cancel() { held = 0; fired = false; }
}

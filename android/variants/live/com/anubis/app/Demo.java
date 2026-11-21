package com.anubis.app;

/** Live (main) build: the 120-minute demo clock does not exist here. Every hook is a no-op so behaviour is unchanged. */
final class Demo {
  static final boolean ENABLED = false;
  private Demo() {}
  static boolean gate(MainActivity a) { return false; }
  static void onResume(MainActivity a) {}
  static void onPause(MainActivity a) {}
  static void onStop(MainActivity a) {}
  static boolean exhausted() { return false; }
  static String info() { return "{\"demo\":false}"; }
}

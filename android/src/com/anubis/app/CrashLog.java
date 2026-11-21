package com.anubis.app;
import android.content.*;
import android.util.Log;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes uncaught exceptions to a file the UI can show on next launch. */
public class CrashLog {
  static final String TAG = "CoyoteCrash";
  static final String FILE = "last_crash.txt";

  public static void install(final Context app) {
    final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
      public void uncaughtException(Thread t, Throwable e) {
        write(app, e);
        if (prev != null) prev.uncaughtException(t, e);
        else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
      }
    });
  }

  public static void write(Context c, Throwable e) {
    try {
      StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
      String body = stamp + "\n" + e.getClass().getName() + ": " + e.getMessage() + "\n\n" + sw;
      FileOutputStream fos = c.openFileOutput(FILE, Context.MODE_PRIVATE);
      fos.write(body.getBytes("UTF-8"));
      fos.close();
      Log.e(TAG, body);
    } catch (Throwable ignored) {}
  }

  public static String read(Context c) {
    try {
      FileInputStream in = c.openFileInput(FILE);
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      byte[] buf = new byte[4096]; int n;
      while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
      in.close();
      return o.toString("UTF-8");
    } catch (Exception e) { return null; }
  }

  public static void clear(Context c) { c.deleteFile(FILE); }
}

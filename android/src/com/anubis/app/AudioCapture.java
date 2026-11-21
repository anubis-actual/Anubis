package com.anubis.app;

import android.media.AudioRecord;

/** Live PCM level analysis for Phone audio (playback capture) and Mic. Levels are polled by JS. */
public class AudioCapture {
  static volatile float loud, left, right;
  static volatile boolean running;
  static volatile boolean silent;     // digital silence for a few seconds
  static volatile long frames;
  static volatile long quietFrames;   // 25 ms frames since the last non-silent sample (or since capture began)
  static volatile boolean stereo;     // v4.8: Phone audio sends left/right band frames too; the Mic is mono only
  static final float[][] ring = new float[8][AudioAnalyzer.NF];  // last 8 raw frames (loud, L, R, mono/left/right bands), so JS can run its own gain / EQ / dynamics / metronome analysis at the full 40 fps

  static void reset() { loud = left = right = 0; silent = false; frames = 0; quietFrames = 0; synchronized (ring) { for (float[] r : ring) java.util.Arrays.fill(r, 0f); } }

  static String frJson() {
    StringBuilder b = new StringBuilder("[");
    synchronized (ring) {
      long n = frames;
      for (long k = Math.max(0, n - 6); k < n; k++) {
        float[] f = ring[(int) (k & 7)];
        if (k > Math.max(0, n - 6)) b.append(',');
        b.append('[').append(k);
        int nv = stereo ? AudioAnalyzer.NF : 21;
        for (int i = 0; i < nv; i++) { float v = f[i]; b.append(',').append(Float.isNaN(v) || Float.isInfinite(v) ? 0f : v); }
        b.append(']');
      }
    }
    return b.append(']').toString();
  }

  static String json() {
    return "{\"loud\":" + loud + ",\"left\":" + left + ",\"right\":" + right + ",\"silent\":" + silent + ",\"frames\":" + frames + ",\"quietMs\":" + (quietFrames * 25) + ",\"fr\":" + frJson() + "}";
  }

  /** Blocks until running=false or a read error. Call on its own thread. */
  static void run(AudioRecord rec, int sr, int ch, final String src) {
    stereo = "phone".equals(src);
    final float[] pk = {.05f, .05f, .05f};
    final int[] quiet = {0};
    final float[] ft = new float[3];
    quietFrames = 0;
    AudioAnalyzer.Sink sink = new AudioAnalyzer.Sink() {
      public void frame(float[] f, int peak16) {
        ft[0] = f[0]; ft[1] = f[1]; ft[2] = f[2];
        for (int k = 0; k < 3; k++) { pk[k] = Math.max(Math.max(.04f, ft[k]), pk[k] * .9985f); }
        loud = Math.min(1, ft[0] / pk[0]); left = Math.min(1, ft[1] / pk[1]); right = Math.min(1, ft[2] / pk[2]);
        synchronized (ring) { System.arraycopy(f, 0, ring[(int) (frames & 7)], 0, AudioAnalyzer.NF); frames++; }
        if (peak16 == 0) { quietFrames++; if (++quiet[0] > AudioAnalyzer.FPS * 5 && !silent) silent = true; }
        else { quiet[0] = 0; quietFrames = 0; if (silent) silent = false; }
      }
    };
    AudioAnalyzer.Kernel K = new AudioAnalyzer.Kernel(sr, sink);
    short[] buf = new short[Math.max(512, sr / 40) * ch];
    while (running) {
      int n = rec.read(buf, 0, buf.length);
      if (n < 0) { MainActivity.emit("audio", "{\"ok\":false,\"src\":" + MainActivity.json(src) + ",\"msg\":" + MainActivity.json("Android stopped the audio stream (error " + n + ").") + "}"); break; }
      int frs = n / ch;
      for (int i = 0; i < frs; i++) {
        int a = buf[i * ch], b = ch > 1 ? buf[i * ch + 1] : a;
        K.push(a / 32768f, b / 32768f, Math.max(Math.abs(a), Math.abs(b)));
      }
    }
    reset();
  }

  static String human(Throwable t) {
    String m = String.valueOf(t.getMessage());
    String lm = m.toLowerCase();
    if (lm.contains("foreground service") || lm.contains("foregroundservice") || lm.contains("service type"))
      return "Android refused to start the audio capture service. Please close and reopen the app, then try again.";
    if (t instanceof SecurityException)
      return "Android refused audio capture (a permission is missing). Allow Microphone / audio for Anubis in Settings → Apps → Permissions, then try again.";
    if (lm.contains("not initial"))
      return "Android couldn't open the audio capture stream. Another app may be using it, or the playing app blocks capture.";
    return "Audio capture failed: " + m;
  }
}

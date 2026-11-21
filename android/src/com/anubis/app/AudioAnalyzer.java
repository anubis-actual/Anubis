package com.anubis.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Shared audio feature kernel + streaming file analysis.
 * Feature order everywhere (matches ROUTES in index.html):
 * 0 loud, 1 beat, 2 bass, 3 mid, 4 high, 5 left, 6 right
 * Files are decoded as a stream (MediaExtractor + MediaCodec) so even very large audio/video files
 * never sit in memory: only 7 small feature series (40 per second) are kept.
 */
public class AudioAnalyzer {
  static final int FPS = 40;
  static final int MAX_FRAMES = FPS * 3600 * 3;           // 3 hours of features
  static final long HWL_MAX = 32L * 1024 * 1024;           // .hwl read whole, 32 MB max (~2M pulses)
  static volatile boolean cancel;
  static volatile byte[] result;
  static volatile String resultKind = "";

  public interface Sink { void frame(float[] f, int peak16); }

  /** Frame layout: [0]=loud (mean of L/R rms) [1]=left [2]=right [3..3+NB) = MONO band rms (bands of (L+R)/2), low to high,
   [3+NB..3+2NB) = LEFT band rms, [3+2NB..3+3NB) = RIGHT band rms (v4.8: per-channel analysis). */
  static final int NB = 18;               // band 0 < 40 Hz, bands 1..16 between log-spaced edges 40 Hz..8 kHz, band 17 > 8 kHz
  static final int NF = 3 + 3 * NB;
  static final double[] EDGE = new double[17];
  static { for (int i = 0; i < 17; i++) EDGE[i] = 40.0 * Math.pow(200.0, i / 16.0); }

  /** Streaming filter bank + RMS per 25 ms hop. */
  static class Kernel {
    final double hop; double next; long idx = 0;
    final float[] a = new float[17], y = new float[17], yl = new float[17], yr = new float[17];
    final double[] pw = new double[NB], pwl = new double[NB], pwr = new double[NB];
    double sl, sr2; int cnt; int pk;
    final Sink sink; final float[] f = new float[NF];
    Kernel(int sr, Sink s) {
      hop = sr / (double) FPS; next = hop; sink = s;
      for (int i = 0; i < 17; i++) a[i] = (float) (1 - Math.exp(-2 * Math.PI * Math.min(EDGE[i], sr * 0.45) / sr));
    }
    void push(float L, float R, int peak16) {
      float x = (L + R) * 0.5f, prev = 0, pl = 0, pr = 0;
      for (int i = 0; i < 17; i++) {
        y[i] += a[i] * (x - y[i]); float d = y[i] - prev; pw[i] += d * d; prev = y[i];
        yl[i] += a[i] * (L - yl[i]); float dl = yl[i] - pl; pwl[i] += dl * dl; pl = yl[i];
        yr[i] += a[i] * (R - yr[i]); float dr = yr[i] - pr; pwr[i] += dr * dr; pr = yr[i];
      }
      float e = x - prev; pw[17] += e * e;
      float el = L - pl; pwl[17] += el * el; float er = R - pr; pwr[17] += er * er;
      sl += L * L; sr2 += R * R;
      if (peak16 > pk) pk = peak16;
      cnt++; idx++;
      if (idx >= next) {
        next += hop;
        float n = Math.max(1, cnt);
        float left = (float) Math.sqrt(sl / n), right = (float) Math.sqrt(sr2 / n);
        f[0] = (left + right) * 0.5f; f[1] = left; f[2] = right;
        for (int j = 0; j < NB; j++) {
          f[3 + j] = (float) Math.sqrt(pw[j] / n); pw[j] = 0;
          f[3 + NB + j] = (float) Math.sqrt(pwl[j] / n); pwl[j] = 0;
          f[3 + 2 * NB + j] = (float) Math.sqrt(pwr[j] / n); pwr[j] = 0;
        }
        sink.frame(f, pk);
        sl = sr2 = 0; cnt = 0; pk = 0;
      }
    }
  }

  static String human(Throwable t, String mime) {
    String m = String.valueOf(t.getMessage());
    if (t instanceof SecurityException) return "Android didn't let the app read this file (permission). Pick it again with the Audio file button.";
    if (t instanceof OutOfMemoryError) return "This file is too large to analyze on this phone (out of memory).";
    if (t instanceof java.io.FileNotFoundException) return "The file could not be opened (moved, deleted, or the cloud file is not downloaded).";
    if (t instanceof IOException && m.contains("setDataSource")) return "Android could not open this file as audio/video. It may be damaged or an unsupported container.";
    if (t instanceof IllegalArgumentException || t instanceof IllegalStateException || t instanceof MediaCodec.CodecException || t instanceof IOException)
      return "Android can't decode this file's audio" + (mime != null ? " (" + mime + ")" : "") + ". Try MP3, AAC/M4A, WAV, OGG or FLAC, or a video with a normal audio track.";
    return "Couldn't read this file: " + m;
  }

  static void prog(int p) { MainActivity.emit("audioProg", "{\"p\":" + p + "}"); }

  /** Returns metadata JSON; bytes in result. Throws readable Exception (message is user-facing). */
  static String analyze(Context c, Uri uri, String name) throws Exception {
    cancel = false; result = null; resultKind = "";
    byte[] m = new byte[8]; int got = 0;
    InputStream in = null;
    try {
      in = c.getContentResolver().openInputStream(uri);
      if (in == null) throw new IOException("Could not open the file.");
      while (got < 8) { int r = in.read(m, got, 8 - got); if (r < 0) break; got += r; }
    } catch (Throwable t) {
      throw new Exception(human(t, null));
    } finally { try { if (in != null) in.close(); } catch (Throwable ignored) {} }
    boolean magic = got == 8 && "YEAHBOI!".equals(new String(m, "US-ASCII"));
    boolean hwlName = name != null && name.toLowerCase().endsWith(".hwl");
    if (hwlName && !magic) throw new Exception("This .hwl file has a wrong or missing header (expected YEAHBOI!), so it isn't a Howl waveform file.");
    if (magic) return readHwl(c, uri);
    return decode(c, uri);
  }

  static String readHwl(Context c, Uri uri) throws Exception {
    InputStream in = null;
    try {
      in = c.getContentResolver().openInputStream(uri);
      ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 20);
      byte[] b = new byte[65536]; long tot = 0; int n;
      while ((n = in.read(b)) > 0) {
        tot += n;
        if (tot > HWL_MAX) throw new Exception("This .hwl file is larger than 32 MB (about 14 hours of pulses). Too large to load.");
        o.write(b, 0, n);
        if (cancel) throw new Exception("Cancelled");
      }
      byte[] all = o.toByteArray();
      if (all.length < 8 + 16) throw new Exception("Empty .hwl file");
      if ((all.length - 8) % 16 != 0) throw new Exception("Damaged .hwl file (a partial pulse at the end)");
      result = all; resultKind = "hwl";
      int pulses = (all.length - 8) / 16;
      return "{\"kind\":\"hwl\",\"n\":" + pulses + ",\"sec\":" + (pulses / 40.0) + "}";
    } finally { try { if (in != null) in.close(); } catch (Throwable ignored) {} }
  }

  static String decode(Context c, Uri uri) throws Exception {
    MediaExtractor ex = new MediaExtractor();
    MediaCodec codec = null;
    String mime = null;
    try {
      try { ex.setDataSource(c, uri, null); }
      catch (Throwable t) { throw new Exception("Android could not open this file as audio/video. It may be damaged or in an unsupported container."); }
      int track = -1; MediaFormat fmt = null;
      for (int i = 0; i < ex.getTrackCount(); i++) {
        MediaFormat f = ex.getTrackFormat(i);
        String mm = f.getString(MediaFormat.KEY_MIME);
        if (mm != null && mm.startsWith("audio/")) { track = i; fmt = f; mime = mm; break; }
      }
      if (track < 0) throw new Exception("No audio track found in this file.");
      ex.selectTrack(track);
      long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
      try {
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, null, null, 0);
        codec.start();
      } catch (Throwable t) { throw new Exception(human(t, mime)); }

      int cap = durUs > 0 ? (int) Math.min(MAX_FRAMES, durUs / 1000000.0 * FPS + FPS * 5) : FPS * 600;
      final float[][] F = new float[NF][Math.max(cap, 1024)];
      final int[] nF = {0};
      Sink sink = new Sink() {
        public void frame(float[] f, int pk) {
          if (nF[0] >= MAX_FRAMES) return;
          if (nF[0] >= F[0].length) { for (int k = 0; k < NF; k++) F[k] = Arrays.copyOf(F[k], F[k].length * 2); }
          // (F is final reference to the outer array; element arrays replaced in place)
          for (int k = 0; k < NF; k++) F[k][nF[0]] = f[k];
          nF[0]++;
        }
      };
      int sr = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
      int chn = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
      int enc = AudioFormat.ENCODING_PCM_16BIT;
      Kernel K = new Kernel(sr, sink);
      MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
      boolean inEos = false, outEos = false; long lastProg = 0; int lastP = -1; long stall = 0;
      while (!outEos) {
        if (cancel) throw new Exception("Cancelled");
        if (!inEos) {
          int ii = codec.dequeueInputBuffer(5000);
          if (ii >= 0) {
            ByteBuffer ib = codec.getInputBuffer(ii);
            int sz = ex.readSampleData(ib, 0);
            if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
            else { codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0); ex.advance(); }
          }
        }
        int oi = codec.dequeueOutputBuffer(bi, 5000);
        if (oi >= 0) {
          stall = 0;
          if (bi.size > 0) {
            ByteBuffer ob = codec.getOutputBuffer(oi);
            ob.position(bi.offset); ob.limit(bi.offset + bi.size);
            ob.order(ByteOrder.LITTLE_ENDIAN);
            if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
              int frames = bi.size / 4 / chn; java.nio.FloatBuffer fb = ob.asFloatBuffer(); float[] tmp = new float[chn];
              for (int i = 0; i < frames; i++) {
                fb.get(tmp); float L = tmp[0], R = chn > 1 ? tmp[1] : L;
                K.push(L, R, (int) (Math.max(Math.abs(L), Math.abs(R)) * 32767));
              }
            } else {
              int frames = bi.size / 2 / chn; java.nio.ShortBuffer sb = ob.asShortBuffer(); short[] tmp = new short[chn];
              for (int i = 0; i < frames; i++) {
                sb.get(tmp); int a = tmp[0], b = chn > 1 ? tmp[1] : a;
                K.push(a / 32768f, b / 32768f, Math.max(Math.abs(a), Math.abs(b)));
              }
            }
          }
          codec.releaseOutputBuffer(oi, false);
          if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
        } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat of = codec.getOutputFormat();
          if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) { int nsr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); if (nsr != sr) { sr = nsr; K = new Kernel(sr, sink); } }
          if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) chn = Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
          if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
        } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
          if (inEos && ++stall > 400) break; // decoder never flagged EOS: finish with what we have
        }
        if (nF[0] >= MAX_FRAMES) break;
        long now = System.currentTimeMillis();
        if (now - lastProg > 300) {
          lastProg = now;
          long t = ex.getSampleTime();
          int p = durUs > 0 && t >= 0 ? (int) Math.min(99, t * 100 / durUs) : -1;
          if (p != lastP) { lastP = p; prog(p); }
        }
      }
      int n = nF[0];
      if (n < FPS) throw new Exception("The file decoded to almost no audio (under 1 second).");
      // v4.2: raw band/level series, stored as 8-bit dB (-100..0 dBFS, 0 = silence): loud, L, R and the mono / left / right band series (v4.8: 57 series).
      byte[] out = new byte[NF * n];
      boolean flat = true;
      for (int k = 0; k < NF; k++) {
        for (int i = 0; i < n; i++) {
          float v = F[k][i];
          if (k == 0 && v > 1e-4f) flat = false;
          int q = v < 1e-5f ? 0 : (int) Math.round((20 * Math.log10(v) + 100) * 2.55);
          out[k * n + i] = (byte) Math.max(0, Math.min(255, q));
        }
      }
      result = out; resultKind = "feat";
      return "{\"kind\":\"feat\",\"fmt\":2,\"nf\":" + NF + ",\"n\":" + n + ",\"sec\":" + (n / (double) FPS) + ",\"mime\":" + MainActivity.json(mime) + ",\"flat\":" + flat + "}";
    } catch (OutOfMemoryError oom) {
      throw new Exception(human(oom, mime));
    } finally {
      try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) {}
      try { ex.release(); } catch (Throwable ignored) {}
    }
  }
}

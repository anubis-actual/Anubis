package com.anubis.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Foreground service for live audio analysis.
 * Phone audio: type mediaProjection. Mic: type microphone.
 * Order (required on Android 14+): the Activity gets the projection consent result, then starts this
 * service with startForegroundService(); we call startForeground(type) FIRST, and only then
 * getMediaProjection() and create the AudioRecord. Nothing is recorded or stored; only levels are kept.
 */
public class CaptureService extends Service {
  static final String ACT_PHONE = "com.anubis.app.CAP_PHONE";
  static final String ACT_MIC = "com.anubis.app.CAP_MIC";
  static final String ACT_STOP = "com.anubis.app.CAP_STOP";
  static final String CH = "coyote_capture";
  static final int NID = 8;
  static volatile CaptureService I;

  MediaProjection mp;
  AudioRecord rec;
  Thread th;
  String src = "phone";

  @Override public IBinder onBind(Intent i) { return null; }

  @Override public int onStartCommand(Intent i, int flags, int startId) {
    String act = i == null ? ACT_STOP : i.getAction();
    if (act == null || ACT_STOP.equals(act)) { shutdown(); return START_NOT_STICKY; }
    boolean phone = ACT_PHONE.equals(act);
    src = phone ? "phone" : "mic";
    I = this;
    // 1) foreground first, with the right type
    try {
      NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      if (Build.VERSION.SDK_INT >= 26 && nm != null)
        nm.createNotificationChannel(new NotificationChannel(CH, "Audio analysis", NotificationManager.IMPORTANCE_LOW));
      Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
      nb.setContentTitle("Anubis · listening")
        .setContentText(phone ? "Analyzing phone audio levels (nothing is recorded)" : "Analyzing microphone levels (nothing is recorded)")
        .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off).setOngoing(true);
      Notification n = nb.build();
      if (Build.VERSION.SDK_INT >= 29) {
        int type = phone ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        startForeground(NID, n, type);
      } else startForeground(NID, n);
    } catch (Throwable t) { fail(AudioCapture.human(t), t); return START_NOT_STICKY; }

    // 2) only now projection + AudioRecord
    try {
      int sr = 44100;
      if (phone) {
        if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Phone audio capture needs Android 10 or newer. Use Mic instead.");
        int code = i.getIntExtra("code", 0);
        Intent data = (Intent) i.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mp = mpm.getMediaProjection(code, data);
        if (mp == null) throw new IllegalStateException("Android didn't give an audio capture token. Try again.");
        mp.registerCallback(new MediaProjection.Callback() {
          @Override public void onStop() {
            MainActivity.emit("audio", "{\"ok\":false,\"src\":\"phone\",\"stopped\":true,\"msg\":\"Android ended phone-audio capture (you stopped sharing, or the system reclaimed it).\"}");
            shutdown();
          }
        }, new Handler(Looper.getMainLooper()));
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
          .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
          .addMatchingUsage(AudioAttributes.USAGE_GAME)
          .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
        AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
        int buf = Math.max(AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2, 16384);
        rec = new AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(buf).setAudioPlaybackCaptureConfig(cfg).build();
      } else {
        AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        int buf = Math.max(AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2, 8192);
        rec = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(fmt).setBufferSizeInBytes(buf).build();
      }
      if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord not initialised");
      rec.startRecording();
      AudioCapture.reset();
      AudioCapture.running = true;
      final AudioRecord r = rec; final int fsr = sr; final int fch = phone ? 2 : 1; final String fs = src;
      th = new Thread(new Runnable() { public void run() { try { AudioCapture.run(r, fsr, fch, fs); } catch (Throwable t) { CrashLog.write(CaptureService.this, t); } } }, "aud-cap");
      th.start();
      MainActivity.emit("audio", "{\"ok\":true,\"src\":" + MainActivity.json(src) + "}");
    } catch (Throwable t) { fail(AudioCapture.human(t), t); }
    return START_NOT_STICKY;
  }

  void fail(String msg, Throwable t) {
    try { if (t != null) CrashLog.write(this, t); } catch (Throwable ignored) {}
    MainActivity.emit("audio", "{\"ok\":false,\"src\":" + MainActivity.json(src) + ",\"msg\":" + MainActivity.json(msg) + "}");
    shutdown();
  }

  synchronized void shutdown() {
    AudioCapture.running = false;
    try { if (th != null) th.join(400); } catch (Throwable ignored) {}
    th = null;
    if (rec != null) { try { rec.stop(); } catch (Throwable ignored) {} try { rec.release(); } catch (Throwable ignored) {} rec = null; }
    if (mp != null) { try { mp.stop(); } catch (Throwable ignored) {} mp = null; }
    AudioCapture.reset();
    if (I == this) I = null;
    try { stopForeground(true); } catch (Throwable ignored) {}
    try { stopSelf(); } catch (Throwable ignored) {}
  }

  @Override public void onTaskRemoved(Intent rootIntent) { shutdown(); }
  @Override public void onDestroy() { AudioCapture.running = false; if (rec != null) { try { rec.stop(); rec.release(); } catch (Throwable ignored) {} rec = null; } if (mp != null) { try { mp.stop(); } catch (Throwable ignored) {} mp = null; } if (I == this) I = null; super.onDestroy(); }
}

package com.pepper.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.vosk.Model;
import org.vosk.Recognizer;

public class PepperService extends Service {
    static final String BASE = "http://127.0.0.1:8765";
    static final String GRAMMAR = "[\"hey pepper\", \"pepper\", \"hey dummy\", \"dummy\", \"hey buddy\", \"buddy\", \"[unk]\"]";
    static volatile boolean appUp = false;
    volatile String state = "off";
    volatile float level = 0f;
    volatile boolean alive = true;
    boolean started = false;
    volatile boolean active = false;
    long activeAt = 0, pauseUntil = 0;
    volatile int lastErr = 0;
    int tickN = 0;
    Handler h = new Handler(Looper.getMainLooper());
    SpeechRecognizer sr;
    Intent ri;
    WindowManager wm;
    OrbView orb;
    WindowManager.LayoutParams lp;
    AudioManager am;
    PendingIntent pi;
    int[] streams = {AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM};
    volatile Model model;
    volatile boolean voskWant = false;
    volatile long voskHold = 0;
    volatile int lvl = 0;
    volatile long listenSince = 0;
    String lastDbg = "";
    volatile String voskMsg = "-";
    volatile String modelMsg = "model loading";

    @Override public IBinder onBind(Intent i) { return null; }

    Notification note(String txt) {
        if (Build.VERSION.SDK_INT >= 26)
            return new Notification.Builder(this, "pepper").setContentTitle("Pepper").setContentText(txt)
                .setStyle(new Notification.BigTextStyle().bigText(txt))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi).setOnlyAlertOnce(true).build();
        return new Notification.Builder(this).setContentTitle("Pepper").setContentText(txt)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi).build();
    }

    @Override public int onStartCommand(Intent in, int f, int id) {
        if (started) return START_STICKY;
        started = true;
        pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("pepper", "Pepper", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Notification n = note("starting");
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(1, n);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        quiet();
        poll();
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(new Ear());
            ri = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            ri.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            ri.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-ZA");
            ri.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            ri.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            ri.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        } else {
            lastErr = -1;
        }
        prepModel();
        voskThread();
        h.post(tick);
        return START_STICKY;
    }

    void nap(int ms) {
        try { Thread.sleep(ms); } catch (Exception e) { }
    }

    void poll() {
        new Thread(() -> {
            Pattern p = Pattern.compile("\"state\":\\s*\"(\\w+)\"");
            Pattern q = Pattern.compile("\"level\":\\s*([0-9.]+)");
            int bad = 0;
            while (alive) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/state").openConnection();
                    c.setConnectTimeout(800);
                    c.setReadTimeout(800);
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    String s = r.readLine();
                    r.close();
                    if (s == null) s = "";
                    Matcher m = p.matcher(s);
                    if (m.find()) state = m.group(1);
                    bad = 0;
                    Matcher m2 = q.matcher(s);
                    level = m2.find() ? Float.parseFloat(m2.group(1)) : 0f;
                } catch (Exception e) {
                    if (++bad >= 6) state = "off";
                }
                nap(300);
            }
        }).start();
    }

    Runnable tick = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            long now = SystemClock.uptimeMillis();
            String st = state;
            boolean listen = st.equals("listening");
            boolean idle = st.equals("idle");
            if (!listen) pauseUntil = now + 900;
            if (sr != null) {
                if (listen && !active && now > pauseUntil) {
                    active = true;
                    activeAt = now;
                    try { sr.startListening(ri); } catch (Exception e) { active = false; }
                } else if (!listen && active) {
                    try { sr.cancel(); } catch (Exception e) { }
                    active = false;
                } else if (active && now - activeAt > 15000) {
                    try { sr.cancel(); } catch (Exception e) { }
                    active = false;
                    pauseUntil = now + 300;
                }
            }
            if (listen) { if (listenSince == 0) listenSince = now; } else listenSince = 0;
            boolean gMic = listen && listenSince != 0 && now - listenSince > 600;
            voskWant = model != null && !st.equals("off") && !gMic;
            if (orb == null) makeOrb();
            if (orb != null) orb.setVisibility((appUp || idle || st.equals("off")) ? View.GONE : View.VISIBLE);
            String dbg = "state " + st + " | google " + (active ? "on" : "off") + " err " + lastErr
                + " | wake " + (voskWant ? "on" : "off") + " | " + modelMsg + " / " + voskMsg;
            if (!dbg.equals(lastDbg)) {
                lastDbg = dbg;
                try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, note(dbg)); } catch (Exception e) { }
            }
            h.postDelayed(this, 500);
        }
    };

    class Ear implements RecognitionListener {
        public void onReadyForSpeech(Bundle b) { }
        public void onBeginningOfSpeech() { }
        public void onRmsChanged(float v) { }
        public void onBufferReceived(byte[] b) { }
        public void onEndOfSpeech() { }
        public void onPartialResults(Bundle b) { }
        public void onEvent(int t, Bundle b) { }
        public void onError(int e) {
            active = false;
            lastErr = e;
            long now = SystemClock.uptimeMillis();
            if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                try { sr.cancel(); } catch (Exception x) { }
                pauseUntil = now + 800;
            } else pauseUntil = now + 300;
        }
        public void onResults(Bundle b) {
            boolean ok = state.equals("listening");
            active = false;
            lastErr = 0;
            pauseUntil = SystemClock.uptimeMillis() + 300;
            ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (ok && l != null && !l.isEmpty() && l.get(0).trim().length() > 0) send(l.get(0).trim());
        }
    }

    void send(final String t) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/hear").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(1500);
                byte[] b = t.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(b.length);
                OutputStream o = c.getOutputStream();
                o.write(b);
                o.close();
                c.getResponseCode();
                c.disconnect();
            } catch (Exception e) { }
        }).start();
    }

    void quiet() {
        SharedPreferences sp = getSharedPreferences("p", 0);
        for (int i = 0; i < streams.length; i++) {
            try {
                if (!sp.contains("v" + i)) sp.edit().putInt("v" + i, am.getStreamVolume(streams[i])).apply();
                am.setStreamVolume(streams[i], 0, 0);
            } catch (Exception e) { }
        }
    }

    void loud() {
        SharedPreferences sp = getSharedPreferences("p", 0);
        for (int i = 0; i < streams.length; i++) {
            try {
                if (sp.contains("v" + i)) {
                    am.setStreamVolume(streams[i], sp.getInt("v" + i, 3), 0);
                    sp.edit().remove("v" + i).apply();
                }
            } catch (Exception e) { }
        }
    }

    void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File x : fs) { if (x.isDirectory()) deleteDir(x); else x.delete(); }
        d.delete();
    }

    void download(File dir) throws Exception {
        File tmp = new File(getFilesDir(), "m.zip");
        HttpURLConnection c = (HttpURLConnection) new URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(tmp);
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.close();
        in.close();
        deleteDir(dir);
        dir.mkdirs();
        String root = dir.getCanonicalPath() + File.separator;
        ZipInputStream z = new ZipInputStream(new FileInputStream(tmp));
        ZipEntry ze;
        while ((ze = z.getNextEntry()) != null) {
            String name = ze.getName();
            int i = name.indexOf('/');
            if (i < 0) continue;
            name = name.substring(i + 1);
            if (name.isEmpty()) continue;
            File f = new File(dir, name);
            if (!f.getCanonicalPath().startsWith(root)) continue;
            if (ze.isDirectory()) { f.mkdirs(); continue; }
            f.getParentFile().mkdirs();
            FileOutputStream o = new FileOutputStream(f);
            int k;
            while ((k = z.read(b)) > 0) o.write(b, 0, k);
            o.close();
        }
        z.close();
        tmp.delete();
        new FileOutputStream(new File(dir, "ok")).close();
    }

    void prepModel() {
        new Thread(() -> {
            File dir = new File(getFilesDir(), "model");
            int fails = 0;
            while (alive && model == null && fails < 2) {
                try {
                    if (!new File(dir, "ok").exists()) {
                        modelMsg = "downloading model";
                        download(dir);
                    }
                } catch (Exception e) {
                    modelMsg = "download failed, retrying";
                    nap(30000);
                    continue;
                }
                try {
                    model = new Model(dir.getAbsolutePath());
                    modelMsg = "model ready";
                } catch (Throwable t) {
                    fails++;
                    modelMsg = "model error " + t.getClass().getSimpleName();
                    deleteDir(dir);
                    nap(20000);
                }
            }
        }).start();
    }

    void voskThread() {
        new Thread(() -> {
            short[] buf = new short[1600];
            AudioRecord a = null;
            Recognizer r = null;
            double gain = 4.0;
            java.util.ArrayDeque<short[]> ring = new java.util.ArrayDeque<>();
            boolean wasIdle = false;
            Pattern tp = Pattern.compile("\"(?:text|partial)\"\\s*:\\s*\"([^\"]*)\"");
            while (alive) {
                if (!voskWant || model == null) {
                    if (a != null) {
                        try { a.stop(); } catch (Exception e) { }
                        try { a.release(); } catch (Exception e) { }
                        a = null;
                    }
                    lvl = 0;
                    nap(100);
                    continue;
                }
                try {
                    if (r == null) r = new Recognizer(model, 16000.0f, GRAMMAR);
                    if (a == null) {
                        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        AudioRecord x = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
                        if (x.getState() != AudioRecord.STATE_INITIALIZED) {
                            x.release();
                            voskMsg = "mic busy";
                            nap(300);
                            continue;
                        }
                        x.startRecording();
                        a = x;
                        r.reset();
                        voskMsg = "listening";
                    }
                    int n = a.read(buf, 0, buf.length);
                    if (n <= 0) { nap(20); continue; }
                    double sum = 0;
                    for (int i = 0; i < n; i++) sum += buf[i] * (double) buf[i];
                    double rms = Math.sqrt(sum / n);
                    lvl = (int) rms;
                    double want = rms < 1 ? gain : Math.max(1.0, Math.min(14.0, 2500.0 / rms));
                    gain = gain * 0.9 + want * 0.1;
                    for (int i = 0; i < n; i++) {
                        int v = (int) (buf[i] * gain);
                        buf[i] = (short) Math.max(-32768, Math.min(32767, v));
                    }
                    if (!state.equals("idle") || SystemClock.uptimeMillis() < voskHold) { wasIdle = false; continue; }
                    if (!wasIdle) { r.reset(); wasIdle = true; }
                    short[] cp = new short[n];
                    System.arraycopy(buf, 0, cp, 0, n);
                    ring.addLast(cp);
                    if (ring.size() > 12) ring.removeFirst();
                    String js = r.acceptWaveForm(buf, n) ? r.getResult() : r.getPartialResult();
                    Matcher m = tp.matcher(js == null ? "" : js);
                    String t = m.find() ? m.group(1).trim().toLowerCase() : "";
                    if (t.contains("pepper") || t.contains("dummy") || t.contains("buddy")) {
                        voskHold = SystemClock.uptimeMillis() + 3000;
                        voskMsg = "heard: " + t;
                        String full = capture(a, gain, ring, t);
                        send(full);
                        r.reset();
                    }
                } catch (Throwable e) {
                    voskMsg = "error " + e.getClass().getSimpleName();
                    nap(500);
                }
            }
            if (a != null) {
                try { a.stop(); } catch (Exception e) { }
                try { a.release(); } catch (Exception e) { }
            }
            try { if (r != null) r.close(); } catch (Throwable e) { }
        }).start();
    }

    String capture(AudioRecord a, double gain, java.util.ArrayDeque<short[]> ring, String first) {
        Recognizer rf = null;
        try {
            rf = new Recognizer(model, 16000.0f);
            for (short[] blk : ring) rf.acceptWaveForm(blk, blk.length);
            short[] b = new short[1600];
            long end = SystemClock.uptimeMillis() + 5000;
            Pattern tp2 = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"");
            String text = "";
            while (SystemClock.uptimeMillis() < end && alive) {
                int n = a.read(b, 0, b.length);
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) {
                    int v = (int) (b[i] * gain);
                    b[i] = (short) Math.max(-32768, Math.min(32767, v));
                }
                if (rf.acceptWaveForm(b, n)) {
                    Matcher m = tp2.matcher(rf.getResult());
                    text = m.find() ? m.group(1).trim() : "";
                    break;
                }
            }
            if (text.isEmpty()) {
                Matcher m = tp2.matcher(rf.getFinalResult());
                text = m.find() ? m.group(1).trim() : "";
            }
            return text.length() > first.length() ? text : first;
        } catch (Throwable e) {
            return first;
        } finally {
            try { if (rf != null) rf.close(); } catch (Throwable x) { }
        }
    }

    void makeOrb() {
        try {
            if (!Settings.canDrawOverlays(this)) return;
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            orb = new OrbView(this);
            int sz = (int) (110 * getResources().getDisplayMetrics().density);
            lp = new WindowManager.LayoutParams(sz, sz,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 20;
            lp.y = 300;
            wm.addView(orb, lp);
        } catch (Exception e) {
            orb = null;
        }
    }

    class OrbView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int sx, sy;
        float dx, dy;
        boolean moved;
        final int N = 150;
        float[] px = new float[N], py = new float[N], pz = new float[N];
        int[] pc = new int[N];
        float lv = 0f;
        float ang = 0f;
        long last = 0;

        OrbView(Context c) {
            super(c);
            java.util.Random rd = new java.util.Random(7);
            int[] pal = {0xFF8FB4FF, 0xFFB69CFF, 0xFFE79BC8, 0xFF9FE3B8, 0xFF7FD3F0};
            for (int i = 0; i < N; i++) {
                double u = rd.nextDouble() * 2 - 1, a = rd.nextDouble() * Math.PI * 2;
                double rr = Math.cbrt(rd.nextDouble());
                double sq = Math.sqrt(1 - u * u);
                px[i] = (float) (rr * sq * Math.cos(a));
                py[i] = (float) (rr * u);
                pz[i] = (float) (rr * sq * Math.sin(a));
                int idx = (px[i] < -0.2f) ? (py[i] < 0 ? 0 : 1) : ((px[i] > 0.2f) ? (py[i] < 0 ? 2 : 3) : 4);
                pc[i] = pal[idx];
            }
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.04f : (now - last) / 1000f;
            last = now;
            float t = now / 1000f;
            String s = state;
            float spin = 0.12f, pulse = 0f, alpha = 110f;
            if (s.equals("listening")) { spin = 0.35f; pulse = 0.04f * (float) Math.sin(t * 2.2f); alpha = 215f; }
            else if (s.equals("thinking")) { spin = 1.6f; pulse = 0.06f * (float) Math.sin(t * 5f); alpha = 230f; }
            else if (s.equals("talking")) { spin = 0.7f; lv += (level - lv) * 0.35f; pulse = 0.05f * (float) Math.sin(t * 9f) + 0.30f * lv; alpha = 245f; }
            ang += spin * dt;
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            float d = getResources().getDisplayMetrics().density;
            float R = getWidth() / 2f;
            float sc = 0.82f * (1f + pulse);
            for (int i = 0; i < N; i++) {
                float x = px[i] * ca + pz[i] * sa;
                float z = -px[i] * sa + pz[i] * ca;
                float depth = (z + 1f) / 2f;
                p.setColor(pc[i]);
                p.setAlpha((int) (alpha * (0.35f + 0.65f * depth)));
                c.drawCircle(R + x * R * sc, R + py[i] * R * sc, (1.1f + 1.7f * depth) * d, p);
            }
            postInvalidateDelayed(33);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = lp.x; sy = lp.y; dx = e.getRawX(); dy = e.getRawY(); moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                    if (Math.abs(mx) > 12 || Math.abs(my) > 12) moved = true;
                    if (moved) {
                        lp.x = sx + (int) mx;
                        lp.y = sy + (int) my;
                        try { wm.updateViewLayout(this, lp); } catch (Exception x) { }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        Intent i = new Intent(PepperService.this, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    }
                    return true;
            }
            return false;
        }
    }

    @Override public void onDestroy() {
        alive = false;
        try { if (sr != null) sr.destroy(); } catch (Exception e) { }
        try { if (orb != null) wm.removeView(orb); } catch (Exception e) { }
        loud();
        super.onDestroy();
    }
}

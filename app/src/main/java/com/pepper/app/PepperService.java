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
import android.media.AudioManager;
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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PepperService extends Service {
    static final String BASE = "http://127.0.0.1:8765";
    static volatile boolean appUp = false;
    volatile String state = "off";
    volatile float level = 0f;
    volatile boolean alive = true;
    boolean started = false, active = false;
    long activeAt = 0, pauseUntil = 0;
    Handler h = new Handler(Looper.getMainLooper());
    SpeechRecognizer sr;
    Intent ri;
    WindowManager wm;
    OrbView orb;
    WindowManager.LayoutParams lp;
    AudioManager am;
    int[] streams = {AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM};

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int f, int id) {
        if (started) return START_STICKY;
        started = true;
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("pepper", "Pepper", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
            n = new Notification.Builder(this, "pepper").setContentTitle("Pepper").setContentText("Here with you")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi).build();
        } else {
            n = new Notification.Builder(this).setContentTitle("Pepper").setContentText("Here with you")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi).build();
        }
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
        }
        h.post(tick);
        return START_STICKY;
    }

    void poll() {
        new Thread(() -> {
            Pattern p = Pattern.compile("\"state\":\\s*\"(\\w+)\"");
            while (alive) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/state").openConnection();
                    c.setConnectTimeout(800);
                    c.setReadTimeout(800);
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    String s = r.readLine();
                    r.close();
                    Matcher m = p.matcher(s == null ? "" : s);
                    if (m.find()) state = m.group(1);
                    Matcher m2 = Pattern.compile("\"level\":\\s*([0-9.]+)").matcher(s == null ? "" : s);
                    level = m2.find() ? Float.parseFloat(m2.group(1)) : 0f;
                } catch (Exception e) {
                    state = "off";
                }
                try { Thread.sleep(300); } catch (Exception e) { }
            }
        }).start();
    }

    Runnable tick = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            long now = SystemClock.uptimeMillis();
            boolean ears = state.equals("listening") || state.equals("idle");
            if (!ears) pauseUntil = now + 900;
            boolean want = ears && now > pauseUntil;
            if (sr != null) {
                if (want && !active) {
                    try { active = true; activeAt = now; sr.startListening(ri); } catch (Exception e) { active = false; }
                } else if (!want && active) {
                    try { sr.cancel(); } catch (Exception e) { }
                    active = false;
                } else if (active && now - activeAt > 15000) {
                    try { sr.cancel(); } catch (Exception e) { }
                    active = false;
                    pauseUntil = now + 300;
                }
            }
            if (orb == null) makeOrb();
            if (orb != null) orb.setVisibility((appUp || state.equals("idle") || state.equals("off")) ? View.GONE : View.VISIBLE);
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
            long now = SystemClock.uptimeMillis();
            if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                try { sr.cancel(); } catch (Exception x) { }
                pauseUntil = now + 800;
            } else pauseUntil = now + 300;
        }
        public void onResults(Bundle b) {
            active = false;
            pauseUntil = SystemClock.uptimeMillis() + 300;
            ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l != null && !l.isEmpty() && l.get(0).trim().length() > 0) send(l.get(0).trim());
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

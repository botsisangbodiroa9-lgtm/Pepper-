package com.pepper.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;

public class MainActivity extends Activity {
    static final String URL = "http://127.0.0.1:8765";
    WebView w;
    boolean asked = false, askedOverlay = false, askedTermux = false;
    long lastStart = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w = new WebView(this);
        w.setBackgroundColor(Color.BLACK);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " Pepper-App");
        w.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) v.postDelayed(() -> v.loadUrl(URL), 1500);
            }
        });
        setContentView(w);
        w.loadUrl(URL);
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() {
        super.onResume();
        PepperService.appUp = true;
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED && !askedTermux) {
            askedTermux = true;
            requestPermissions(new String[]{"com.termux.permission.RUN_COMMAND"}, 2);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!asked) {
                asked = true;
                ArrayList<String> need = new ArrayList<>();
                need.add(Manifest.permission.RECORD_AUDIO);
                if (Build.VERSION.SDK_INT >= 33) need.add("android.permission.POST_NOTIFICATIONS");
                requestPermissions(need.toArray(new String[0]), 1);
            }
            return;
        }
        if (!Settings.canDrawOverlays(this) && !askedOverlay) {
            askedOverlay = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }
        Intent i = new Intent(this, PepperService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        startPepper();
    }

    void startPepper() {
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) return;
        if (System.currentTimeMillis() - lastStart < 15000) return;
        lastStart = System.currentTimeMillis();
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(URL + "/state").openConnection();
                c.setConnectTimeout(700);
                c.setReadTimeout(700);
                if (c.getResponseCode() == 200) return;
            } catch (Exception e) { }
            try {
                Intent i = new Intent();
                i.setClassName("com.termux", "com.termux.app.RunCommandService");
                i.setAction("com.termux.RUN_COMMAND");
                i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
                i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"/data/data/com.termux/files/home/pepper_bg.sh"});
                i.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
                i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
                startService(i);
            } catch (Exception e) { }
        }).start();
    }

    @Override protected void onPause() {
        super.onPause();
        PepperService.appUp = false;
    }
}

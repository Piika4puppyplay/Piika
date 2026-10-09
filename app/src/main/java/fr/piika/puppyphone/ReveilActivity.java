package fr.piika.puppyphone;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;

/** PupRéveil — la liste des réveils et leur réglage. */
public class ReveilActivity extends Activity {
    static final String HOST = "pupreveil.local";
    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    float dp = 1f;
    int insT, insB;
    MediaPlayer prev;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        dp = getResources().getDisplayMetrics().density;
        web = new WebView(this);
        web.setBackgroundColor(Pelage.bg(this));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !HOST.equals(r.getUrl().getHost()); }
            @Override public void onPageFinished(WebView v, String u) { pushInsets(); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Rev");
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v, ins) -> { insT = ins.getSystemWindowInsetTop(); insB = ins.getSystemWindowInsetBottom(); pushInsets(); return ins.consumeSystemWindowInsets(); });
        web.loadUrl("https://" + HOST + "/reveil.html");
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        Reveil.schedule(this);
    }
    @Override protected void onResume() { super.onResume(); Pelage.watch(this, web); emit("resume", ""); }
    @Override protected void onPause() { stopPrev(); super.onPause(); }
    @Override protected void onDestroy() { stopPrev(); if (web != null) web.destroy(); super.onDestroy(); }

    void emit(String ev, String data) { ui.post(() -> { if (web != null) web.evaluateJavascript("window.RevUI&&RevUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); }); }
    void pushInsets() { final float t = insT / dp, bt = insB / dp; ui.post(() -> { if (web != null) web.evaluateJavascript("window.RevUI&&RevUI.insets(" + t + "," + bt + ")", null); }); }

    void stopPrev() { ui.removeCallbacksAndMessages("prev"); try { if (prev != null) { prev.stop(); prev.release(); } } catch (Exception ignored) { } prev = null; }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null || u.getPath().equals("/") ? "/reveil.html" : u.getPath();
        try {
            InputStream in = Pelage.open(this, p);
            String mime = MainActivity.mime(p);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", new HashMap<>(), in);
        } catch (Exception e) { return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", new HashMap<>(), new ByteArrayInputStream(new byte[0])); }
    }

    class Bridge {
        @JavascriptInterface public String info() {
            try {
                JSONArray sons = new JSONArray();
                for (String[] x : Reveil.SONS) sons.put(new JSONArray().put(x[0]).put(x[1]));
                boolean exact = true;
                if (Build.VERSION.SDK_INT >= 31) exact = getSystemService(AlarmManager.class).canScheduleExactAlarms();
                return new JSONObject().put("alarms", Reveil.list(ReveilActivity.this)).put("nextAt", Reveil.sp(ReveilActivity.this).getLong("nextAt", 0))
                        .put("sons", sons).put("exact", exact).toString();
            } catch (Exception e) { return "{}"; }
        }
        /** Ajoute ou remplace un réveil (JSON), renvoie la liste à jour. */
        @JavascriptInterface public String save(String json) {
            try {
                JSONObject o = new JSONObject(json);
                JSONArray a = Reveil.list(ReveilActivity.this), out = new JSONArray();
                if (o.optInt("id", 0) == 0) o.put("id", (int) (System.currentTimeMillis() / 1000 % 2000000000));
                boolean done = false;
                for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x.optInt("id") == o.optInt("id")) { out.put(o); done = true; } else out.put(x); }
                if (!done) out.put(o);
                Reveil.save(ReveilActivity.this, out);
                Reveil.schedule(ReveilActivity.this);
            } catch (Exception ignored) { }
            return info();
        }
        @JavascriptInterface public String del(int id) {
            JSONArray a = Reveil.list(ReveilActivity.this), out = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optInt("id") != id) out.put(a.optJSONObject(i));
            Reveil.save(ReveilActivity.this, out); Reveil.schedule(ReveilActivity.this);
            return info();
        }
        /** Fait sonner ce réveil dans 10 secondes (pour tester, écran éteint compris). */
        @JavascriptInterface public void test(int id) {
            Reveil.sp(ReveilActivity.this).edit().putLong("snoozeAt", System.currentTimeMillis() + 10_000).putInt("snoozeId", id).commit();
            Reveil.schedule(ReveilActivity.this);
        }
        @JavascriptInterface public void preview(String son) {
            ui.post(() -> {
                stopPrev();
                try {
                    prev = new MediaPlayer();
                    prev.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
                    if ("systeme".equals(son)) {
                        Uri u = RingtoneManager.getActualDefaultRingtoneUri(ReveilActivity.this, RingtoneManager.TYPE_ALARM);
                        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                        prev.setDataSource(ReveilActivity.this, u);
                    } else try (AssetFileDescriptor fd = getAssets().openFd("www/sons/reveil_" + son + ".wav")) { prev.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength()); }
                    prev.setVolume(.7f, .7f); prev.prepare(); prev.start();
                    ui.postAtTime(ReveilActivity.this::stopPrev, "prev", android.os.SystemClock.uptimeMillis() + 4500);
                } catch (Exception ignored) { }
            });
        }
        @JavascriptInterface public void stopPreview() { ui.post(ReveilActivity.this::stopPrev); }
        @JavascriptInterface public void exactSettings() {
            ui.post(() -> { try { if (Build.VERSION.SDK_INT >= 31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { } });
        }
        @JavascriptInterface public void close() { ui.post(ReveilActivity.this::finish); }
    }
}

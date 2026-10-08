package fr.piika.puppyphone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
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

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** PupUpdate — écran de mise à jour de PuppyPhone. */
public class UpdateActivity extends Activity {
    static final String HOST = "pupupdate.local";
    static UpdateActivity I;
    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    float dp = 1f;
    int insT, insB;
    final AtomicBoolean cancel = new AtomicBoolean(false);
    volatile boolean busy;
    File pendingApk;

    static void status(String st, String msg) {
        UpdateActivity a = I;
        if (a != null) { a.busy = false; a.emit("status", "{\"st\":" + JSONObject.quote(st) + ",\"msg\":" + JSONObject.quote(msg == null ? "" : msg) + "}"); }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        I = this;
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        dp = getResources().getDisplayMetrics().density;
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0614);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !HOST.equals(r.getUrl().getHost()); }
            @Override public void onPageFinished(WebView v, String u) { pushInsets(); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Upd");
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v, ins) -> { insT = ins.getSystemWindowInsetTop(); insB = ins.getSystemWindowInsetBottom(); pushInsets(); return ins.consumeSystemWindowInsets(); });
        web.loadUrl("https://" + HOST + "/update.html");
    }
    @Override protected void onResume() {
        super.onResume(); Pelage.watch(this, web);
        // retour des réglages « sources inconnues » : on reprend l'installation
        if (pendingApk != null && getPackageManager().canRequestPackageInstalls()) { File f = pendingApk; pendingApk = null; doInstall(f); }
        emit("resume", "");
    }
    @Override protected void onDestroy() { if (I == this) I = null; cancel.set(true); if (web != null) web.destroy(); super.onDestroy(); }

    void emit(String ev, String data) { ui.post(() -> { if (web != null) web.evaluateJavascript("window.UpdUI&&UpdUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); }); }
    void pushInsets() { final float t = insT / dp, bt = insB / dp; ui.post(() -> web.evaluateJavascript("window.UpdUI&&UpdUI.insets(" + t + "," + bt + ")", null)); }

    void doInstall(File f) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingApk = f;
            emit("status", "{\"st\":\"perm\",\"msg\":\"\"}");
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
            return;
        }
        new Thread(() -> {
            try { emit("status", "{\"st\":\"installing\",\"msg\":\"\"}"); PupUpdate.install(this, f); }
            catch (Exception e) { busy = false; emit("status", "{\"st\":\"error\",\"msg\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"); }
        }).start();
    }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        try {
            String path = p.equals("/") ? "/update.html" : p;
            InputStream in = Pelage.open(this, path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    class Bridge {
        @JavascriptInterface public String info() {
            try {
                android.content.SharedPreferences p = PupUpdate.sp(UpdateActivity.this);
                return new JSONObject().put("current", PupUpdate.current(UpdateActivity.this)).put("auto", p.getBoolean("auto", true)).put("notify", p.getBoolean("notify", true))
                        .put("lastCheck", p.getLong("lastCheck", 0)).put("latest", p.getLong("latest", 0)).put("notes", new org.json.JSONArray(p.getString("notes", "[]")))
                        .put("url", p.getString("url", "")).put("size", p.getLong("size", 0)).put("busy", busy).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void check() { new Thread(() -> emit("checked", PupUpdate.check(UpdateActivity.this).toString())).start(); }
        @JavascriptInterface public void set(String k, boolean v) { PupUpdate.sp(UpdateActivity.this).edit().putBoolean(k, v).apply(); }
        @JavascriptInterface public void update(String url, long size) {
            if (busy) return;
            busy = true; cancel.set(false);
            new Thread(() -> {
                try {
                    final long[] last = {0};
                    File f = PupUpdate.download(UpdateActivity.this, url, size, (d, t) -> {
                        long now = System.currentTimeMillis();
                        if (now - last[0] > 120 || d == t) { last[0] = now; emit("progress", "{\"d\":" + d + ",\"t\":" + t + "}"); }
                    }, cancel);
                    ui.post(() -> doInstall(f));
                } catch (Exception e) { busy = false; emit("status", "{\"st\":\"error\",\"msg\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"); }
            }).start();
        }
        @JavascriptInterface public void cancel() { cancel.set(true); }
        @JavascriptInterface public void openPage() { ui.post(() -> { try { startActivity(new Intent(UpdateActivity.this, BrowserActivity.class).setData(Uri.parse(PupUpdate.PAGE))); } catch (Exception e) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PupUpdate.PAGE))); } catch (Exception ignored) { } } }); }
        @JavascriptInterface public void close() { ui.post(UpdateActivity.this::finish); }
    }
}

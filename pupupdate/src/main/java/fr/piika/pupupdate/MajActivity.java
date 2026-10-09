package fr.piika.pupupdate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** PupUpdate (appli séparée) : écran de mise à jour de PuppyPhone et de lui-même. */
public class MajActivity extends Activity {
    static final String HOST = "pupupdate.local";
    static MajActivity I;
    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    float dp = 1f;
    int insT, insB;
    final AtomicBoolean cancel = new AtomicBoolean(false);
    volatile boolean busy;
    File pendingApk; String pendingPkg;
    File rollbackApk; boolean awaitingUninstall; long rollbackV;

    static void status(String st, String msg) {
        MajActivity a = I;
        if (a != null) { a.busy = false; a.emit("status", "{\"st\":" + JSONObject.quote(st) + ",\"msg\":" + JSONObject.quote(msg == null ? "" : msg) + "}"); }
    }
    static void finishAll() { MajActivity a = I; if (a != null) a.ui.post(a::finish); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        I = this;
        takeColors(getIntent());
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
        web.loadUrl("https://" + HOST + "/maj.html");
        Maj.schedule(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); if (takeColors(i) && web != null) web.reload(); }
    /** PuppyPhone passe les couleurs du pelage : PupUpdate s'habille pareil. */
    boolean takeColors(Intent i) {
        if (i == null || i.getStringExtra("acc") == null) return false;
        String a = i.getStringExtra("acc"), b = i.getStringExtra("acc2");
        if (!a.matches("#[0-9a-fA-F]{6}") || b == null || !b.matches("#[0-9a-fA-F]{6}")) return false;
        boolean changed = !a.equals(Maj.sp(this).getString("acc", ""));
        Maj.sp(this).edit().putString("acc", a).putString("acc2", b).apply();
        return changed;
    }
    @Override protected void onResume() {
        super.onResume();
        // Rétrograde : on attendait la fin de la désinstallation de PuppyPhone pour poser l'ancienne version.
        if (awaitingUninstall && rollbackApk != null) {
            if (Maj.version(this, Maj.PUPPY) == 0) {
                awaitingUninstall = false; File f = rollbackApk; rollbackApk = null; long v = rollbackV;
                emit("status", "{\"st\":\"rollinstall\",\"msg\":" + JSONObject.quote("v1.0." + v) + "}");
                doInstall(f, Maj.PUPPY);
            } else {
                // PuppyPhone est toujours là : l'utilisateur a annulé la désinstallation.
                awaitingUninstall = false; busy = false;
                emit("status", "{\"st\":\"rollcancel\",\"msg\":\"\"}");
            }
        }
        if (pendingApk != null && getPackageManager().canRequestPackageInstalls()) { File f = pendingApk; pendingApk = null; doInstall(f, pendingPkg); }
        emit("resume", "");
    }
    @Override protected void onDestroy() { if (I == this) I = null; cancel.set(true); if (web != null) web.destroy(); super.onDestroy(); }

    void emit(String ev, String data) { ui.post(() -> { if (web != null) web.evaluateJavascript("window.UpdUI&&UpdUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); }); }
    void pushInsets() { final float t = insT / dp, bt = insB / dp; ui.post(() -> { if (web != null) web.evaluateJavascript("window.UpdUI&&UpdUI.insets(" + t + "," + bt + ")", null); }); }

    void doInstall(File f, String pkg) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingApk = f; pendingPkg = pkg;
            emit("status", "{\"st\":\"perm\",\"msg\":\"\"}");
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
            return;
        }
        new Thread(() -> {
            try { emit("status", "{\"st\":\"installing\",\"msg\":\"\"}"); Maj.install(this, f, pkg); }
            catch (Exception e) { busy = false; emit("status", "{\"st\":\"error\",\"msg\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"); }
        }).start();
    }

    static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".wav")) return "audio/wav";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        try {
            String path = p.equals("/") ? "/maj.html" : p;
            InputStream in = getAssets().open("www" + path);
            if ("/style.css".equals(path)) {
                String a = Maj.sp(this).getString("acc", ""), b = Maj.sp(this).getString("acc2", "");
                if (!a.isEmpty()) in = new SequenceInputStream(in, new ByteArrayInputStream(("\nhtml body{--acc:" + a + "!important;--acc2:" + b + "!important;--pink:" + a + "!important}\n").getBytes(StandardCharsets.UTF_8)));
            }
            if ("/icons.js".equals(path)) {
                in = new SequenceInputStream(in, new SequenceInputStream(new ByteArrayInputStream("\n;window.PUPSONS={on:true,vol:.5};\n".getBytes(StandardCharsets.UTF_8)), getAssets().open("www/pupsons.js")));
            }
            String mime = mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    class Bridge {
        @JavascriptInterface public String info() {
            try {
                android.content.SharedPreferences p = Maj.sp(MajActivity.this);
                return new JSONObject().put("last", new JSONObject(p.getString("last", "{}"))).put("lastCheck", p.getLong("lastCheck", 0))
                        .put("auto", p.getBoolean("auto", true)).put("notify", p.getBoolean("notify", true)).put("retour", p.getBoolean("retour", true))
                        .put("ppCur", Maj.version(MajActivity.this, Maj.PUPPY)).put("selfCur", Maj.version(MajActivity.this, getPackageName()))
                        .put("android12", Build.VERSION.SDK_INT >= 31).put("busy", busy).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void check() { new Thread(() -> emit("checked", Maj.check(MajActivity.this).toString())).start(); }
        @JavascriptInterface public void set(String k, boolean v) {
            if (!k.matches("auto|notify|retour")) return;
            Maj.sp(MajActivity.this).edit().putBoolean(k, v).apply();
            if ("auto".equals(k)) {
                if (!v) { android.app.job.JobScheduler js = getSystemService(android.app.job.JobScheduler.class); if (js != null) js.cancel(Maj.JOB); }
                else Maj.schedule(MajActivity.this);
            }
        }
        /** which = "pp" (PuppyPhone) ou "self" (PupUpdate). */
        @JavascriptInterface public void update(String which, String url, long size) {
            if (busy) return;
            busy = true; cancel.set(false);
            final String pkg = "self".equals(which) ? getPackageName() : Maj.PUPPY;
            new Thread(() -> {
                try {
                    final long[] last = {0};
                    File f = Maj.download(MajActivity.this, url, size, "self".equals(which) ? "PupUpdate.apk" : "PuppyPhone.apk", (d, t) -> {
                        long now = System.currentTimeMillis();
                        if (now - last[0] > 120 || d == t) { last[0] = now; emit("progress", "{\"d\":" + d + ",\"t\":" + t + "}"); }
                    }, cancel);
                    ui.post(() -> doInstall(f, pkg));
                } catch (Exception e) { busy = false; emit("status", "{\"st\":\"error\",\"msg\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"); }
            }).start();
        }
        @JavascriptInterface public void cancel() { cancel.set(true); awaitingUninstall = false; }
        /**
         * Dépannage : réinstalle / rétrograde PuppyPhone vers la version visée.
         * needUninstall = la version visée est PLUS BASSE que l'installée → Android exige une désinstallation d'abord.
         * PupUpdate étant une appli à part, il survit à la désinstallation et pose ensuite l'ancien APK depuis son cache.
         */
        @JavascriptInterface public void recover(String url, long size, long targetV, boolean needUninstall) {
            if (busy) return;
            busy = true; cancel.set(false);
            new Thread(() -> {
                try {
                    final long[] last = {0};
                    File f = Maj.download(MajActivity.this, url, size, "PuppyPhone-v" + targetV + ".apk", (d, t) -> {
                        long now = System.currentTimeMillis();
                        if (now - last[0] > 120 || d == t) { last[0] = now; emit("progress", "{\"d\":" + d + ",\"t\":" + t + "}"); }
                    }, cancel);
                    if (needUninstall && Maj.version(MajActivity.this, Maj.PUPPY) > 0) {
                        rollbackApk = f; rollbackV = targetV; awaitingUninstall = true;
                        emit("status", "{\"st\":\"rolluninstall\",\"msg\":" + JSONObject.quote("v1.0." + targetV) + "}");
                        ui.post(() -> Maj.uninstall(MajActivity.this, Maj.PUPPY));
                    } else {
                        ui.post(() -> doInstall(f, Maj.PUPPY));
                    }
                } catch (Exception e) { busy = false; emit("status", "{\"st\":\"error\",\"msg\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"); }
            }).start();
        }
        @JavascriptInterface public void openPuppy() { ui.post(() -> { if (Maj.openPuppy(MajActivity.this)) finish(); }); }
        @JavascriptInterface public void openPage() { ui.post(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Maj.PAGE))); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void close() { ui.post(MajActivity.this::finish); }
    }
}

package fr.piika.puppyphone;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
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
import java.util.concurrent.atomic.AtomicBoolean;

/** PupRescousse 🤒 : le chiot malade. Ouvert tout seul après plusieurs plantages, ou à la main. Tourne dans son propre processus. */
public class RecoveryActivity extends Activity {
    static final String HOST = "puprescue.local";
    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    final AtomicBoolean cancel = new AtomicBoolean(false);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashGuard.reset(this); // on est en rescousse : on arrête de compter les plantages
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT); w.setNavigationBarColor(Color.TRANSPARENT);
        web = new WebView(this);
        web.setBackgroundColor(0xFF1a0a12);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
        });
        web.addJavascriptInterface(new Bridge(), "Rescue");
        setContentView(web);
        web.loadUrl("https://" + HOST + "/recovery.html");
    }

    void emit(String ev, String data) { ui.post(() -> { if (web != null) web.evaluateJavascript("window.RescueUI&&RescueUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); }); }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null || u.getPath().equals("/") ? "/recovery.html" : u.getPath();
        try {
            InputStream in = getAssets().open("www" + p);
            String mime = MainActivity.mime(p);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", new HashMap<>(), in);
        } catch (Exception e) { return new WebResourceResponse("text/plain", "utf-8", 404, "x", new HashMap<>(), new ByteArrayInputStream(new byte[0])); }
    }

    void installFrom(String url, long size, String label) {
        cancel.set(false);
        new Thread(() -> {
            try {
                emit("work", label + "…");
                File apk = PupUpdate.download(this, url, size, (done, total) -> emit("prog", total > 0 ? String.valueOf(done * 100 / total) : "-1"), cancel);
                emit("work", "Installation… valide quand Android te le demande 🐾");
                PupUpdate.install(this, apk);
            } catch (Exception e) { emit("err", String.valueOf(e.getMessage())); }
        }).start();
    }

    class Bridge {
        @JavascriptInterface public boolean auto() { return getIntent() != null && getIntent().getBooleanExtra("auto", false); }
        @JavascriptInterface public String pelage() { String[] p = Pelage.cur(RecoveryActivity.this); return "{\"acc\":\"" + p[2] + "\",\"acc2\":\"" + p[3] + "\"}"; }
        @JavascriptInterface public long current() { return PupUpdate.current(RecoveryActivity.this); }
        @JavascriptInterface public String lastError() { return CrashGuard.sp(RecoveryActivity.this).getString("lastErr", ""); }
        @JavascriptInterface public void check() { new Thread(() -> emit("checked", PupUpdate.check(RecoveryActivity.this).toString())).start(); }
        @JavascriptInterface public void update(String url, long size) { installFrom(url, size, "Téléchargement de la dernière version"); }
        @JavascriptInterface public void rollback(String url, long size) {
            cancel.set(false);
            new Thread(() -> {
                try {
                    emit("work", "Téléchargement de la version précédente");
                    File apk = PupUpdate.download(RecoveryActivity.this, url, size, (done, total) -> emit("prog", total > 0 ? String.valueOf(done * 100 / total) : "-1"), cancel);
                    String digits = url.replaceAll(".*puppyphone-v", "").replaceAll("[^0-9].*", "");
                    final String name = "PuppyPhone-v" + (digits.isEmpty() ? "rollback" : digits) + ".apk";
                    PupSave.save(RecoveryActivity.this, "download", "PuppyPhone", name, "application/vnd.android.package-archive", o -> {
                        try (java.io.FileInputStream in = new java.io.FileInputStream(apk)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); }
                    });
                    apk.delete();
                    emit("rollready", name);
                } catch (Exception e) { emit("err", String.valueOf(e.getMessage())); }
            }).start();
        }
        /** Désinstalle PuppyPhone (l'utilisateur rouvre ensuite l'APK téléchargé pour poser l'ancienne version). */
        @JavascriptInterface public void uninstallSelf() {
            ui.post(() -> {
                try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            });
        }
        /** Ouvre le dossier Téléchargements (pour retrouver l'APK et le réinstaller). */
        @JavascriptInterface public void openDownloads() {
            ui.post(() -> {
                try { startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { try { startActivity(new Intent(Intent.ACTION_VIEW).setType("application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }
            });
        }
        @JavascriptInterface public void cancel() { cancel.set(true); }
        /** Repartir sur une base propre : efface données + cache, puis Android relance l'appli à zéro. */
        @JavascriptInterface public void wipe() {
            ui.post(() -> {
                try { ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).clearApplicationUserData(); }
                catch (Exception e) { emit("err", "Effacement impossible : " + e.getMessage()); }
            });
        }
        /** Juste réessayer de lancer l'accueil. */
        @JavascriptInterface public void retry() {
            CrashGuard.reset(RecoveryActivity.this);
            ui.post(() -> {
                try { startActivity(new Intent(RecoveryActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); } catch (Exception ignored) { }
                finish();
            });
        }
        @JavascriptInterface public void close() { ui.post(RecoveryActivity.this::finish); }
    }
}

package fr.piika.puppyphone;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** « Ta niche » : pelage global, sauvegarde (enterrer / déterrer un os), fond animé Nuit néon, sieste du chiot. */
public class NicheActivity extends Activity {
    static final String HOST = "pupniche.local";
    static final int REQ_RESTORE = 71;
    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    float dp = 1f;
    int insT, insB;
    volatile boolean busy;

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
        web.addJavascriptInterface(new Bridge(), "Niche");
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v, ins) -> { insT = ins.getSystemWindowInsetTop(); insB = ins.getSystemWindowInsetBottom(); pushInsets(); return ins.consumeSystemWindowInsets(); });
        web.loadUrl("https://" + HOST + "/niche.html");
    }
    @Override protected void onResume() { super.onResume(); Pelage.watch(this, web); emit("resume", ""); }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }

    void emit(String ev, String data) { ui.post(() -> { if (web != null) web.evaluateJavascript("window.NicheUI&&NicheUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); }); }
    void pushInsets() { final float t = insT / dp, bt = insB / dp; ui.post(() -> { if (web != null) web.evaluateJavascript("window.NicheUI&&NicheUI.insets(" + t + "," + bt + ")", null); }); }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        try {
            String path = p.equals("/") ? "/niche.html" : p;
            InputStream in = Pelage.open(this, path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    boolean liveWallpaperOn() {
        try {
            android.app.WallpaperInfo wi = WallpaperManager.getInstance(this).getWallpaperInfo();
            return wi != null && getPackageName().equals(wi.getPackageName());
        } catch (Exception e) { return false; }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_RESTORE) return;
        if (res != RESULT_OK || data == null || data.getData() == null) { emit("restore", "{\"st\":\"cancel\"}"); return; }
        final Uri src = data.getData();
        emit("restore", "{\"st\":\"reading\"}");
        new Thread(() -> {
            try {
                JSONObject man = NicheBackup.prepare(this, src);
                emit("restore", new JSONObject().put("st", "ready").put("man", man).toString());
            } catch (Exception e) {
                try { emit("restore", new JSONObject().put("st", "error").put("msg", String.valueOf(e.getMessage())).toString()); } catch (Exception ignored) { }
            }
        }).start();
    }

    class Bridge {
        @JavascriptInterface public String info() {
            try {
                android.content.SharedPreferences p = Pelage.sp(NicheActivity.this);
                JSONArray pel = new JSONArray();
                for (String[] x : Pelage.ALL) pel.put(new JSONObject().put("id", x[0]).put("nom", x[1]).put("acc", x[2]).put("acc2", x[3]).put("bg", x[4]));
                return new JSONObject().put("pelage", Pelage.id(NicheActivity.this)).put("pelages", pel)
                        .put("lastBackup", p.getLong("lastBackup", 0)).put("lastBackupName", p.getString("lastBackupName", ""))
                        .put("restoredAt", p.getLong("restoredAt", 0)).put("live", liveWallpaperOn())
                        .put("siesteBright", p.getBoolean("siesteBright", false)).put("busy", busy)
                        .put("sons", p.getBoolean("sons", true)).put("sonsVol", p.getInt("sonsVol", 60))
                        .put("sonsNav", p.getBoolean("sonsNav", true)).put("sonsClavier", p.getBoolean("sonsClavier", false))
                        .put("sonsCharge", p.getBoolean("sonsCharge", true)).put("sonsVerrou", p.getBoolean("sonsVerrou", false))
                        .put("silent", PupSons.silent(NicheActivity.this))
                        .put("verrou", p.getBoolean("verrou", false)).put("verrouCadre", p.getBoolean("verrouCadre", true))
                        .put("verrouPattes", p.getBoolean("verrouPattes", true)).put("verrouChiot", p.getBoolean("verrouChiot", true))
                        .put("verrouEtoiles", p.getBoolean("verrouEtoiles", true)).put("verrouCharge", p.getBoolean("verrouCharge", true))
                        .put("verrouForce", p.getInt("verrouForce", 1)).put("a11y", PupNavA11y.I != null)
                        .put("version", PupUpdate.current(NicheActivity.this)).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void pelage(String id) {
            ui.post(() -> {
                Pelage.set(NicheActivity.this, id);
                web.setBackgroundColor(Pelage.bg(NicheActivity.this));
                Pelage.watch(NicheActivity.this, web); // recharge cette page aux nouvelles couleurs
            });
        }
        @JavascriptInterface public void backup() {
            if (busy) return;
            busy = true;
            new Thread(() -> {
                try {
                    String name = NicheBackup.backup(NicheActivity.this, (what) -> {
                        try { emit("backup", new JSONObject().put("st", "step").put("msg", what).toString()); } catch (Exception ignored) { }
                    });
                    emit("backup", new JSONObject().put("st", "done").put("name", name).toString());
                } catch (Exception e) {
                    try { emit("backup", new JSONObject().put("st", "error").put("msg", String.valueOf(e.getMessage())).toString()); } catch (Exception ignored) { }
                } finally { busy = false; }
            }).start();
        }
        @JavascriptInterface public void pickRestore() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(i, REQ_RESTORE); } catch (Exception e) { emit("restore", "{\"st\":\"error\",\"msg\":\"Aucun sélecteur de fichiers\"}"); }
            });
        }
        @JavascriptInterface public void cancelRestore() { NicheBackup.staged(NicheActivity.this).delete(); }
        @JavascriptInterface public void restoreNow() {
            if (!NicheBackup.commit(NicheActivity.this)) { emit("restore", "{\"st\":\"error\",\"msg\":\"L'os a disparu, choisis-le à nouveau\"}"); return; }
            ui.post(() -> {
                Intent i = new Intent(NicheActivity.this, PhoenixActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finishAffinity();
            });
        }
        @JavascriptInterface public void wallpaper() {
            ui.post(() -> {
                Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(NicheActivity.this, PupLiveWallpaper.class));
                try { startActivity(i); }
                catch (Exception e) {
                    try { startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); } catch (Exception ignored) { }
                }
            });
        }
        @JavascriptInterface public void siestePreview() { ui.post(() -> startActivity(new Intent(NicheActivity.this, SiesteActivity.class))); }
        @JavascriptInterface public void dreamSettings() {
            ui.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); } catch (Exception ignored) { } }
            });
        }
        @JavascriptInterface public void set(String k, boolean v) {
            if (!k.matches("siesteBright|sons|sonsNav|sonsClavier|sonsCharge|sonsVerrou|verrou|verrouCadre|verrouPattes|verrouChiot|verrouEtoiles|verrouCharge")) return;
            android.content.SharedPreferences.Editor e = Pelage.sp(NicheActivity.this).edit().putBoolean(k, v);
            if ("sons".equals(k)) e.putLong("ver", System.currentTimeMillis()); // les Pup-apps rechargent leurs sons
            e.commit();
            if (k.startsWith("verrou")) decoRefresh();
        }
        @JavascriptInterface public void setInt(String k, int v) {
            if (!k.matches("sonsVol|verrouForce")) return;
            android.content.SharedPreferences.Editor e = Pelage.sp(NicheActivity.this).edit().putInt(k, v);
            if ("sonsVol".equals(k)) e.putLong("ver", System.currentTimeMillis());
            e.commit();
            if (k.startsWith("verrou")) decoRefresh();
        }
        void decoRefresh() { ui.post(() -> { PupNavA11y a = PupNavA11y.I; if (a != null && a.deco != null) a.deco.refresh(); }); }
        /** Montre la déco par-dessus l'écran actuel pendant quelques secondes. */
        @JavascriptInterface public boolean decoPreview() {
            PupNavA11y a = PupNavA11y.I;
            if (a == null || a.deco == null) return false;
            ui.post(() -> a.deco.preview(7000));
            return true;
        }
        @JavascriptInterface public void openA11y() { ui.post(() -> { try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void close() { ui.post(NicheActivity.this::finish); }
    }
}

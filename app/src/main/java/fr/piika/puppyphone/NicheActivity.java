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
    @Override protected void onResume() { super.onResume(); Pelage.watch(this, web); syncLauncherWall(); emit("resume", ""); }

    /** Quand un fond animé PuppyPhone est posé, l'accueil PuppyPhone l'affiche aussi (mode « Fond du téléphone »). */
    void syncLauncherWall() {
        android.content.SharedPreferences w = getSharedPreferences(PupWallService.PREFS, MODE_PRIVATE);
        String type = w.getString("wall_type", "neon");
        if (liveWallpaperOn()) {
            if (!"system".equals(type)) w.edit().putString("wall_before_live", type).putString("wall_type", "system").putLong("wall_ver", System.currentTimeMillis()).commit();
        } else if ("system".equals(type) && w.contains("wall_before_live")) {
            w.edit().putString("wall_type", w.getString("wall_before_live", "neon")).remove("wall_before_live").putLong("wall_ver", System.currentTimeMillis()).commit();
        }
    }
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

    /** Fond animé PuppyPhone actuellement posé : neon / cosmos / aurore, ou "" si aucun. */
    String liveId() {
        try {
            android.app.WallpaperInfo wi = WallpaperManager.getInstance(this).getWallpaperInfo();
            if (wi == null || !getPackageName().equals(wi.getPackageName())) return "";
            String n = wi.getServiceName();
            return n.endsWith("Cosmos") ? "cosmos" : n.endsWith("Aurore") ? "aurore" : n.endsWith("Niche") ? "niche" : n.endsWith("Lune") ? "lune" : n.endsWith("Foret") ? "foret" : n.endsWith("Arcade") ? "arcade" : n.endsWith("Chalet") ? "chalet" : "neon";
        } catch (Exception e) { return ""; }
    }
    /** Le verrouillage a-t-il sa propre image (qui cacherait le fond animé) ? */
    boolean lockOwn() {
        try { return WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_LOCK) > 0; } catch (Exception e) { return false; }
    }
    static Class<?> wallClass(String id) { return "cosmos".equals(id) ? PupWallCosmos.class : "aurore".equals(id) ? PupWallAurore.class : "niche".equals(id) ? PupWallNiche.class : "lune".equals(id) ? PupWallLune.class : "foret".equals(id) ? PupWallForet.class : "arcade".equals(id) ? PupWallArcade.class : "chalet".equals(id) ? PupWallChalet.class : PupLiveWallpaper.class; }

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
                        .put("restoredAt", p.getLong("restoredAt", 0)).put("live", liveWallpaperOn()).put("liveId", liveId())
                        .put("siesteLum", Sieste.lum(NicheActivity.this)).put("siesteDuree", Sieste.duree(NicheActivity.this)).put("busy", busy)
                        .put("sons", p.getBoolean("sons", true)).put("sonsVol", p.getInt("sonsVol", 60))
                        .put("sonsNav", p.getBoolean("sonsNav", true)).put("sonsClavier", p.getBoolean("sonsClavier", false))
                        .put("sonsCharge", p.getBoolean("sonsCharge", true)).put("sonsVerrou", p.getBoolean("sonsVerrou", false))
                        .put("silent", PupSons.silent(NicheActivity.this))
                        .put("verrou", p.getBoolean("verrou", false)).put("verrouCadre", p.getBoolean("verrouCadre", true))
                        .put("verrouPattes", p.getBoolean("verrouPattes", true)).put("verrouChiot", p.getBoolean("verrouChiot", true))
                        .put("verrouEtoiles", p.getBoolean("verrouEtoiles", true)).put("verrouCharge", p.getBoolean("verrouCharge", true))
                        .put("verrouForce", p.getInt("verrouForce", 1)).put("veille", p.getBoolean("veille", false)).put("siesteCharge", p.getBoolean("siesteCharge", false)).put("veilleStyle", p.getString("veilleStyle", "verre")).put("veilleQuand", p.getInt("veilleQuand", 0)).put("veilleDuree", p.getInt("veilleDuree", 30)).put("veilleLum", p.getInt("veilleLum", 0)).put("a11y", PupNavA11y.I != null)
                        .put("voletOn", p.getBoolean("voletOn", true)).put("popOn", p.getBoolean("popOn", true)).put("aodNotif", p.getBoolean("aodNotif", true)).put("aodNotifTxt", p.getBoolean("aodNotifTxt", false))
                        .put("homeNotif", p.getBoolean("homeNotif", true)).put("voletForce", p.getBoolean("voletForce", true)).put("popForce", p.getBoolean("popForce", true)).put("wallLock", p.getString("wallLock", "")).put("lockOwn", lockOwn()).put("samsung", "samsung".equalsIgnoreCase(Build.MANUFACTURER)).put("lockVid", p.getString("lockVid", "")).put("lockVidId", p.getString("lockVidId", "")).put("voletZone", p.getInt("voletZone", 0)).put("notifOk", PupNotifs.granted(NicheActivity.this))
                        .put("writeOk", Settings.System.canWrite(NicheActivity.this)).put("dndOk", getSystemService(android.app.NotificationManager.class).isNotificationPolicyAccessGranted())
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
        @JavascriptInterface public void wallpaper(String id) {
            ui.post(() -> {
                Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(NicheActivity.this, wallClass(id)));
                try { startActivity(i); }
                catch (Exception e) {
                    try { startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); } catch (Exception ignored) { }
                }
            });
        }
        /** Retire l'image propre à l'écran de verrouillage : Android y affiche alors le fond animé de l'accueil. */
        @JavascriptInterface public boolean lockToo() {
            try { WallpaperManager.getInstance(NicheActivity.this).clear(WallpaperManager.FLAG_LOCK); return true; }
            catch (Exception e) { return false; }
        }
        @JavascriptInterface public void siestePreview() { ui.post(() -> startActivity(new Intent(NicheActivity.this, SiesteActivity.class).putExtra("preview", true))); }
        @JavascriptInterface public void dreamSettings() {
            ui.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); } catch (Exception ignored) { } }
            });
        }
        @JavascriptInterface public void set(String k, boolean v) {
            if (!k.matches("veille|siesteCharge|siesteBright|sons|sonsNav|sonsClavier|sonsCharge|sonsVerrou|verrou|verrouCadre|verrouPattes|verrouChiot|verrouEtoiles|verrouCharge|voletOn|popOn|aodNotif|aodNotifTxt|homeNotif|voletForce|popForce")) return;
            android.content.SharedPreferences.Editor e = Pelage.sp(NicheActivity.this).edit().putBoolean(k, v);
            if ("sons".equals(k)) e.putLong("ver", System.currentTimeMillis()); // les Pup-apps rechargent leurs sons
            e.commit();
            if (k.startsWith("verrou")) decoRefresh();
            if (k.equals("voletOn")) ui.post(PupVolet::update);
        }
        @JavascriptInterface public void setInt(String k, int v) {
            if (!k.matches("sonsVol|verrouForce|siesteLum|siesteDuree|veilleQuand|veilleDuree|veilleLum|voletZone")) return;
            android.content.SharedPreferences.Editor e = Pelage.sp(NicheActivity.this).edit().putInt(k, v);
            if ("sonsVol".equals(k)) e.putLong("ver", System.currentTimeMillis());
            e.commit();
            if (k.startsWith("verrou")) decoRefresh();
            if (k.equals("voletZone")) ui.post(PupVolet::update);
        }
        void decoRefresh() { ui.post(() -> { PupNavA11y a = PupNavA11y.I; if (a != null && a.deco != null) a.deco.refresh(); }); }
        /** Montre la déco par-dessus l'écran actuel pendant quelques secondes. */
        @JavascriptInterface public boolean decoPreview() {
            PupNavA11y a = PupNavA11y.I;
            if (a == null || a.deco == null) return false;
            ui.post(() -> a.deco.preview(7000));
            return true;
        }
        @JavascriptInterface public void setStr(String k, String v) {
            if ("veilleStyle".equals(k) && v.matches("verre|os")) Pelage.sp(NicheActivity.this).edit().putString(k, v).commit();
            if ("wallLock".equals(k) && v.matches("|neon|cosmos|aurore|niche|lune|foret|arcade|chalet")) Pelage.sp(NicheActivity.this).edit().putString(k, v).commit();
        }
        @JavascriptInterface public void veillePreview() { ui.post(() -> startActivity(new Intent(NicheActivity.this, VeilleActivity.class).putExtra("preview", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))); }
        /** Range le GIF de batterie puppy dans la Galerie (Images › PupAOD) pour l'Always On Display de Samsung. */
        @JavascriptInterface public String aodSave(String style) {
            if (!style.matches("verre|os")) return "";
            String name = "pup_aod_" + style + ".gif";
            try (java.io.InputStream in = getAssets().open("www/aod/" + style + ".gif")) {
                java.io.OutputStream out;
                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/gif");
                    cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/PupAOD");
                    Uri u = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    out = getContentResolver().openOutputStream(u);
                } else {
                    java.io.File d = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "PupAOD"); d.mkdirs();
                    out = new java.io.FileOutputStream(new java.io.File(d, name));
                }
                try (java.io.OutputStream o = out) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); }
                return name;
            } catch (Exception e) { return ""; }
        }
        /** Ouvre les réglages Always On Display de Samsung (ou l'écran de verrouillage si introuvable). */
        @JavascriptInterface public void openAod() {
            ui.post(() -> {
                Intent[] tries = {
                        new Intent("com.samsung.android.app.aodservice.intent.action.AOD_SETTINGS"),
                        new Intent().setClassName("com.samsung.android.app.aodservice", "com.samsung.android.app.aodservice.settings.AODSettingsActivity"),
                        new Intent().setClassName("com.android.settings", "com.android.settings.Settings$LockscreenSettingsActivity"),
                        new Intent(Settings.ACTION_DISPLAY_SETTINGS)};
                for (Intent i : tries) { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Exception ignored) { } }
            });
        }
        // ------------------------------------------------ verrouillage One UI : vidéo pour la Galerie Samsung
        volatile boolean filming;
        @JavascriptInterface public void lockFilm(String id) {
            if (filming || !id.matches("neon|cosmos|aurore|niche|lune|foret|arcade|chalet")) return;
            filming = true;
            new Thread(() -> {
                try {
                    Uri u = PupWallVideo.film(NicheActivity.this, id, k -> { try { emit("film", new JSONObject().put("st", "work").put("p", k).toString()); } catch (Exception ignored) { } });
                    Pelage.sp(NicheActivity.this).edit().putString("lockVid", u.toString()).putString("lockVidId", id).apply();
                    emit("film", new JSONObject().put("st", "done").put("uri", u.toString()).put("id", id).toString());
                } catch (Throwable e) {
                    try { emit("film", new JSONObject().put("st", "error").put("msg", String.valueOf(e.getMessage())).toString()); } catch (Exception ignored) { }
                } finally { filming = false; }
            }, "PupWallVideo").start();
        }
        /** Ouvre la Galerie Samsung (sur la vidéo si elle accepte, sinon sur l'appli : Albums › PupWall). Renvoie "video", "app" ou "". */
        @JavascriptInterface public String lockGallery(String uri) {
            Uri u = Uri.parse(uri);
            String g = "com.sec.android.gallery3d";
            for (String type : new String[]{"video/mp4", "video/*", "image/*"}) {
                Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, type).setPackage(g).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                if (v.resolveActivity(getPackageManager()) != null) { try { startActivity(v); return "video"; } catch (Exception ignored) { } }
            }
            Intent l = getPackageManager().getLaunchIntentForPackage(g);
            if (l != null) { try { startActivity(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "app"; } catch (Exception ignored) { } }
            return "";
        }
        /** Le cadreur « Définir comme fond d'écran » de One UI (celui qu'utilise la Galerie). */
        @JavascriptInterface public boolean lockCrop(String uri) {
            try {
                Intent i = WallpaperManager.getInstance(NicheActivity.this).getCropAndSetWallpaperIntent(Uri.parse(uri));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i); return true;
            } catch (Exception e) {
                try {
                    Intent i = new Intent("android.service.wallpaper.CROP_AND_SET_WALLPAPER").setDataAndType(Uri.parse(uri), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (i.resolveActivity(getPackageManager()) == null) return false;
                    startActivity(i); return true;
                } catch (Exception e2) { return false; }
            }
        }
        /** « Définir comme… » d'Android : One UI y propose parfois directement « Fond d'écran ». */
        @JavascriptInterface public void lockSetAs(String uri) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(Uri.parse(uri), "video/mp4").putExtra("mimeType", "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(i, "Définir comme… 🐾")); } catch (Exception ignored) { }
            });
        }
        @JavascriptInterface public boolean lockStill(String id) {
            if (!id.matches("neon|cosmos|aurore|niche|lune|foret|arcade|chalet")) return false;
            return PupWallVideo.still(NicheActivity.this, id);
        }
        @JavascriptInterface public void notifAccess() { ui.post(() -> PupNotifs.openSettings(NicheActivity.this)); }
        @JavascriptInterface public void writeAccess() { ui.post(() -> { try { startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void dndAccess() { ui.post(() -> { try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); } catch (Exception ignored) { } }); }
        @JavascriptInterface public String voletDiag() { return PupVolet.diagText(); }
        @JavascriptInterface public boolean voletTest() { return PupVolet.openFromApp(); }
        /** Une vraie notification de test (canal « important ») pour voir le pop-up puppyplay. */
        @JavascriptInterface public void popTest() {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                ui.post(() -> requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 47));
            ui.postDelayed(() -> {
                try {
                    android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
                    if (nm.getNotificationChannel("puptest") == null) nm.createNotificationChannel(new android.app.NotificationChannel("puptest", "Tests PuppyPhone", android.app.NotificationManager.IMPORTANCE_HIGH));
                    android.app.Notification n = new android.app.Notification.Builder(NicheActivity.this, "puptest").setSmallIcon(R.drawable.ic_paw)
                            .setContentTitle("🐶 Wouf ! Notification de test").setContentText("Voilà à quoi ressemblent tes notifications en version puppyplay 🐾")
                            .setAutoCancel(true).setColor(0xFFFF3FA4)
                            .setContentIntent(android.app.PendingIntent.getActivity(NicheActivity.this, 4700, new Intent(NicheActivity.this, NicheActivity.class), android.app.PendingIntent.FLAG_IMMUTABLE)).build();
                    nm.notify(4700, n);
                } catch (Exception ignored) { }
            }, 1800);
        }
        @JavascriptInterface public void openA11y() { ui.post(() -> { try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void close() { ui.post(NicheActivity.this::finish); }
    }
}

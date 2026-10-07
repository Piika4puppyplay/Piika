package fr.piika.puppyphone;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.LruCache;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** PupTasks — « 🐾 Les dernières balades » : multitâche puppyplay. */
public class TasksActivity extends Activity {
    static final String HOST = "puptasks.local";
    static final Map<String, String[]> SELF = new HashMap<>();
    static {
        SELF.put("BrowserActivity", new String[]{"PuppyInternet", "pupnet", "cyan"});
        SELF.put("GalleryActivity", new String[]{"PupGalery", "gallery", "pink"});
        SELF.put("VideoActivity", new String[]{"PupVidéo", "film", "red"});
        SELF.put("CameraActivity", new String[]{"PupCamera", "camera", "orange"});
        SELF.put("SmsActivity", new String[]{"PupSMS", "sms", "orange"});
        SELF.put("DialerActivity", new String[]{"PupPhone", "phone", "green"});
        SELF.put("CallActivity", new String[]{"PupPhone", "phone", "green"});
        SELF.put("MusicActivity", new String[]{"PupMusic", "music", "pink"});
        SELF.put("FileActivity", new String[]{"PupFile", "folder", "amber"});
        SELF.put("PupSonActivity", new String[]{"PupSon", "speaker", "violet"});
    }
    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    float dp = 1f;
    int insT, insB;
    String current = "";   // appli « en cours » au moment de l'ouverture
    boolean wantSettings;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        dp = getResources().getDisplayMetrics().density;
        wantSettings = getIntent() != null && getIntent().getBooleanExtra("settings", false);
        current = PupNavA11y.fgPkg + "|" + PupNavA11y.fgClass;
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0614);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !HOST.equals(r.getUrl().getHost()); }
            @Override public void onPageFinished(WebView v, String u) { pushInsets(); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Tasks");
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v, ins) -> {
            insT = ins.getSystemWindowInsetTop();
            insB = ins.getSystemWindowInsetBottom();
            pushInsets();
            return ins.consumeSystemWindowInsets();
        });
        web.loadUrl("https://" + HOST + "/tasks.html" + (wantSettings ? "?settings=1" : ""));
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        current = PupNavA11y.fgPkg + "|" + PupNavA11y.fgClass;
        emit("reload", i.getBooleanExtra("settings", false) ? "settings" : "");
    }
    @Override protected void onResume() { super.onResume(); emit("resume", ""); PupNav.ensure(this); }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
    @Override public void onBackPressed() { web.evaluateJavascript("(window.TasksUI&&TasksUI.back())?'y':'n'", v -> { if (!"\"y\"".equals(v)) finishAndRemoveTask(); }); }

    void emit(String ev, String data) { ui.post(() -> { if (web != null) web.evaluateJavascript("window.TasksUI&&TasksUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); }); }
    void pushInsets() { final float t = insT / dp, bt = insB / dp; ui.post(() -> web.evaluateJavascript("window.TasksUI&&TasksUI.insets(" + t + "," + bt + ")", null)); }
    void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }
    SharedPreferences prefs() { return PupNav.prefs(this); }

    boolean hasUsage() {
        try {
            AppOpsManager ao = getSystemService(AppOpsManager.class);
            int m = Build.VERSION.SDK_INT >= 29 ? ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                    : ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return m == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    Set<String> launchers() {
        Set<String> s = new HashSet<>();
        try {
            for (ResolveInfo r : getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) s.add(r.activityInfo.packageName);
        } catch (Exception ignored) { }
        return s;
    }

    static String shortCls(String c) { return c == null ? "" : c.substring(c.lastIndexOf('.') + 1); }

    /** Liste des cartes : la plus récente d'abord. */
    String listJson() {
        JSONArray out = new JSONArray();
        if (!hasUsage()) return out.toString();
        UsageStatsManager us = getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        LinkedHashMap<String, Object[]> last = new LinkedHashMap<>(); // clé → {pkg, cls, temps}
        try {
            UsageEvents ev = us.queryEvents(now - 3L * 86400000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
                String pkg = e.getPackageName(), cls = e.getClassName();
                String key;
                if (getPackageName().equals(pkg)) {
                    String sc = shortCls(cls);
                    if (!SELF.containsKey(sc)) continue;           // lanceur, PupTasks…
                    if ("CallActivity".equals(sc)) sc = "DialerActivity";
                    key = "self:" + sc;
                } else key = pkg;
                last.remove(key);
                last.put(key, new Object[]{pkg, cls, e.getTimeStamp()});
            }
        } catch (Exception ignored) { }
        Set<String> home = launchers();
        PackageManager pm = getPackageManager();
        SharedPreferences p = prefs();
        List<String> keys = new ArrayList<>(last.keySet());
        java.util.Collections.reverse(keys);
        String curPkg = current.split("\\|", -1)[0], curCls = shortCls(current.split("\\|", -1).length > 1 ? current.split("\\|", -1)[1] : "");
        if ("CallActivity".equals(curCls)) curCls = "DialerActivity";
        ActivityManager am = getSystemService(ActivityManager.class);
        for (String k : keys) {
            Object[] v = last.get(k);
            String pkg = (String) v[0];
            long t = (long) v[2];
            if (t <= p.getLong("dis_" + k, 0)) continue;
            if (!k.startsWith("self:") && (home.contains(pkg) || "com.android.systemui".equals(pkg))) continue;
            try {
                JSONObject o = new JSONObject().put("key", k).put("pkg", pkg).put("t", t);
                if (k.startsWith("self:")) {
                    String[] s = SELF.get(k.substring(5));
                    o.put("name", s[0]).put("glyph", s[1]).put("color", s[2]).put("self", true)
                            .put("running", selfTaskAlive(am, k.substring(5)));
                    o.put("current", getPackageName().equals(curPkg) && k.equals("self:" + curCls));
                } else {
                    if (pm.getLaunchIntentForPackage(pkg) == null) continue;
                    o.put("name", String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)))).put("self", false);
                    o.put("current", pkg.equals(curPkg));
                }
                out.put(o);
            } catch (Exception ignored) { }
            if (out.length() >= 40) break;
        }
        return out.toString();
    }

    boolean selfTaskAlive(ActivityManager am, String cls) {
        try {
            for (ActivityManager.AppTask t : am.getAppTasks()) {
                ActivityManager.RecentTaskInfo i = t.getTaskInfo();
                ComponentName c = i.baseActivity != null ? i.baseActivity : i.baseIntent != null ? i.baseIntent.getComponent() : null;
                if (c != null && cls.equals(shortCls(c.getClassName()))) return true;
                if ("DialerActivity".equals(cls) && c != null && "CallActivity".equals(shortCls(c.getClassName()))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    String memJson() {
        try {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            getSystemService(ActivityManager.class).getMemoryInfo(mi);
            return new JSONObject().put("avail", mi.availMem).put("total", mi.totalMem).put("low", mi.lowMemory).toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Ferme une carte : nos modules → vraie fermeture ; applis tierces → killBackgroundProcesses. */
    void close(String key) {
        ActivityManager am = getSystemService(ActivityManager.class);
        prefs().edit().putLong("dis_" + key, System.currentTimeMillis()).apply();
        if (key.startsWith("self:")) {
            String cls = key.substring(5);
            try {
                for (ActivityManager.AppTask t : am.getAppTasks()) {
                    ActivityManager.RecentTaskInfo i = t.getTaskInfo();
                    ComponentName c = i.baseActivity != null ? i.baseActivity : i.baseIntent != null ? i.baseIntent.getComponent() : null;
                    if (c == null) continue;
                    String sc = shortCls(c.getClassName());
                    if (cls.equals(sc) || ("DialerActivity".equals(cls) && "CallActivity".equals(sc) && PupInCallService.current() == null)) t.finishAndRemoveTask();
                }
            } catch (Exception ignored) { }
            if ("MusicActivity".equals(cls)) {
                MusicService s = MusicService.I;
                if (s != null && !s.isPlaying()) { s.saveState(); s.stopSelf(); }
            }
        } else {
            try { am.killBackgroundProcesses(key); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ icônes
    final LruCache<String, byte[]> icons = new LruCache<>(120);
    byte[] icon(String key) {
        byte[] b = icons.get(key);
        if (b != null) return b;
        try {
            PackageManager pm = getPackageManager();
            Drawable d;
            if (key.startsWith("self:")) d = pm.getActivityIcon(new ComponentName(this, getPackageName() + "." + key.substring(5)));
            else d = pm.getApplicationIcon(key);
            Bitmap bm = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 192, 192);
            d.draw(c);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.PNG, 100, o);
            b = o.toByteArray();
            icons.put(key, b);
            return b;
        } catch (Exception e) { return new byte[0]; }
    }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        try {
            if (p.equals("/icon")) {
                byte[] b = icon(u.getQueryParameter("k"));
                if (b.length == 0) throw new Exception();
                h.put("Cache-Control", "max-age=86400");
                return new WebResourceResponse("image/png", null, 200, "OK", h, new ByteArrayInputStream(b));
            }
            String path = p.equals("/") ? "/tasks.html" : p;
            InputStream in = getAssets().open("www" + path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    class Bridge {
        @JavascriptInterface public String list() { return listJson(); }
        @JavascriptInterface public String mem() { return memJson(); }
        @JavascriptInterface public void open(String key) {
            ui.post(() -> {
                try {
                    Intent i;
                    if (key.startsWith("self:")) {
                        String cls = key.substring(5);
                        if ("DialerActivity".equals(cls) && (PupInCallService.current() != null || PupInCallService.ringing() != null)) cls = "CallActivity";
                        i = new Intent().setComponent(new ComponentName(TasksActivity.this, getPackageName() + "." + cls));
                    } else {
                        i = getPackageManager().getLaunchIntentForPackage(key);
                        if (i == null) throw new Exception();
                        i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    finishAndRemoveTask();
                } catch (Exception e) { toast("Impossible d'ouvrir cette appli"); }
            });
        }
        @JavascriptInterface public void close(String key) { TasksActivity.this.close(key); }
        @JavascriptInterface public void home() { ui.post(() -> { PupNav.goHome(TasksActivity.this); finishAndRemoveTask(); }); }
        @JavascriptInterface public void exit() { ui.post(TasksActivity.this::finishAndRemoveTask); }
        @JavascriptInterface public String perms() {
            try {
                return new JSONObject().put("usage", hasUsage()).put("a11y", PupNav.a11yOn(TasksActivity.this)).put("a11yLive", PupNavA11y.I != null)
                        .put("overlay", PupNav.canOverlay(TasksActivity.this)).put("sdk", Build.VERSION.SDK_INT).put("brand", Build.MANUFACTURER).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public String settings() {
            SharedPreferences p = prefs();
            try {
                return new JSONObject().put("style", p.getString("style", "off")).put("order", p.getString("order", "samsung")).put("height", p.getInt("height", 54))
                        .put("alpha", p.getInt("alpha", 100)).put("edge", p.getBoolean("edge", false)).put("edgeSide", p.getString("edgeSide", "right")).put("edgeY", p.getInt("edgeY", -60)).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void set(String json) {
            try {
                JSONObject o = new JSONObject(json);
                SharedPreferences.Editor e = prefs().edit();
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next(); Object v = o.get(k);
                    if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                    else if (v instanceof Integer) e.putInt(k, (Integer) v);
                    else e.putString(k, String.valueOf(v));
                }
                e.apply();
                ui.post(() -> PupNav.ensure(TasksActivity.this));
            } catch (Exception ignored) { }
        }
        @JavascriptInterface public void openUsage() { ui.post(() -> { try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (Exception ignored) { } } }); }
        @JavascriptInterface public void openOverlay() { ui.post(() -> { try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void openA11y() { ui.post(() -> { try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void openAppInfo() { ui.post(() -> { try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void haptic() { ui.post(() -> web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)); }
        @JavascriptInterface public void toast(String s) { TasksActivity.this.toast(s); }
    }
}

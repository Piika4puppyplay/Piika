package fr.piika.puppyphone;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    static final String HOST = "pup.local";
    static final String START = "https://" + HOST + "/index.html";
    static final int REQ_WALL_VIDEO = 11, REQ_WALL_IMAGE = 12, REQ_HOME_ROLE = 13, REQ_PERM_BT = 14, REQ_NOTIF = 15;

    WebView web;
    TextureView tex;
    FrameLayout root;
    LauncherApps la;
    UserHandle user;
    final Handler ui = new Handler(Looper.getMainLooper());
    final Map<String, byte[]> iconCache = new HashMap<>();
    PupWallService wall;
    boolean bound;
    Surface surface;
    boolean torchOn;
    String torchId;
    CameraManager cam;
    float density = 1f;
    int insetTop, insetBottom;
    boolean pageReady;
    BroadcastReceiver statusRx;
    LauncherApps.Callback laCb;
    CameraManager.TorchCallback torchCb;

    // ---------------------------------------------------------------- cycle de vie
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        density = getResources().getDisplayMetrics().density;
        la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        user = Process.myUserHandle();

        root = new FrameLayout(this);
        tex = new TextureView(this);
        tex.setVisibility(View.GONE);
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int ww, int hh) {
                surface = new Surface(st);
                if (wall != null) wall.setSurface(surface);
                fitVideo();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int ww, int hh) { fitVideo(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (wall != null) wall.setSurface(null);
                if (surface != null) surface.release();
                surface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        root.addView(tex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insetTop = ins.getSystemWindowInsetTop();
            insetBottom = ins.getSystemWindowInsetBottom();
            pushInsets();
            return ins.consumeSystemWindowInsets();
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setOffscreenPreRaster(true);              // pré-calcule l'écran sur le GPU : défilement plus fluide
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serve(request.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
                return true;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushInsets();
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Pup");
        web.loadUrl(START);

        registerWatchers();
        applyWallMode();

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && "video".equals(wallPrefs().getString("wall_type", ""))) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume(); Pelage.watch(this, web);
        if (web != null) web.onResume();
        try { PupNav.ensure(this); } catch (Exception ignored) { }
        try { PupUpdate.autoCheck(this); } catch (Exception ignored) { }
        // fond choisi depuis PupGalery / PupVidéo ?
        long ver = wallPrefs().getLong("wall_ver", 0);
        if (ver != appliedWallVer) { applyWallMode(); emit("wall", wallInfo()); }
        emit("resume", "");
    }

    @Override
    protected void onStop() {
        // une autre appli est devant : on fige les animations pour qu'elle reste fluide
        emit("pause", "");
        ui.postDelayed(() -> { if (web != null && stopped) web.onPause(); }, 120);
        stopped = true;
        super.onStop();
    }

    boolean stopped;

    @Override protected void onStart() { stopped = false; super.onStart(); if (web != null) web.onResume(); }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction())) emit("home", "");
    }

    @Override
    public void onBackPressed() {
        if (web == null) return;
        web.evaluateJavascript("(window.PupNative && PupNative.back()) ? 'y' : 'n'", v -> { });
    }

    @Override
    protected void onDestroy() {
        try { if (statusRx != null) unregisterReceiver(statusRx); } catch (Exception ignored) { }
        try { if (laCb != null) la.unregisterCallback(laCb); } catch (Exception ignored) { }
        try { if (torchCb != null && cam != null) cam.unregisterTorchCallback(torchCb); } catch (Exception ignored) { }
        try { if (bound) unbindService(conn); } catch (Exception ignored) { }
        bound = false;
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- JS <-> natif
    void emit(String ev, String data) {
        ui.post(() -> {
            if (web == null) return;
            web.evaluateJavascript("window.PupNative&&PupNative.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null);
        });
    }

    void pushInsets() {
        if (web == null) return;
        final float t = insetTop / density, bt = insetBottom / density;
        ui.post(() -> web.evaluateJavascript("window.PupNative&&PupNative.insets(" + t + "," + bt + ")", null));
    }

    // ---------------------------------------------------------------- fichiers servis à la page
    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        try {
            if (p.equals("/icon")) {
                byte[] png = appIcon(u.getQueryParameter("id"), "1".equals(u.getQueryParameter("raw")), String.valueOf(u.getQueryParameter("v")));
                if (png == null) return notFound();
                h.put("Cache-Control", "max-age=31536000");
                return new WebResourceResponse("image/png", null, 200, "OK", h, new ByteArrayInputStream(png));
            }
            if (p.equals("/sicon")) {
                byte[] png = shortcutIcon(u.getQueryParameter("pkg"), u.getQueryParameter("sid"));
                if (png == null) return notFound();
                return new WebResourceResponse("image/png", null, 200, "OK", h, new ByteArrayInputStream(png));
            }
            if (p.equals("/wall")) {
                String wu = wallPrefs().getString("img_uri", null);
                if (wu == null) return notFound();
                InputStream in = getContentResolver().openInputStream(Uri.parse(wu));
                return new WebResourceResponse("image/*", null, 200, "OK", h, in);
            }
            String path = p.equals("/") ? "/index.html" : p;
            InputStream in = Pelage.open(this, path);
            String mime = mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return notFound();
        }
    }

    WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    byte[] appIcon(String id, boolean raw, String ver) {
        if (id == null) return null;
        String key = id + (raw ? "#r" : "#o");
        synchronized (iconCache) {
            if (iconCache.containsKey(key)) return iconCache.get(key);
        }
        File f = new File(new File(getCacheDir(), "icons"), Integer.toHexString((key + "@" + ver).hashCode()) + ".png");
        try {
            if (f.exists()) {
                byte[] b = Files.readAllBytes(f.toPath());
                synchronized (iconCache) { iconCache.put(key, b); }
                return b;
            }
        } catch (Exception ignored) { }
        Drawable d = null;
        try {
            ComponentName cn = ComponentName.unflattenFromString(id);
            if (cn != null) {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn);
                LauncherActivityInfo a = la.resolveActivity(i, user);
                if (a != null) d = a.getIcon(getResources().getDisplayMetrics().densityDpi);
                if (d == null) d = getPackageManager().getApplicationIcon(cn.getPackageName());
            } else {
                d = getPackageManager().getApplicationIcon(id);
            }
        } catch (Exception e) { return null; }
        byte[] png = toPng(d, raw);
        synchronized (iconCache) { iconCache.put(key, png); }
        try {
            f.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(png); }
        } catch (Exception ignored) { }
        return png;
    }

    byte[] shortcutIcon(String pkg, String sid) {
        try {
            if (pkg == null || sid == null || !la.hasShortcutHostPermission()) return null;
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(pkg);
            List<String> ids = new ArrayList<>();
            ids.add(sid);
            q.setShortcutIds(ids);
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> l = la.getShortcuts(q, user);
            if (l == null || l.isEmpty()) return null;
            Drawable d = la.getShortcutIconDrawable(l.get(0), getResources().getDisplayMetrics().densityDpi);
            return d == null ? null : toPng(d, false);
        } catch (Exception e) { return null; }
    }

    static byte[] toPng(Drawable d, boolean raw) {
        int s = 192;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (raw && d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
            int o = s / 4;
            Drawable bg = ad.getBackground(), fg = ad.getForeground();
            if (bg != null) { bg.setBounds(-o, -o, s + o, s + o); bg.draw(c); }
            if (fg != null) { fg.setBounds(-o, -o, s + o, s + o); fg.draw(c); }
        } else if (raw) {
            int o = s / 9;
            d.setBounds(o, o, s - o, s - o);
            d.draw(c);
        } else {
            d.setBounds(0, 0, s, s);
            d.draw(c);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- surveillance (applis, réseau, batterie, lampe)
    void registerWatchers() {
        laCb = new LauncherApps.Callback() {
            void changed(String pkg, String ev) {
                synchronized (iconCache) {
                    List<String> rm = new ArrayList<>();
                    for (String k : iconCache.keySet()) if (k.startsWith(pkg + "/") || k.startsWith(pkg + "#")) rm.add(k);
                    for (String k : rm) iconCache.remove(k);
                }
                emit("apps", ev + ":" + pkg);
            }
            @Override public void onPackageRemoved(String pkg, UserHandle u) { changed(pkg, "removed"); }
            @Override public void onPackageAdded(String pkg, UserHandle u) { changed(pkg, "added"); }
            @Override public void onPackageChanged(String pkg, UserHandle u) { changed(pkg, "changed"); }
            @Override public void onPackagesAvailable(String[] pkgs, UserHandle u, boolean r) { emit("apps", "available"); }
            @Override public void onPackagesUnavailable(String[] pkgs, UserHandle u, boolean r) { emit("apps", "unavailable"); }
            @Override public void onShortcutsChanged(String pkg, List<ShortcutInfo> s, UserHandle u) { emit("shortcuts", pkg); }
        };
        try { la.registerCallback(laCb, ui); } catch (Exception ignored) { }

        statusRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { emit("status", ""); }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusRx, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(statusRx, f);
        } catch (Exception ignored) { }

        try {
            cam = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cam.getCameraIdList()) {
                CameraCharacteristics cc = cam.getCameraCharacteristics(id);
                Boolean fl = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer face = cc.get(CameraCharacteristics.LENS_FACING);
                if (fl != null && fl && face != null && face == CameraCharacteristics.LENS_FACING_BACK) { torchId = id; break; }
            }
            torchCb = new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String id, boolean enabled) {
                    if (id.equals(torchId)) { torchOn = enabled; emit("status", ""); }
                }
            };
            cam.registerTorchCallback(torchCb, ui);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- fond d'écran
    SharedPreferences wallPrefs() { return getSharedPreferences(PupWallService.PREFS, MODE_PRIVATE); }

    final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            wall = ((PupWallService.LocalBinder) b).get();
            wall.listener = () -> ui.post(() -> { fitVideo(); emit("status", ""); });
            if (surface != null) wall.setSurface(surface);
            fitVideo();
        }
        @Override public void onServiceDisconnected(ComponentName n) { wall = null; }
    };

    long appliedWallVer;

    void applyWallMode() {
        appliedWallVer = wallPrefs().getLong("wall_ver", 0);
        ui.post(() -> {
            String type = wallPrefs().getString("wall_type", "neon");
            Window win = getWindow();
            if ("system".equals(type)) win.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            else win.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            boolean transparent = "system".equals(type) || "video".equals(type);
            web.setBackgroundColor(transparent ? Color.TRANSPARENT : 0xFF0B0614);
            root.setBackgroundColor(transparent ? Color.TRANSPARENT : 0xFF0B0614);
            if ("video".equals(type)) {
                tex.setVisibility(View.VISIBLE);
                Intent i = new Intent(this, PupWallService.class).setAction(PupWallService.ACT_START);
                try { startForegroundService(i); } catch (Exception e) { try { startService(i); } catch (Exception ignored) { } }
                if (!bound) bound = bindService(new Intent(this, PupWallService.class), conn, Context.BIND_AUTO_CREATE);
            } else {
                tex.setVisibility(View.GONE);
                if (bound) { try { unbindService(conn); } catch (Exception ignored) { } bound = false; }
                wall = null;
                if (PupWallService.running != null) PupWallService.running.shutdown();
            }
        });
    }

    void fitVideo() {
        if (wall == null || tex == null) return;
        int vw = wall.videoW, vh = wall.videoH;
        float W = tex.getWidth(), H = tex.getHeight();
        if (vw <= 0 || vh <= 0 || W <= 0 || H <= 0) return;
        float s = Math.max(W / vw, H / vh);
        Matrix m = new Matrix();
        m.setScale(vw * s / W, vh * s / H, W / 2f, H / 2f);
        tex.setTransform(m);
    }

    String fileName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return u.getLastPathSegment();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if ((req == REQ_WALL_VIDEO || req == REQ_WALL_IMAGE)) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
                SharedPreferences.Editor e = wallPrefs().edit();
                if (req == REQ_WALL_VIDEO) {
                    e.putString("wall_uri", u.toString()).putString("wall_name", fileName(u)).putString("wall_type", "video");
                } else {
                    e.putString("img_uri", u.toString()).putString("img_name", fileName(u)).putString("wall_type", "image");
                }
                e.putLong("wall_ver", System.currentTimeMillis()).commit();
                if (req == REQ_WALL_VIDEO && Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                }
                applyWallMode();
            }
            emit("wall", wallInfo());
            return;
        }
        if (req == REQ_HOME_ROLE) { emit("status", ""); return; }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_PERM_BT && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) toggleBt();
        emit("status", "");
    }

    String wallInfo() {
        SharedPreferences p = wallPrefs();
        JSONObject o = new JSONObject();
        try {
            o.put("type", p.getString("wall_type", "neon"));
            o.put("video", p.getString("wall_name", ""));
            o.put("image", p.getString("img_name", ""));
            o.put("hasVideo", p.getString("wall_uri", null) != null);
            o.put("hasImage", p.getString("img_uri", null) != null);
            o.put("muted", p.getBoolean("wall_muted", false));
            o.put("volume", p.getFloat("wall_vol", 1f));
            o.put("ver", p.getLong("wall_ver", 0));
            o.put("playing", PupWallService.running != null && PupWallService.running.isPlaying());
        } catch (Exception ignored) { }
        return o.toString();
    }

    // ---------------------------------------------------------------- interrupteurs
    void toggleBt() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) { toast("Pas de Bluetooth sur ce téléphone"); return; }
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERM_BT);
                return;
            }
            boolean on = a.isEnabled();
            if (Build.VERSION.SDK_INT < 33) {
                if (on) a.disable(); else a.enable();
                return;
            }
            if (!on) startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            else startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    boolean isDefaultHome() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME);
            }
            ResolveInfo r = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }

    String pkgFor(Intent i) {
        try {
            PackageManager pm = getPackageManager();
            ResolveInfo r = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            String p = r == null || r.activityInfo == null ? "" : r.activityInfo.packageName;
            if (p.isEmpty() || "android".equals(p) || p.contains("resolver")) {
                List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
                return l.isEmpty() ? "" : l.get(0).activityInfo.packageName;
            }
            return p;
        } catch (Exception e) { return ""; }
    }

    boolean cellular() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Exception e) { return false; }
    }

    boolean wifiConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) { return false; }
    }

    void go(Intent i) {
        ui.post(() -> {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { toast("Impossible d'ouvrir ça sur ce téléphone"); }
        });
    }

    // ---------------------------------------------------------------- le pont
    class Bridge {
        @JavascriptInterface public String version() { return "1"; }

        @JavascriptInterface public String apps() {
            JSONArray arr = new JSONArray();
            try {
                for (LauncherActivityInfo a : la.getActivityList(null, user)) {
                    JSONObject o = new JSONObject();
                    ComponentName cn = a.getComponentName();
                    ApplicationInfo ai = a.getApplicationInfo();
                    o.put("id", cn.flattenToString());
                    o.put("pkg", cn.getPackageName());
                    o.put("label", String.valueOf(a.getLabel()));
                    o.put("sysCat", Build.VERSION.SDK_INT >= 26 ? ai.category : -1);
                    o.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    o.put("installed", a.getFirstInstallTime());
                    try { o.put("updated", getPackageManager().getPackageInfo(cn.getPackageName(), 0).lastUpdateTime); } catch (Exception ignored) { }
                    arr.put(o);
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        @JavascriptInterface public void launch(String id) {
            ui.post(() -> {
                try {
                    ComponentName cn = ComponentName.unflattenFromString(id);
                    la.startMainActivity(cn, user, null, null);
                } catch (Exception e) {
                    try {
                        Intent i = getPackageManager().getLaunchIntentForPackage(id.split("/")[0]);
                        if (i != null) startActivity(i);
                        else toast("Appli introuvable");
                    } catch (Exception ex) { toast("Appli introuvable"); }
                }
            });
        }

        @JavascriptInterface public String shortcuts(String pkg) {
            JSONArray arr = new JSONArray();
            try {
                if (!la.hasShortcutHostPermission()) return "{\"denied\":true}";
                LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
                q.setPackage(pkg);
                q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
                List<ShortcutInfo> l = la.getShortcuts(q, user);
                if (l != null) for (ShortcutInfo si : l) {
                    if (!si.isEnabled()) continue;
                    JSONObject o = new JSONObject();
                    o.put("pkg", si.getPackage());
                    o.put("sid", si.getId());
                    CharSequence lab = si.getShortLabel();
                    o.put("label", lab == null ? si.getId() : lab.toString());
                    arr.put(o);
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        @JavascriptInterface public void startShortcut(String pkg, String sid) {
            ui.post(() -> {
                try { la.startShortcut(pkg, sid, null, null, user); }
                catch (Exception e) { toast("Raccourci indisponible (PuppyPhone doit être le lanceur par défaut)"); }
            });
        }

        @JavascriptInterface public String takePins() {
            SharedPreferences p = getSharedPreferences("pup_pins", MODE_PRIVATE);
            String s = p.getString("pins", "[]");
            p.edit().putString("pins", "[]").apply();
            return s;
        }

        @JavascriptInterface public String defaults() {
            JSONObject o = new JSONObject();
            try {
                o.put("dial", pkgFor(new Intent(Intent.ACTION_DIAL)));
                o.put("sms", pkgFor(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))));
                o.put("browser", pkgFor(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))));
                o.put("camera", pkgFor(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)));
                o.put("self", getPackageName());
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface public String status() {
            JSONObject o = new JSONObject();
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                o.put("wifi", wm != null && wm.isWifiEnabled());
                o.put("wifiOn", wifiConnected());
                boolean bt = false;
                try { BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter(); bt = a != null && a.isEnabled(); } catch (Throwable ignored) { }
                o.put("bt", bt);
                boolean data;
                try {
                    TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                    data = tm != null && tm.isDataEnabled();
                    if (tm != null) o.put("operator", tm.getNetworkOperatorName());
                } catch (Throwable t) { data = cellular(); }
                o.put("data", data);
                o.put("cell", cellular());
                o.put("airplane", Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
                Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) {
                    int lv = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    o.put("battery", sc > 0 ? Math.round(lv * 100f / sc) : -1);
                    o.put("charging", bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
                }
                o.put("torch", torchOn);
                o.put("hasTorch", torchId != null);
                o.put("isDefault", isDefaultHome());
                o.put("wall", new JSONObject(wallInfo()));
                o.put("shortcutHost", la.hasShortcutHostPermission());
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                o.put("ringer", am == null ? 2 : am.getRingerMode());
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface public void toggle(String what) {
            try {
                switch (what) {
                    case "wifi": {
                        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        if (Build.VERSION.SDK_INT < 29 && wm != null) wm.setWifiEnabled(!wm.isWifiEnabled());
                        else go(new Intent(Settings.Panel.ACTION_WIFI));
                        break;
                    }
                    case "bt": ui.post(MainActivity.this::toggleBt); break;
                    case "data":
                        if (Build.VERSION.SDK_INT >= 29) go(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
                        else go(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS));
                        break;
                    case "torch":
                        if (torchId != null) cam.setTorchMode(torchId, !torchOn);
                        else toast("Pas de lampe sur ce téléphone");
                        break;
                    case "ringer": {
                        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        int m = am.getRingerMode();
                        try { am.setRingerMode(m == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL); }
                        catch (SecurityException se) { go(new Intent(Settings.ACTION_SOUND_SETTINGS)); }
                        break;
                    }
                    case "wallsound": PupSonActivity.toggle(MainActivity.this); break;
                }
            } catch (Exception e) { toast("Impossible : " + e.getMessage()); }
            ui.postDelayed(() -> emit("status", ""), 400);
        }

        @JavascriptInterface public void open(String what) {
            switch (what) {
                case "wifi": go(new Intent(Settings.ACTION_WIFI_SETTINGS)); break;
                case "bt": go(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); break;
                case "data": go(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)); break;
                case "battery": go(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)); break;
                case "sound": go(new Intent(Settings.ACTION_SOUND_SETTINGS)); break;
                case "display": go(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); break;
                case "home": go(new Intent(Settings.ACTION_HOME_SETTINGS)); break;
                case "clock": go(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)); break;
                case "notif":
                    if (Build.VERSION.SDK_INT >= 26) go(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    break;
                default: go(new Intent(Settings.ACTION_SETTINGS));
            }
        }

        @JavascriptInterface public void appInfo(String pkg) {
            go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
        }

        @JavascriptInterface public void uninstall(String pkg) {
            go(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        }

        @JavascriptInterface public void openUrl(String url) { go(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }

        /** Ouvre une adresse ou une recherche dans PuppyInternet. */
        @JavascriptInterface public void browse(String url, String q) {
            Intent i = new Intent(MainActivity.this, BrowserActivity.class);
            if (url != null && !url.isEmpty()) { i.setAction(Intent.ACTION_VIEW); i.setData(Uri.parse(url)); }
            else if (q != null && !q.isEmpty()) i.putExtra("q", q);
            go(i);
        }

        @JavascriptInterface public void dial(String num) { go(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num == null ? "" : num)))); }

        @JavascriptInterface public String get(String k) {
            return getSharedPreferences("pup_store", MODE_PRIVATE).getString(k, null);
        }

        @JavascriptInterface public void set(String k, String v) {
            SharedPreferences.Editor e = getSharedPreferences("pup_store", MODE_PRIVATE).edit();
            if (v == null) e.remove(k); else e.putString(k, v);
            e.apply();
        }

        @JavascriptInterface public void pickWall(String kind) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("video".equals(kind) ? "video/*" : "image/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try { startActivityForResult(i, "video".equals(kind) ? REQ_WALL_VIDEO : REQ_WALL_IMAGE); }
                catch (Exception e) { toast("Aucun sélecteur de fichiers trouvé"); }
            });
        }

        @JavascriptInterface public void setWallType(String type) {
            SharedPreferences p = wallPrefs();
            if ("video".equals(type) && p.getString("wall_uri", null) == null) { pickWall("video"); return; }
            if ("image".equals(type) && p.getString("img_uri", null) == null) { pickWall("image"); return; }
            p.edit().putString("wall_type", type).putLong("wall_ver", System.currentTimeMillis()).commit();
            applyWallMode();
            emit("wall", wallInfo());
        }

        @JavascriptInterface public String wallInfo() { return MainActivity.this.wallInfo(); }

        @JavascriptInterface public void setWallMuted(boolean muted) {
            wallPrefs().edit().putBoolean("wall_muted", muted).commit();
            PupSonActivity.apply(MainActivity.this);
            emit("status", "");
        }

        @JavascriptInterface public void setWallVolume(float v) {
            wallPrefs().edit().putFloat("wall_vol", Math.max(0f, Math.min(1f, v))).commit();
            if (PupWallService.running != null) ui.post(() -> { if (PupWallService.running != null) PupWallService.running.applyState(); });
        }

        @JavascriptInterface public boolean isDefault() { return isDefaultHome(); }

        @JavascriptInterface public void askDefault() {
            ui.post(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        RoleManager rm = getSystemService(RoleManager.class);
                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME_ROLE);
                            return;
                        }
                    }
                } catch (Exception ignored) { }
                go(new Intent(Settings.ACTION_HOME_SETTINGS));
            });
        }

        @JavascriptInterface public void haptic(String kind) {
            ui.post(() -> web.performHapticFeedback("long".equals(kind) ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.KEYBOARD_TAP));
        }

        @JavascriptInterface public void expand() {
            ui.post(() -> {
                try {
                    Object sb = getSystemService("statusbar");
                    Class<?> c = Class.forName("android.app.StatusBarManager");
                    Method m = c.getMethod("expandNotificationsPanel");
                    m.invoke(sb);
                } catch (Throwable ignored) { }
            });
        }

        @JavascriptInterface public void toast(String s) { MainActivity.this.toast(s); }
    }
}

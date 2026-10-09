package fr.piika.puppyphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;

/** « La sieste du chiot » : horloge-batterie néon (écran de veille de charge + horloge de nuit). */
final class Sieste {
    static final String HOST = "pupsieste.local";
    final Context ctx;
    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    String lastBattery = "{}";
    final BroadcastReceiver batt = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { push(i); } };
    final Runnable tick = new Runnable() { @Override public void run() { Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); if (i != null) push(i); ui.postDelayed(this, 30000); } };

    final String page;
    Sieste(Context c) { this(c, "/sieste.html"); }
    Sieste(Context c, String page) { ctx = c; this.page = page; }

    /** Tamise vraiment l'écran (OLED : le noir reste noir, les néons restent lisibles). */
    static void dim(android.view.Window w, int lum) {
        if (w == null) return;
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.screenBrightness = lum >= 2 ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : lum == 1 ? 0.12f : 0.01f;
        w.setAttributes(lp);
    }
    /** 0 = très sombre (défaut), 1 = tamisé, 2 = lumineux. */
    static int lum(Context c) {
        android.content.SharedPreferences p = Pelage.sp(c);
        return p.contains("siesteLum") ? p.getInt("siesteLum", 0) : p.getBoolean("siesteBright", false) ? 2 : 0;
    }
    /** Secondes avant que la sieste s'arrête et laisse l'écran s'éteindre (0 = jamais). */
    static int duree(Context c) { return Pelage.sp(c).getInt("siesteDuree", 60); }

    View create() {
        web = new WebView(ctx);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override public void onPageFinished(WebView v, String u) { emit("battery", lastBattery); }
        });
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public String battery() { return lastBattery; }
            @JavascriptInterface public String notifs() {
                if (!Pelage.sp(ctx).getBoolean("aodNotif", true) || PupNotifs.I == null) return "[]";
                return PupNotifs.summary(ctx, Pelage.sp(ctx).getBoolean("aodNotifTxt", false));
            }
            @JavascriptInterface public String prefs() {
                try { return new JSONObject().put("pelage", Pelage.id(ctx)).put("acc", Pelage.cur(ctx)[2]).put("acc2", Pelage.cur(ctx)[3]).put("h24", true).put("lum", lum(ctx)).put("style", Pelage.sp(ctx).getString("veilleStyle", "verre")).put("vlum", Pelage.sp(ctx).getInt("veilleLum", 0)).put("alarm", Reveil.nextText(ctx)).toString(); } catch (Exception e) { return "{}"; }
            }
        }, "Sieste");
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public String info() {
                JSONObject a = Reveil.find(ctx, ReveilService.ringingId);
                try { return (a == null ? new JSONObject() : new JSONObject(a.toString())).put("ringing", ReveilService.ringingId != -1).toString(); } catch (Exception e) { return "{}"; }
            }
            @JavascriptInterface public void stop() { ctx.startService(new Intent(ctx, ReveilService.class).setAction(ReveilService.ACT_STOP)); }
            @JavascriptInterface public void snooze() { ctx.startService(new Intent(ctx, ReveilService.class).setAction(ReveilService.ACT_SNOOZE)); }
            @JavascriptInterface public void proof() { ui.post(PupVeille::proofHide); }
        }, "Ring");
        web.loadUrl("https://" + HOST + page);
        return web;
    }

    final Runnable notifL = () -> emit("notifs", "");
    void start() {
        PupNotifs.listeners.add(notifL);
        Intent i = ctx.registerReceiver(batt, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i != null) push(i);
        ui.postDelayed(tick, 30000);
    }
    void stop() {
        PupNotifs.listeners.remove(notifL);
        try { ctx.unregisterReceiver(batt); } catch (Exception ignored) { }
        ui.removeCallbacks(tick);
        if (web != null) { web.destroy(); web = null; }
    }

    void push(Intent i) {
        try {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1), plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            long remain = -1;
            if (Build.VERSION.SDK_INT >= 28) { try { remain = ctx.getSystemService(BatteryManager.class).computeChargeTimeRemaining(); } catch (Exception ignored) { } }
            JSONObject o = new JSONObject().put("pct", scale > 0 ? Math.round(level * 100f / scale) : -1)
                    .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                    .put("full", status == BatteryManager.BATTERY_STATUS_FULL)
                    .put("plug", plugged == BatteryManager.BATTERY_PLUGGED_AC ? "secteur" : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB" : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "sans fil" : "")
                    .put("temp", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f)
                    .put("remain", remain);
            lastBattery = o.toString();
            emit("battery", lastBattery);
        } catch (Exception ignored) { }
    }
    void load(String pg) { if (web != null) web.loadUrl("https://" + HOST + pg); }

    void emit(String ev, String data) {
        final String d = data == null ? "" : data;
        ui.post(() -> { if (web != null) web.evaluateJavascript("window.SiesteUI&&SiesteUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(d) + ")", null); }); }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        try {
            if (p.equals("/nicon")) {
                byte[] png = PupNotifs.appIcon(ctx, u.getQueryParameter("pkg"));
                if (png == null) throw new Exception("pas d'icône");
                return new WebResourceResponse("image/png", null, 200, "OK", new HashMap<>(), new ByteArrayInputStream(png));
            }
            String path = p.equals("/") ? page : p;
            InputStream in = Pelage.open(ctx, path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", new HashMap<>(), in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }
    }
}

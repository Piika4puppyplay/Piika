package fr.piika.puppyphone;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PuppyInternet : navigateur à onglets, bloqueur de pub, aucune appli ne s'ouvre de force. */
public class BrowserActivity extends Activity {
    static final String UI_HOST = "pupnet.local";
    static final int REQ_FILE = 21, REQ_EXPORT = 22, REQ_IMPORT = 23, REQ_ROLE = 24;
    static final int BAR_DP = 78;
    static final int MAX_LIVE = 5;

    static volatile Set<String> adHosts;
    static final String[] BUILTIN_ADS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "google-analytics.com",
            "googletagservices.com", "googletagmanager.com", "pagead2.googlesyndication.com", "adnxs.com", "adsrvr.org",
            "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "amazon-adsystem.com", "scorecardresearch.com",
            "moatads.com", "pubmatic.com", "rubiconproject.com", "openx.net", "smartadserver.com", "teads.tv", "adform.net",
            "casalemedia.com", "yieldmo.com", "media.net", "quantserve.com", "hotjar.com", "popads.net", "popcash.net",
            "propellerads.com", "exoclick.com", "juicyads.com", "trafficjunky.net", "adsterra.com", "zedo.com", "mgid.com",
            "revcontent.com", "sharethrough.com", "33across.com", "indexww.com", "bidswitch.net", "3lift.com", "onesignal.com",
            "app-measurement.com", "facebook.net", "ads.yahoo.com", "advertising.com", "adcolony.com", "unityads.unity3d.com",
            "applovin.com", "inmobi.com", "vungle.com", "chartboost.com", "admob.com", "tapad.com", "branch.io", "adjust.com"
    };
    static final String COSMETIC = "(function(){if(document.getElementById('pupnet-ab'))return;var s=document.createElement('style');s.id='pupnet-ab';"
            + "s.textContent='.adsbygoogle,ins.adsbygoogle,[id^=\"google_ads\"],[id^=\"div-gpt-ad\"],[data-ad-slot],[data-ad-unit],[data-google-query-id],"
            + "[aria-label=\"Advertisement\"],[aria-label=\"Publicité\"],.advertisement,.ad-banner,.ad-slot,.ad-container,.ad-wrapper,.adsbox,.adBanner,"
            + ".taboola,[id^=\"taboola\"],.trc_related_container,.OUTBRAIN,[id^=\"outbrain\"],.ob-widget,iframe[src*=\"doubleclick\"],iframe[src*=\"googlesyndication\"],"
            + "iframe[id^=\"google_ads\"],.sponsored-content,.dfp-ad,.gpt-ad,[class*=\"AdSlot\"],[class*=\"adslot\"]{display:none!important;visibility:hidden!important}';"
            + "(document.head||document.documentElement).appendChild(s);})();";
    static final Map<String, String> ENGINES = new HashMap<>();
    static {
        ENGINES.put("ddg", "https://duckduckgo.com/?q=%s");
        ENGINES.put("google", "https://www.google.com/search?q=%s");
        ENGINES.put("qwant", "https://www.qwant.com/?q=%s");
        ENGINES.put("ecosia", "https://www.ecosia.org/search?q=%s");
        ENGINES.put("brave", "https://search.brave.com/search?q=%s");
        ENGINES.put("startpage", "https://www.startpage.com/do/search?query=%s");
    }

    class Tab {
        int id;
        String url = "", title = "";
        WebView wv;
        Bundle state;
        int progress = 100, blocked;
        boolean loading;
        byte[] thumb, icon;
        long used, thumbAt;
        Tab opener;
    }

    final Handler ui = new Handler(Looper.getMainLooper());
    final List<Tab> tabs = new ArrayList<>();
    Tab cur;
    int idSeq;
    FrameLayout root, host, fsBox;
    WebView uiv;
    float dp = 1f;
    int insT, insB;
    boolean expanded, uiReady;
    SharedPreferences sp;
    int blockedTotal;
    volatile String stateJson = "{}";
    ValueCallback<Uri[]> fileCb;
    String pendingExport;
    View customView;
    WebChromeClient.CustomViewCallback customCb;
    String defaultUA = "";
    boolean emitQueued, saveQueued;

    // ------------------------------------------------------------------ liste de pubs
    static void loadAdHosts(Context c) {
        if (adHosts != null) return;
        new Thread(() -> {
            Set<String> s = new HashSet<>(160000);
            Collections.addAll(s, BUILTIN_ADS);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("adblock/hosts.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    int sp1 = line.indexOf(' ');
                    if (sp1 < 0) sp1 = line.indexOf('\t');
                    if (sp1 < 0) continue;
                    String ip = line.substring(0, sp1);
                    if (!ip.equals("0.0.0.0") && !ip.equals("127.0.0.1")) continue;
                    String d = line.substring(sp1 + 1).trim();
                    int h = d.indexOf('#');
                    if (h >= 0) d = d.substring(0, h).trim();
                    int sp2 = d.indexOf(' ');
                    if (sp2 > 0) d = d.substring(0, sp2);
                    if (d.indexOf('.') > 0 && !d.equals("0.0.0.0") && !d.startsWith("localhost")) s.add(d.toLowerCase(Locale.ROOT));
                }
            } catch (Exception ignored) { }
            adHosts = s;
        }, "pupnet-adblock").start();
    }

    static boolean isAd(String host) {
        Set<String> s = adHosts;
        if (host == null || s == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        while (true) {
            if (s.contains(host)) return true;
            int i = host.indexOf('.');
            if (i < 0 || i == host.lastIndexOf('.')) return false;
            host = host.substring(i + 1);
        }
    }

    // ------------------------------------------------------------------ cycle de vie
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        dp = getResources().getDisplayMetrics().density;
        sp = getSharedPreferences("pupnet", MODE_PRIVATE);
        blockedTotal = sp.getInt("blockedTotal", 0);
        loadAdHosts(getApplicationContext());

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0614);
        host = new FrameLayout(this);
        host.setBackgroundColor(0xFF0B0614);
        root.addView(host, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        uiv = new WebView(this);
        uiv.setBackgroundColor(Color.TRANSPARENT);
        uiv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        uiv.setVerticalScrollBarEnabled(false);
        WebSettings us = uiv.getSettings();
        us.setJavaScriptEnabled(true);
        us.setDomStorageEnabled(true);
        us.setAllowFileAccess(false);
        us.setTextZoom(100);
        us.setOffscreenPreRaster(true);
        uiv.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serveUi(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !UI_HOST.equals(r.getUrl().getHost()); }
            @Override public void onPageFinished(WebView v, String u) { uiReady = true; pushInsets(); emitState(); }
        });
        uiv.addJavascriptInterface(new UiBridge(), "Net");
        root.addView(uiv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (BAR_DP * dp), Gravity.BOTTOM));

        fsBox = new FrameLayout(this);
        fsBox.setBackgroundColor(Color.BLACK);
        fsBox.setVisibility(View.GONE);
        root.addView(fsBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insT = ins.getSystemWindowInsetTop();
            insB = ins.getSystemWindowInsetBottom();
            relayout();
            return ins.consumeSystemWindowInsets();
        });

        restoreTabs();
        if (!handleIntent(getIntent()) && tabs.isEmpty()) newTab("", true, null);
        if (cur == null && !tabs.isEmpty()) select(tabs.get(Math.min(sp.getInt("active", 0), tabs.size() - 1)));
        uiv.loadUrl("https://" + UI_HOST + "/net.html");
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    boolean handleIntent(Intent i) {
        if (i == null) return false;
        String q = null, url = null;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) url = i.getDataString();
        if (Intent.ACTION_WEB_SEARCH.equals(i.getAction())) q = i.getStringExtra(android.app.SearchManager.QUERY);
        if (i.hasExtra("q")) q = i.getStringExtra("q");
        if (q != null && !q.trim().isEmpty()) url = toUrl(q);
        if (url == null) return false;
        if (cur != null && cur.url.isEmpty()) load(cur, url);
        else newTab(url, true, null);
        setExpanded(false);
        emitUi("collapse", "");
        return true;
    }

    @Override protected void onStop() { saveTabsNow(); sp.edit().putInt("blockedTotal", blockedTotal).apply(); super.onStop(); }

    @Override protected void onDestroy() {
        for (Tab t : tabs) if (t.wv != null) t.wv.destroy();
        if (uiv != null) uiv.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { hideCustom(); return; }
        uiv.evaluateJavascript("(window.NetUI&&NetUI.back())?'y':'n'", v -> {
            if ("\"y\"".equals(v)) return;
            if (cur != null && cur.wv != null && cur.wv.canGoBack()) { cur.wv.goBack(); return; }
            if (cur != null && cur.opener != null && tabs.contains(cur.opener)) { closeTab(cur); return; }
            moveTaskToBack(true);
        });
    }

    // ------------------------------------------------------------------ mise en page
    void relayout() {
        int bar = (int) (BAR_DP * dp) + insB;
        FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) host.getLayoutParams();
        hp.topMargin = insT;
        hp.bottomMargin = bar;
        host.setLayoutParams(hp);
        FrameLayout.LayoutParams up = (FrameLayout.LayoutParams) uiv.getLayoutParams();
        up.height = expanded ? ViewGroup.LayoutParams.MATCH_PARENT : bar;
        up.gravity = Gravity.BOTTOM;
        uiv.setLayoutParams(up);
        pushInsets();
    }

    void setExpanded(boolean e) {
        if (expanded == e) return;
        expanded = e;
        relayout();
    }

    void pushInsets() {
        if (uiv == null) return;
        final float t = insT / dp, b = insB / dp;
        ui.post(() -> uiv.evaluateJavascript("window.NetUI&&NetUI.insets(" + t + "," + b + ")", null));
    }

    // ------------------------------------------------------------------ onglets
    Tab newTab(String url, boolean activate, Tab opener) {
        Tab t = new Tab();
        t.id = ++idSeq;
        t.url = url == null ? "" : url;
        t.opener = opener;
        int at = cur == null ? tabs.size() : tabs.indexOf(cur) + 1;
        tabs.add(Math.max(0, Math.min(at, tabs.size())), t);
        if (activate) select(t);
        saveTabs();
        emitState();
        return t;
    }

    void ensureWv(Tab t) {
        if (t.wv != null) return;
        t.wv = makeWv(t);
        host.addView(t.wv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (t.state != null) { t.wv.restoreState(t.state); t.state = null; }
        else if (!t.url.isEmpty()) t.wv.loadUrl(t.url);
        trimLive();
    }

    void trimLive() {
        List<Tab> live = new ArrayList<>();
        for (Tab t : tabs) if (t.wv != null && t != cur) live.add(t);
        if (live.size() < MAX_LIVE) return;
        Collections.sort(live, (a, b) -> Long.compare(a.used, b.used));
        for (int i = 0; i <= live.size() - MAX_LIVE; i++) {
            Tab t = live.get(i);
            t.state = new Bundle();
            t.wv.saveState(t.state);
            host.removeView(t.wv);
            t.wv.destroy();
            t.wv = null;
        }
    }

    void select(Tab t) {
        if (cur != null && cur != t && cur.wv != null) { captureThumb(cur); cur.wv.setVisibility(View.GONE); }
        cur = t;
        t.used = System.currentTimeMillis();
        ensureWv(t);
        t.wv.setVisibility(t.url.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        t.wv.requestFocus();
        saveTabs();
        emitState();
    }

    void closeTab(Tab t) {
        int i = tabs.indexOf(t);
        if (i < 0) return;
        tabs.remove(i);
        if (t.wv != null) { host.removeView(t.wv); t.wv.destroy(); t.wv = null; }
        if (t == cur) {
            cur = null;
            if (tabs.isEmpty()) newTab("", true, null);
            else if (t.opener != null && tabs.contains(t.opener)) select(t.opener);
            else select(tabs.get(Math.max(0, i - 1)));
        }
        saveTabs();
        emitState();
    }

    void load(Tab t, String url) {
        t.url = url;
        ensureWv(t);
        t.wv.setVisibility(View.VISIBLE);
        t.wv.loadUrl(url);
        emitState();
    }

    void captureThumb(Tab t) {
        try {
            if (t.wv == null || t.wv.getWidth() <= 0 || t.url.isEmpty()) return;
            int w = t.wv.getWidth() / 3, h = t.wv.getHeight() / 3;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(b);
            c.scale(1 / 3f, 1 / 3f);
            c.translate(-t.wv.getScrollX(), -t.wv.getScrollY());
            t.wv.draw(c);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 72, o);
            b.recycle();
            t.thumb = o.toByteArray();
            t.thumbAt = System.currentTimeMillis();
        } catch (Throwable ignored) { }
    }

    Tab byId(int id) { for (Tab t : tabs) if (t.id == id) return t; return null; }
    Tab byWv(WebView w) { for (Tab t : tabs) if (t.wv == w) return t; return null; }

    void saveTabs() {
        if (saveQueued) return;
        saveQueued = true;
        ui.postDelayed(() -> { saveQueued = false; saveTabsNow(); }, 800);
    }

    void saveTabsNow() {
        try {
            JSONArray a = new JSONArray();
            for (Tab t : tabs) {
                if (t.url.isEmpty() && tabs.size() > 1) continue;
                JSONObject o = new JSONObject();
                o.put("url", t.url);
                o.put("title", t.title);
                a.put(o);
            }
            sp.edit().putString("tabs", a.toString()).putInt("active", Math.max(0, tabs.indexOf(cur))).apply();
        } catch (Exception ignored) { }
    }

    void restoreTabs() {
        try {
            JSONArray a = new JSONArray(sp.getString("tabs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tab t = new Tab();
                t.id = ++idSeq;
                t.url = o.optString("url", "");
                t.title = o.optString("title", "");
                tabs.add(t);
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ WebView d'un onglet
    String mobileUA() { return defaultUA.replace("; wv", "").replace("Version/4.0 ", ""); }
    String desktopUA() {
        Matcher m = Pattern.compile("Chrome/([0-9.]+)").matcher(defaultUA);
        String v = m.find() ? m.group(1) : "124.0.0.0";
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + v + " Safari/537.36";
    }

    void applySettings(WebView wv) {
        WebSettings s = wv.getSettings();
        if (defaultUA.isEmpty()) defaultUA = s.getUserAgentString();
        s.setJavaScriptEnabled(sp.getBoolean("js", true));
        s.setUserAgentString(sp.getBoolean("desktop", false) ? desktopUA() : mobileUA());
        s.setTextZoom(sp.getInt("zoom", 100));
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !sp.getBoolean("block3p", true));
    }

    WebView makeWv(Tab t) {
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setOffscreenPreRaster(false);
        CookieManager.getInstance().setAcceptCookie(true);
        applySettings(wv);
        wv.setBackgroundColor(Color.WHITE);
        wv.setWebViewClient(new TabClient());
        wv.setWebChromeClient(new TabChrome());
        wv.setDownloadListener((url, ua, cd, mime, len) -> download(url, ua, cd, mime));
        return wv;
    }

    class TabClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            if (!r.isForMainFrame() && sp.getBoolean("adblock", true) && isAd(r.getUrl().getHost())) {
                ui.post(() -> {
                    Tab tt = byWv(v);
                    if (tt != null) tt.blocked++;
                    blockedTotal++;
                    emitState();
                });
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (sc.equals("http") || sc.equals("https") || sc.equals("about") || sc.equals("data") || sc.equals("blob") || sc.equals("javascript")) return false;
            String url = u.toString();
            if (sc.equals("intent")) {
                try {
                    Intent it = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    String fb = it.getStringExtra("browser_fallback_url");
                    if (fb != null && (fb.startsWith("https://") || fb.startsWith("http://"))) { v.loadUrl(fb); return true; }
                    String pkg = it.getPackage();
                    blockedApp(url, pkg != null ? pkg : sc);
                } catch (Exception e) { blockedApp(url, sc); }
                return true;
            }
            blockedApp(url, sc);
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap fav) {
            Tab t = byWv(v);
            if (t == null) return;
            t.url = url;
            t.loading = true;
            t.blocked = 0;
            t.icon = null;
            v.setVisibility(t == cur ? View.VISIBLE : View.GONE);
            emitState();
        }

        @Override
        public void onPageCommitVisible(WebView v, String url) {
            if (sp.getBoolean("adblock", true)) v.evaluateJavascript(COSMETIC, null);
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            Tab t = byWv(v);
            if (t == null) return;
            t.loading = false;
            t.progress = 100;
            t.url = url;
            if (v.getTitle() != null && !v.getTitle().isEmpty()) t.title = v.getTitle();
            if (sp.getBoolean("adblock", true)) v.evaluateJavascript(COSMETIC, null);
            addHistory(url, t.title);
            saveTabs();
            emitState();
        }

        @Override
        public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
            Tab t = byWv(v);
            if (t != null) { t.url = url; emitState(); }
        }

        @Override
        public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
            h.cancel();
            emitUi("notice", "{\"kind\":\"ssl\",\"text\":\"Connexion non sécurisée bloquée\"}");
        }
    }

    class TabChrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView v, int p) { Tab t = byWv(v); if (t != null) { t.progress = p; emitState(); } }
        @Override public void onReceivedTitle(WebView v, String title) { Tab t = byWv(v); if (t != null) { t.title = title; emitState(); } }
        @Override public void onReceivedIcon(WebView v, Bitmap icon) {
            Tab t = byWv(v);
            if (t == null || icon == null) return;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            icon.compress(Bitmap.CompressFormat.PNG, 100, o);
            t.icon = o.toByteArray();
            emitState();
        }

        @Override
        public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message msg) {
            Tab opener = byWv(v);
            if (!gesture) {
                if (opener != null) opener.blocked++;
                blockedTotal++;
                emitUi("notice", "{\"kind\":\"popup\",\"text\":\"Fenêtre pop-up bloquée\"}");
                emitState();
                return false;
            }
            Tab nt = newTab("", true, opener);
            ensureWv(nt);
            nt.wv.setVisibility(View.VISIBLE);
            WebView.WebViewTransport tr = (WebView.WebViewTransport) msg.obj;
            tr.setWebView(nt.wv);
            msg.sendToTarget();
            setExpanded(false);
            emitUi("collapse", "");
            return true;
        }

        @Override public void onCloseWindow(WebView w) { Tab t = byWv(w); if (t != null) closeTab(t); }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            try { startActivityForResult(params.createIntent(), REQ_FILE); }
            catch (Exception e) { fileCb = null; return false; }
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback cb) {
            if (customView != null) { cb.onCustomViewHidden(); return; }
            customView = view;
            customCb = cb;
            fsBox.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fsBox.setVisibility(View.VISIBLE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        @Override public void onHideCustomView() { hideCustom(); }
        @Override public void onPermissionRequest(PermissionRequest r) { r.deny(); }
        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) { cb.invoke(origin, false, false); }
    }

    void hideCustom() {
        if (customView == null) return;
        fsBox.removeView(customView);
        fsBox.setVisibility(View.GONE);
        customView = null;
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    void blockedApp(String url, String what) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "app");
            o.put("url", url);
            o.put("what", what);
            emitUi("notice", o.toString());
        } catch (Exception ignored) { }
    }

    void download(String url, String ua, String cd, String mime) {
        try {
            if (url.startsWith("blob:") || url.startsWith("data:")) { toast("Ce type de téléchargement n'est pas pris en charge"); return; }
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String name = URLUtil.guessFileName(url, cd, mime);
            r.setMimeType(mime);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) r.addRequestHeader("Cookie", ck);
            r.addRequestHeader("User-Agent", ua);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            try { r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name); }
            catch (Exception e) { r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name); }
            dm.enqueue(r);
            toast("⬇️ Téléchargement : " + name);
        } catch (Exception e) { toast("Téléchargement impossible"); }
    }

    // ------------------------------------------------------------------ historique
    void addHistory(String url, String title) {
        if (url == null || !url.startsWith("http")) return;
        try {
            JSONArray a = new JSONArray(sp.getString("history", "[]"));
            JSONArray n = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("url", url);
            o.put("title", title == null ? "" : title);
            o.put("t", System.currentTimeMillis());
            n.put(o);
            for (int i = 0; i < a.length() && n.length() < 400; i++) {
                JSONObject x = a.getJSONObject(i);
                if (!url.equals(x.optString("url"))) n.put(x);
            }
            sp.edit().putString("history", n.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ état -> interface
    void emitState() {
        if (emitQueued) return;
        emitQueued = true;
        ui.postDelayed(() -> {
            emitQueued = false;
            try {
                JSONObject o = new JSONObject();
                JSONArray a = new JSONArray();
                for (Tab t : tabs) {
                    JSONObject x = new JSONObject();
                    x.put("id", t.id);
                    x.put("title", t.title);
                    x.put("url", t.url);
                    x.put("active", t == cur);
                    x.put("thumb", t.thumbAt);
                    x.put("icon", t.icon != null);
                    a.put(x);
                }
                o.put("tabs", a);
                if (cur != null) {
                    JSONObject c = new JSONObject();
                    c.put("id", cur.id);
                    c.put("url", cur.url);
                    c.put("title", cur.title);
                    c.put("loading", cur.loading);
                    c.put("progress", cur.progress);
                    c.put("blocked", cur.blocked);
                    c.put("icon", cur.icon != null);
                    c.put("blank", cur.url.isEmpty());
                    c.put("canBack", cur.wv != null && cur.wv.canGoBack());
                    c.put("canFwd", cur.wv != null && cur.wv.canGoForward());
                    c.put("secure", cur.url.startsWith("https://"));
                    o.put("active", c);
                }
                o.put("blockedTotal", blockedTotal);
                o.put("hosts", adHosts == null ? 0 : adHosts.size());
                o.put("adblock", sp.getBoolean("adblock", true));
                o.put("desktop", sp.getBoolean("desktop", false));
                o.put("js", sp.getBoolean("js", true));
                o.put("block3p", sp.getBoolean("block3p", true));
                o.put("zoom", sp.getInt("zoom", 100));
                o.put("engine", sp.getString("engine", "ddg"));
                o.put("isDefault", isDefaultBrowser());
                stateJson = o.toString();
                emitUi("state", stateJson);
            } catch (Exception ignored) { }
        }, 90);
    }

    void emitUi(String ev, String data) {
        ui.post(() -> {
            if (uiv == null) return;
            uiv.evaluateJavascript("window.NetUI&&NetUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null);
        });
    }

    boolean isDefaultBrowser() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) return rm.isRoleHeld(RoleManager.ROLE_BROWSER);
            }
            ResolveInfo r = getPackageManager().resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }

    String toUrl(String text) {
        String t = text.trim();
        if (t.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) return t;
        if (!t.contains(" ") && t.matches("(?i)^[a-z0-9-]+(\\.[a-z0-9-]+)+(:[0-9]+)?(/.*)?$")) return "https://" + t;
        String tpl = ENGINES.get(sp.getString("engine", "ddg"));
        if (tpl == null) tpl = ENGINES.get("ddg");
        return tpl.replace("%s", Uri.encode(t));
    }

    void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    // ------------------------------------------------------------------ fichiers pour l'interface
    WebResourceResponse serveUi(Uri u) {
        if (u == null || !UI_HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        try {
            if (p.equals("/thumb") || p.equals("/fav")) {
                int id = Integer.parseInt(u.getQueryParameter("id"));
                final byte[][] out = new byte[1][];
                final Object lock = new Object();
                ui.post(() -> { Tab t = byId(id); synchronized (lock) { out[0] = t == null ? null : (p.equals("/thumb") ? t.thumb : t.icon); lock.notifyAll(); } });
                synchronized (lock) { lock.wait(400); }
                if (out[0] == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", h, new ByteArrayInputStream(new byte[0]));
                return new WebResourceResponse(p.equals("/thumb") ? "image/jpeg" : "image/png", null, 200, "OK", h, new ByteArrayInputStream(out[0]));
            }
            String path = p.equals("/") ? "/net.html" : p;
            InputStream in = getAssets().open("www" + path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    // ------------------------------------------------------------------ retours d'activités
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (fileCb != null) fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
            return;
        }
        if (req == REQ_EXPORT) {
            if (res == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
                try (OutputStream o = getContentResolver().openOutputStream(data.getData())) {
                    o.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                    toast("🐾 Sauvegarde exportée");
                } catch (Exception e) { toast("Export impossible"); }
            }
            pendingExport = null;
            return;
        }
        if (req == REQ_IMPORT) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    emitUi("import", o.toString("UTF-8"));
                } catch (Exception e) { toast("Fichier illisible"); }
            }
            return;
        }
        if (req == REQ_ROLE) { emitState(); return; }
        super.onActivityResult(req, res, data);
    }

    // ------------------------------------------------------------------ pont avec l'interface
    class UiBridge {
        @JavascriptInterface public String state() { return stateJson; }

        @JavascriptInterface public void go(String text) {
            ui.post(() -> {
                if (text == null || text.trim().isEmpty()) return;
                if (cur == null) BrowserActivity.this.newTab("", true, null);
                load(cur, toUrl(text));
                setExpanded(false);
            });
        }

        @JavascriptInterface public void back() { ui.post(() -> { if (cur != null && cur.wv != null && cur.wv.canGoBack()) cur.wv.goBack(); }); }
        @JavascriptInterface public void forward() { ui.post(() -> { if (cur != null && cur.wv != null && cur.wv.canGoForward()) cur.wv.goForward(); }); }
        @JavascriptInterface public void reload() { ui.post(() -> { if (cur != null && cur.wv != null) { if (cur.loading) cur.wv.stopLoading(); else cur.wv.reload(); } }); }

        @JavascriptInterface public void newTab(String url) {
            ui.post(() -> {
                BrowserActivity.this.newTab("", true, null);
                if (url != null && !url.isEmpty()) load(cur, toUrl(url));
            });
        }

        @JavascriptInterface public void select(int id) { ui.post(() -> { Tab t = byId(id); if (t != null) BrowserActivity.this.select(t); }); }
        @JavascriptInterface public void close(int id) { ui.post(() -> { Tab t = byId(id); if (t != null) closeTab(t); }); }

        @JavascriptInterface public void closeAll() {
            ui.post(() -> {
                for (Tab t : new ArrayList<>(tabs)) { if (t.wv != null) { host.removeView(t.wv); t.wv.destroy(); } }
                tabs.clear();
                cur = null;
                BrowserActivity.this.newTab("", true, null);
            });
        }

        @JavascriptInterface public void capture() {
            final Object lock = new Object();
            ui.post(() -> { if (cur != null) captureThumb(cur); emitState(); synchronized (lock) { lock.notifyAll(); } });
            synchronized (lock) { try { lock.wait(500); } catch (InterruptedException ignored) { } }
        }

        @JavascriptInterface public void expand(boolean e) { ui.post(() -> setExpanded(e)); }

        @JavascriptInterface public String get(String k) { return sp.getString("ui_" + k, null); }
        @JavascriptInterface public void set(String k, String v) { sp.edit().putString("ui_" + k, v).apply(); }
        @JavascriptInterface public String history() { return sp.getString("history", "[]"); }

        @JavascriptInterface public void setSetting(String k, String v) {
            ui.post(() -> {
                SharedPreferences.Editor e = sp.edit();
                switch (k) {
                    case "engine": e.putString("engine", v); break;
                    case "zoom": try { e.putInt("zoom", Integer.parseInt(v)); } catch (Exception ignored) { } break;
                    default: e.putBoolean(k, "true".equals(v));
                }
                e.apply();
                for (Tab t : tabs) if (t.wv != null) applySettings(t.wv);
                if (("desktop".equals(k) || "js".equals(k)) && cur != null && cur.wv != null && !cur.url.isEmpty()) cur.wv.reload();
                emitState();
            });
        }

        @JavascriptInterface public void clear(String what) {
            ui.post(() -> {
                if ("history".equals(what)) { sp.edit().remove("history").apply(); for (Tab t : tabs) if (t.wv != null) t.wv.clearHistory(); }
                if ("cookies".equals(what)) { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData(); }
                if ("cache".equals(what)) { for (Tab t : tabs) if (t.wv != null) t.wv.clearCache(true); }
                toast("🧽 Effacé");
            });
        }

        @JavascriptInterface public void exportBackup(String json) {
            ui.post(() -> {
                pendingExport = json;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, "PuppyInternet-sauvegarde-" + new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date()) + ".json");
                try { startActivityForResult(i, REQ_EXPORT); } catch (Exception e) { toast("Aucun gestionnaire de fichiers"); }
            });
        }

        @JavascriptInterface public void importBackup() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(i, REQ_IMPORT); } catch (Exception e) { toast("Aucun gestionnaire de fichiers"); }
            });
        }

        @JavascriptInterface public void restoreTabs(String json) {
            ui.post(() -> {
                try {
                    JSONArray a = new JSONArray(json);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        String u = o.optString("url", "");
                        if (!u.startsWith("http")) continue;
                        Tab t = new Tab();
                        t.id = ++idSeq;
                        t.url = u;
                        t.title = o.optString("title", "");
                        tabs.add(t);
                    }
                    saveTabs();
                    emitState();
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void share() {
            ui.post(() -> {
                if (cur == null || cur.url.isEmpty()) return;
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, cur.url).putExtra(Intent.EXTRA_SUBJECT, cur.title);
                startActivity(Intent.createChooser(i, "Partager la page"));
            });
        }

        /** Ouverture volontaire (bouton tapé par toi) dans une autre appli. */
        @JavascriptInterface public void openExternal(String url) {
            ui.post(() -> {
                try {
                    Intent i = url.startsWith("intent:") ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null);
                    i.setSelector(null);
                    startActivity(Intent.createChooser(i, "Ouvrir avec"));
                } catch (ActivityNotFoundException e) { toast("Aucune appli pour ouvrir ça"); }
                catch (Exception e) { toast("Impossible d'ouvrir"); }
            });
        }

        @JavascriptInterface public void openDownloads() {
            ui.post(() -> { try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); } catch (Exception e) { toast("Téléchargements introuvables"); } });
        }

        @JavascriptInterface public void pinHome() {
            ui.post(() -> {
                if (cur == null || cur.url.isEmpty()) return;
                try {
                    SharedPreferences p = getSharedPreferences("pup_pins", MODE_PRIVATE);
                    JSONArray arr = new JSONArray(p.getString("pins", "[]"));
                    JSONObject o = new JSONObject();
                    o.put("type", "link");
                    o.put("url", cur.url);
                    o.put("label", cur.title == null || cur.title.isEmpty() ? Uri.parse(cur.url).getHost() : cur.title);
                    arr.put(o);
                    p.edit().putString("pins", arr.toString()).commit();
                    toast("🐾 Épinglé sur l'accueil PuppyPhone");
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void askDefault() {
            ui.post(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        RoleManager rm = getSystemService(RoleManager.class);
                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), REQ_ROLE);
                            return;
                        }
                    }
                    startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
                } catch (Exception e) { toast("Ouvre Paramètres → Applis par défaut → Navigateur"); }
            });
        }

        @JavascriptInterface public void haptic() { ui.post(() -> uiv.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)); }
        @JavascriptInterface public void toast(String s) { BrowserActivity.this.toast(s); }
        @JavascriptInterface public void home() { ui.post(() -> { Intent i = new Intent(BrowserActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }); }
    }
}

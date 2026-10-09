package fr.piika.puppyphone;

import android.app.Activity;
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
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;

/** Base des Pup-apps « page web locale » : hôte intercepté, pelage, marges système, pont JS commun. */
public abstract class PupWebActivity extends Activity {
    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    float dp = 1f;
    int insT, insB;

    abstract String host();
    abstract String page();
    abstract String uiName();
    Object bridge() { return new Common(); }

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
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (host().equals(r.getUrl().getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                return true;
            }
            @Override public void onPageFinished(WebView v, String u) { pushInsets(); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(bridge(), "Pup");
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v, ins) -> { insT = ins.getSystemWindowInsetTop(); insB = ins.getSystemWindowInsetBottom(); pushInsets(); return ins; });
        web.loadUrl("https://" + host() + "/" + page());
    }
    @Override protected void onResume() { super.onResume(); Pelage.watch(this, web); emit("resume", ""); }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window." + uiName() + "&&" + uiName() + ".back&&" + uiName() + ".back()", v -> { if (!"true".equals(v)) finish(); });
        else finish();
    }

    void emit(String ev, String data) { ui.post(() -> { if (web != null) web.evaluateJavascript("window." + uiName() + "&&" + uiName() + ".on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); }); }
    void pushInsets() { final float t = insT / dp, bt = insB / dp; ui.post(() -> { if (web != null) web.evaluateJavascript("(function(){var r=document.documentElement.style;r.setProperty('--st',Math.max(" + t + ",20)+'px');r.setProperty('--sb'," + bt + "+'px')})()", null); }); }

    WebResourceResponse serve(Uri u) {
        if (u == null || !host().equals(u.getHost())) return null;
        String p = u.getPath() == null || u.getPath().equals("/") ? "/" + page() : u.getPath();
        try {
            InputStream in = Pelage.open(this, p);
            String mime = MainActivity.mime(p);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", new HashMap<>(), in);
        } catch (Exception e) { return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", new HashMap<>(), new ByteArrayInputStream(new byte[0])); }
    }

    class Common {
        @JavascriptInterface public void share(String title, String text) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text);
                try { startActivity(Intent.createChooser(i, "Partager 🐾")); } catch (Exception ignored) { }
            });
        }
        @JavascriptInterface public void close() { ui.post(PupWebActivity.this::finish); }
        @JavascriptInterface public void haptic() { ui.post(() -> { if (web != null) web.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); }); }
    }
}

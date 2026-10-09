package fr.piika.puppyphone;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
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

/** Écran qui sonne : par-dessus le verrouillage, gros réveil chromé, « Je me lève » ou « Encore 5 min de sieste ». */
public class ReveilRingActivity extends Activity {
    static final String HOST = "pupreveil.local";
    static ReveilRingActivity I;
    WebView web;

    static void closeAll() { ReveilRingActivity a = I; if (a != null) a.runOnUiThread(a::finish); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        I = this;
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setTextZoom(100); s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
        });
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public String info() {
                int id = getIntent().getIntExtra("id", ReveilService.ringingId);
                JSONObject a = Reveil.find(ReveilRingActivity.this, id);
                try { return (a == null ? new JSONObject() : new JSONObject(a.toString())).put("ringing", ReveilService.ringingId != -1).toString(); } catch (Exception e) { return "{}"; }
            }
            @JavascriptInterface public void stop() { startService(new Intent(ReveilRingActivity.this, ReveilService.class).setAction(ReveilService.ACT_STOP)); runOnUiThread(ReveilRingActivity.this::finish); }
            @JavascriptInterface public void snooze() { startService(new Intent(ReveilRingActivity.this, ReveilService.class).setAction(ReveilService.ACT_SNOOZE)); runOnUiThread(ReveilRingActivity.this::finish); }
        }, "Ring");
        setContentView(web);
        web.loadUrl("https://" + HOST + "/ring.html");
    }
    @Override public void onBackPressed() { /* on ne ferme pas un réveil par erreur */ }
    @Override protected void onDestroy() { if (I == this) I = null; if (web != null) web.destroy(); super.onDestroy(); }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/ring.html" : u.getPath();
        try {
            InputStream in = Pelage.open(this, p);
            String mime = MainActivity.mime(p);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", new HashMap<>(), in);
        } catch (Exception e) { return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", new HashMap<>(), new ByteArrayInputStream(new byte[0])); }
    }
}

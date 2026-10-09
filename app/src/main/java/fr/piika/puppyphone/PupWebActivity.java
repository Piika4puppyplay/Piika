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
    /** Appelé quand la page est prête (après onPageFinished) : les sous-classes peuvent injecter du CSS/JS. */
    void onReady() { }
    /** Le lanceur recadre l'app dans la zone utile de l'écran (marges barres système + encoche + clavier).
     *  Les apps vraiment plein écran (caméra, veille…) pourront renvoyer false. */
    boolean frameToSafeArea() { return true; }
    boolean framed;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setBackgroundDrawable(frameBackground()); // la marge de recadrage garde le thème puppyplay
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        // Le système recadre lui-même le contenu dans la zone utile (barres transparentes → on voit le dégradé thémé
        // derrière), ET redimensionne la fenêtre quand le clavier s'ouvre → le contenu et la barre du bas remontent
        // au-dessus du clavier. C'est le comportement standard fiable (comme l'appli Claude).
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(true);
        w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dp = getResources().getDisplayMetrics().density;
        web = new WebView(this);
        web.setBackgroundColor(Pelage.bg(this));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setTextZoom(100); s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (host().equals(r.getUrl().getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                return true;
            }
            @Override public void onPageFinished(WebView v, String u) { pushInsets(); onReady(); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(android.webkit.PermissionRequest r) { ui.post(() -> askWeb(r)); }
            @Override public boolean onShowFileChooser(WebView v, android.webkit.ValueCallback<Uri[]> cb, FileChooserParams fp) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(fp.createIntent(), 78); } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        web.addJavascriptInterface(bridge(), "Pup");
        setContentView(web);
        // Le contenu est déjà recadré par le système (decorFitsSystemWindows) → marges CSS nulles, pas de double marge.
        insT = 0; insB = 0; pushInsets();
        web.loadUrl("https://" + host() + "/" + page());
    }
    @Override protected void onResume() { super.onResume(); Pelage.watch(this, web); emit("resume", ""); }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window." + uiName() + "&&" + uiName() + ".back&&" + uiName() + ".back()", v -> { if (!"true".equals(v)) finish(); });
        else finish();
    }

    /** Fond de la marge de recadrage : dégradé violet néon PuppyPlay, teinté par la couleur d'accent choisie. */
    android.graphics.drawable.Drawable frameBackground() {
        int high = 0xFF1b0d33;
        try { high = blend(android.graphics.Color.parseColor(Pelage.cur(this)[2]), 0xFF160a2a, 0.20f); } catch (Exception ignored) { }
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{high, 0xFF120a22, 0xFF0b0614});
    }
    static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * t + ((b >> 16) & 255) * (1 - t));
        int g = Math.round(((a >> 8) & 255) * t + ((b >> 8) & 255) * (1 - t));
        int bl = Math.round((a & 255) * t + (b & 255) * (1 - t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ---------------- caméra / micro pour getUserMedia, sélecteur de fichiers
    android.webkit.PermissionRequest pendingWeb;
    android.webkit.ValueCallback<Uri[]> fileCb;
    void askWeb(android.webkit.PermissionRequest r) {
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        for (String res : r.getResources()) {
            if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) need.add(android.Manifest.permission.CAMERA);
            if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) need.add(android.Manifest.permission.RECORD_AUDIO);
        }
        java.util.ArrayList<String> miss = new java.util.ArrayList<>();
        for (String n : need) if (checkSelfPermission(n) != android.content.pm.PackageManager.PERMISSION_GRANTED) miss.add(n);
        if (miss.isEmpty()) { r.grant(r.getResources()); return; }
        pendingWeb = r; requestPermissions(miss.toArray(new String[0]), 77);
    }
    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        boolean ok = res.length > 0; for (int g : res) ok &= g == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (code == 77 && pendingWeb != null) { if (ok) pendingWeb.grant(pendingWeb.getResources()); else pendingWeb.deny(); pendingWeb = null; }
        emit("perm", code + ":" + ok);
    }
    @Override protected void onActivityResult(int code, int rc, Intent data) {
        super.onActivityResult(code, rc, data);
        if (code == 78 && fileCb != null) { fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(rc, data)); fileCb = null; }
    }

    void emit(String ev, String data) { ui.post(() -> { if (web != null) web.evaluateJavascript("window." + uiName() + "&&" + uiName() + ".on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); }); }
    void pushInsets() {
        // Le système recadre déjà la WebView dans la zone utile → marges CSS nulles (pas de double marge).
        ui.post(() -> { if (web != null) web.evaluateJavascript("(function(){var r=document.documentElement.style;r.setProperty('--st','0px');r.setProperty('--sb','0px')})()", null); });
    }

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
        @JavascriptInterface public void copy(String text) {
            ui.post(() -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("PuppyPhone", text));
            });
        }
        @JavascriptInterface public void openUrl(String u) { ui.post(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void close() { ui.post(PupWebActivity.this::finish); }
        @JavascriptInterface public void haptic() { ui.post(() -> { if (web != null) web.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); }); }
        /** Horloge internet de confiance de PuppyPhone (ms epoch), ou -1 si pas encore synchronisée. Anti-triche : les apps lisent l'heure de l'OS, pas Internet. */
        @JavascriptInterface public long netNow() { return PupTime.now(PupWebActivity.this); }
        @JavascriptInterface public boolean netTrusted() { return PupTime.now(PupWebActivity.this) > 0; }
    }
}

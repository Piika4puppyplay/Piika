package fr.piika.puppyphone;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
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
import java.util.Map;

/** « Dressage du clavier » : réglages de PupKeyboard. */
public class KbSettingsActivity extends Activity {
    static final String HOST = "pupkbd.local";
    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    float dp = 1f;
    int insT, insB;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        dp = getResources().getDisplayMetrics().density;
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0614);
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
        web.addJavascriptInterface(new Bridge(), "Kbd");
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v, ins) -> { insT = ins.getSystemWindowInsetTop(); insB = ins.getSystemWindowInsetBottom(); pushInsets(); return ins.consumeSystemWindowInsets(); });
        web.loadUrl("https://" + HOST + "/kbd.html");
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) emit("resume"); }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }

    void emit(String ev) { ui.post(() -> { if (web != null) web.evaluateJavascript("window.KbdUI&&KbdUI.on(" + JSONObject.quote(ev) + ")", null); }); }
    void pushInsets() { final float t = insT / dp, bt = insB / dp; ui.post(() -> web.evaluateJavascript("window.KbdUI&&KbdUI.insets(" + t + "," + bt + ")", null)); }
    SharedPreferences sp() { return getSharedPreferences("pupkbd", MODE_MULTI_PROCESS); }
    String imeId() { return new ComponentName(this, PupKeyboard.class).flattenToShortString(); }

    boolean enabled() {
        try {
            for (InputMethodInfo i : getSystemService(InputMethodManager.class).getEnabledInputMethodList()) if (i.getPackageName().equals(getPackageName())) return true;
        } catch (Exception ignored) { }
        return false;
    }
    boolean current() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return s != null && s.startsWith(getPackageName() + "/");
    }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        try {
            String path = p.equals("/") ? "/kbd.html" : p;
            InputStream in = getAssets().open("www" + path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") || mime.contains("svg") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    class Bridge {
        @JavascriptInterface public String state() {
            try {
                int learned = 0;
                for (String l : new String[]{"fr", "en"}) learned += new JSONObject(getSharedPreferences("pupkbd_words_" + l, MODE_MULTI_PROCESS).getString("w", "{}")).length();
                return new JSONObject().put("enabled", enabled()).put("current", current()).put("learned", learned)
                        .put("clips", new org.json.JSONArray(sp().getString("clips", "[]")).length()).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public String get() {
            SharedPreferences p = sp();
            try {
                return new JSONObject().put("theme", p.getString("theme", "rose")).put("size", p.getInt("size", 2)).put("layout", p.getString("layout", "azerty")).put("lang", p.getString("lang", "fr"))
                        .put("numRow", p.getBoolean("numRow", false)).put("hints", p.getBoolean("hints", true)).put("bubble", p.getBoolean("bubble", true)).put("vibe", p.getInt("vibe", 2))
                        .put("sound", p.getBoolean("sound", false)).put("soundVol", p.getInt("soundVol", 50)).put("autoCap", p.getBoolean("autoCap", true)).put("dblSpace", p.getBoolean("dblSpace", true))
                        .put("suggest", p.getBoolean("suggest", true)).put("autocorrect", p.getBoolean("autocorrect", false)).put("learn", p.getBoolean("learn", true)).put("clipHist", p.getBoolean("clipHist", true)).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void set(String json) {
            try {
                JSONObject o = new JSONObject(json);
                SharedPreferences.Editor e = sp().edit();
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next(); Object v = o.get(k);
                    if (v instanceof Boolean) e.putBoolean(k, (Boolean) v); else if (v instanceof Integer) e.putInt(k, (Integer) v); else e.putString(k, String.valueOf(v));
                }
                e.commit();
            } catch (Exception ignored) { }
        }
        @JavascriptInterface public void openList() { ui.post(() -> { try { startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void pick() { ui.post(() -> { InputMethodManager m = getSystemService(InputMethodManager.class); if (m != null) m.showInputMethodPicker(); }); }
        @JavascriptInterface public void forget() {
            for (String l : new String[]{"fr", "en"}) getSharedPreferences("pupkbd_words_" + l, MODE_MULTI_PROCESS).edit().clear().commit();
            sp().edit().putInt("resetVer", sp().getInt("resetVer", 0) + 1).commit();
        }
        @JavascriptInterface public void clearClips() { sp().edit().putString("clips", "[]").putInt("resetVer", sp().getInt("resetVer", 0) + 1).commit(); }
    }
}

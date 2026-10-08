package fr.piika.puppyphone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PupMusic — lecteur façon Poweramp, thème puppyplay. */
public class MusicActivity extends Activity {
    static final String HOST = "pupmusic.local";
    static final int REQ_PERM = 71, REQ_MIC = 72;
    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    float dp = 1f;
    int insT, insB;
    Uri pendingUri;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        dp = getResources().getDisplayMetrics().density;
        try { startService(new Intent(this, MusicService.class)); } catch (Exception ignored) { }
        readIntent(getIntent());
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0614);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        s.setOffscreenPreRaster(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !HOST.equals(r.getUrl().getHost()); }
            @Override public void onPageFinished(WebView v, String u) { pushInsets(); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Music");
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v, ins) -> {
            insT = ins.getSystemWindowInsetTop();
            insB = ins.getSystemWindowInsetBottom();
            pushInsets();
            return ins.consumeSystemWindowInsets();
        });
        web.loadUrl("https://" + HOST + "/music.html");
        askPerms();
    }

    void readIntent(Intent i) {
        if (i == null) return;
        Uri d = i.getData();
        if (d == null && Intent.ACTION_SEND.equals(i.getAction())) d = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (d != null) pendingUri = d;
        flushUri();
    }

    void flushUri() {
        if (pendingUri == null) return;
        final Uri u = pendingUri;
        ui.postDelayed(() -> {
            MusicService s = MusicService.I;
            if (s == null) { ui.postDelayed(this::flushUri, 300); return; }
            pendingUri = null;
            new Thread(() -> {
                MusicLib.Track t = MusicLib.external(this, u);
                List<MusicLib.Track> l = new ArrayList<>();
                l.add(t);
                ui.post(() -> { s.setQueue(l, 0, false); emit("open", "player"); });
            }).start();
        }, 150);
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); readIntent(i); }

    @Override protected void onResume() {
        super.onResume(); Pelage.watch(this, web);
        MusicService.listener = () -> { MusicService s = MusicService.I; if (s != null) emit("state", s.stateJson()); };
        ui.postDelayed(() -> { MusicService s = MusicService.I; if (s != null) emit("state", s.stateJson()); }, 120);
        emit("resume", "");
    }

    @Override protected void onPause() {
        MusicService.listener = null;
        MusicService s = MusicService.I;
        if (s != null) s.visOff();
        super.onPause();
    }

    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }

    @Override public void onBackPressed() {
        web.evaluateJavascript("(window.MusicUI&&MusicUI.back())?'y':'n'", v -> { if (!"\"y\"".equals(v)) moveTaskToBack(true); });
    }

    void emit(String ev, String data) {
        ui.post(() -> { if (web != null) web.evaluateJavascript("window.MusicUI&&MusicUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); });
    }

    void pushInsets() {
        final float t = insT / dp, bt = insB / dp;
        ui.post(() -> web.evaluateJavascript("window.MusicUI&&MusicUI.insets(" + t + "," + bt + ")", null));
    }

    void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    String audioPerm() { return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE; }
    boolean hasAudioPerm() { return checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED; }

    void askPerms() {
        List<String> p = new ArrayList<>();
        if (!hasAudioPerm()) p.add(audioPerm());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        if (r == REQ_PERM) emit("perm", hasAudioPerm() ? "1" : "0");
        if (r == REQ_MIC) emit("mic", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "1" : "0");
    }

    List<MusicLib.Track> tracks(String json) {
        List<MusicLib.Track> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) { MusicLib.Track t = MusicLib.get(this, a.getLong(i)); if (t != null) l.add(t); }
        } catch (Exception ignored) { }
        return l;
    }

    interface Op { void run(MusicService s); }
    void svc(Op o) {
        ui.post(() -> {
            MusicService s = MusicService.I;
            if (s == null) { try { startService(new Intent(this, MusicService.class)); } catch (Exception ignored) { } ui.postDelayed(() -> { if (MusicService.I != null) o.run(MusicService.I); }, 400); return; }
            o.run(s);
        });
    }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        try {
            if (p.equals("/art")) {
                long a = Long.parseLong(u.getQueryParameter("a") == null ? "-1" : u.getQueryParameter("a"));
                String t = u.getQueryParameter("t");
                int sz = Integer.parseInt(u.getQueryParameter("s") == null ? "256" : u.getQueryParameter("s"));
                byte[] b = MusicLib.art(this, a, t, Math.max(64, Math.min(1024, sz)));
                if (b.length == 0) throw new Exception();
                h.put("Cache-Control", "max-age=604800");
                return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(b));
            }
            String path = p.equals("/") ? "/music.html" : p;
            InputStream in = Pelage.open(this, path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    class Bridge {
        @JavascriptInterface public boolean perm() { return hasAudioPerm(); }
        @JavascriptInterface public void askPerm() { ui.post(MusicActivity.this::askPerms); }
        @JavascriptInterface public void scan(int minSec) { new Thread(() -> emit("lib", MusicLib.scan(MusicActivity.this, minSec))).start(); }
        @JavascriptInterface public String state() { MusicService s = MusicService.I; return s == null ? "{}" : s.stateJson(); }
        @JavascriptInterface public String queue() { MusicService s = MusicService.I; return s == null ? "[]" : s.queueJson(); }
        @JavascriptInterface public String pos() {
            MusicService s = MusicService.I;
            if (s == null) return "0,0,0";
            return s.pos() + "," + s.dur() + "," + (s.isPlaying() ? 1 : 0);
        }
        @JavascriptInterface public void play(String ids, int start, boolean shuffle) { List<MusicLib.Track> l = tracks(ids); svc(s -> s.setQueue(l, start, shuffle)); }
        @JavascriptInterface public void enqueue(String ids, boolean next) { List<MusicLib.Track> l = tracks(ids); svc(s -> s.enqueue(l, next)); }
        @JavascriptInterface public void toggle() { svc(MusicService::toggle); }
        @JavascriptInterface public void next() { svc(MusicService::next); }
        @JavascriptInterface public void prev() { svc(MusicService::prev); }
        @JavascriptInterface public void seek(int ms) { svc(s -> s.seek(ms)); }
        @JavascriptInterface public void shuffle(boolean on) { svc(s -> s.setShuffle(on)); }
        @JavascriptInterface public void repeat(int r) { svc(s -> s.setRepeat(r)); }
        @JavascriptInterface public void jump(int i) { svc(s -> s.jump(i)); }
        @JavascriptInterface public void qremove(int i) { svc(s -> s.removeAt(i)); }
        @JavascriptInterface public void qmove(int a, int b) { svc(s -> s.move(a, b)); }
        @JavascriptInterface public void qclear() { svc(MusicService::clearQueue); }
        @JavascriptInterface public String fxGet() { MusicService s = MusicService.I; return s == null ? getSharedPreferences("pupmusic", MODE_PRIVATE).getString("fx", "{}") : s.fxCfg.toString(); }
        @JavascriptInterface public void fx(String json) { try { JSONObject o = new JSONObject(json); svc(s -> s.setFx(o)); } catch (Exception ignored) { } }
        @JavascriptInterface public void opts(String json) { try { JSONObject o = new JSONObject(json); svc(s -> s.setOpts(o)); } catch (Exception ignored) { } }
        @JavascriptInterface public void sleep(int min) { svc(s -> s.sleep(min)); }
        @JavascriptInterface public boolean hasMic() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }
        @JavascriptInterface public void askMic() { ui.post(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC)); }
        @JavascriptInterface public void vis(boolean on) {
            svc(s -> {
                if (!on) { s.visOff(); return; }
                if (!hasMic()) { askMic(); return; }
                if (!s.visOn()) toast("Visualiseur refusé par le téléphone");
            });
        }
        @JavascriptInterface public String fft() { MusicService s = MusicService.I; return s == null ? "" : s.fft(48); }
        @JavascriptInterface public String stats() { MusicService s = MusicService.I; return s == null ? getSharedPreferences("pupmusic", MODE_PRIVATE).getString("stats", "{}") : s.stats.toString(); }
        @JavascriptInterface public String lyrics(String path) {
            try {
                if (path == null || path.isEmpty()) return "";
                String base = path.replaceAll("\\.[^./]+$", "");
                for (String ext : new String[]{".lrc", ".LRC", ".txt"}) {
                    File f = new File(base + ext);
                    if (f.canRead() && f.length() < 512 * 1024) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) { }
            return "";
        }
        @JavascriptInterface public void share(String uri, String title) {
            ui.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, title));
                } catch (Exception e) { toast("Partage impossible"); }
            });
        }
        @JavascriptInterface public void sysVolume(int dir) {
            ui.post(() -> getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC, dir > 0 ? AudioManager.ADJUST_RAISE : dir < 0 ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI));
        }
        @JavascriptInterface public void haptic() { ui.post(() -> web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)); }
        @JavascriptInterface public void toast(String s) { MusicActivity.this.toast(s); }
        @JavascriptInterface public void close() { ui.post(() -> moveTaskToBack(true)); }
    }
}

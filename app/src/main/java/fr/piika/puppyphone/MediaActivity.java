package fr.piika.puppyphone;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.util.Size;
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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PupGalery / PupVidéo : galerie photo-vidéo et lecteur vidéo natif, interface puppyplay. */
public class MediaActivity extends Activity {
    static final String HOST = "pupmedia.local";
    static final int REQ_PERM = 31, REQ_DEL = 32;

    final Handler ui = new Handler(Looper.getMainLooper());
    FrameLayout root;
    TextureView tex;
    WebView web;
    float dp = 1f;
    int insT, insB;
    String mode = "gallery";
    boolean pickMode, pickMultiple;
    String pickType = "*/*";
    Uri extUri;
    String extMime;

    final LruCache<String, byte[]> thumbs = new LruCache<String, byte[]>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String k, byte[] v) { return v.length; }
    };

    // lecteur
    MediaPlayer mp;
    Surface surface;
    int vw, vh;
    boolean prepared, looping;
    String fit = "fit";
    float speed = 1f;
    AudioManager am;
    AudioFocusRequest afr;
    List<Uri> pendingDelete = new ArrayList<>();
    JSONArray pendingDeleteIds;

    String modeFromIntent() {
        String cls = getClass().getSimpleName();
        if (cls.startsWith("Video")) return "video";
        if (cls.startsWith("Pick")) return "pick";
        return "gallery";
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        normalBars();
        dp = getResources().getDisplayMetrics().density;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        mode = modeFromIntent();
        readIntent(getIntent());

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0614);
        tex = new TextureView(this);
        tex.setVisibility(View.GONE);
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int a, int c) { surface = new Surface(st); if (mp != null) try { mp.setSurface(surface); } catch (Exception ignored) { } fitVideo(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int a, int c) { fitVideo(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { if (mp != null) try { mp.setSurface(null); } catch (Exception ignored) { } if (surface != null) surface.release(); surface = null; return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        root.addView(tex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0614);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        s.setOffscreenPreRaster(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !HOST.equals(r.getUrl().getHost()); }
            @Override public void onPageFinished(WebView v, String u) { pushInsets(); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Media");
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insT = ins.getSystemWindowInsetTop();
            insB = ins.getSystemWindowInsetBottom();
            pushInsets();
            return ins.consumeSystemWindowInsets();
        });
        web.loadUrl("https://" + HOST + "/media.html?mode=" + mode);
        if (permState().equals("none")) askPerm();
    }

    void readIntent(Intent i) {
        if (i == null) return;
        String a = i.getAction();
        if (Intent.ACTION_GET_CONTENT.equals(a) || Intent.ACTION_PICK.equals(a)) {
            pickMode = true;
            mode = "pick";
            pickMultiple = i.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            pickType = i.getType() == null ? "*/*" : i.getType();
        } else if (Intent.ACTION_VIEW.equals(a) && i.getData() != null) {
            extUri = i.getData();
            extMime = i.getType() != null ? i.getType() : getContentResolver().getType(extUri);
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        readIntent(i);
        if (extUri != null) emit("external", extJson());
    }

    String extJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("uri", extUri.toString());
            o.put("mime", extMime == null ? "" : extMime);
            String name = extUri.getLastPathSegment();
            try (Cursor c = getContentResolver().query(extUri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) { }
            o.put("name", name);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @Override protected void onResume() { super.onResume(); emit("resume", permState()); }
    @Override protected void onPause() { if (mp != null && prepared && mp.isPlaying()) { mp.pause(); emit("player", playerState()); } super.onPause(); }
    @Override protected void onDestroy() { stopPlayer(); if (web != null) web.destroy(); super.onDestroy(); }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.MediaUI&&MediaUI.back())?'y':'n'", v -> { if (!"\"y\"".equals(v)) finish(); });
    }

    void emit(String ev, String data) {
        ui.post(() -> { if (web != null) web.evaluateJavascript("window.MediaUI&&MediaUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); });
    }

    void pushInsets() {
        final float t = insT / dp, bt = insB / dp;
        ui.post(() -> { if (web != null) web.evaluateJavascript("window.MediaUI&&MediaUI.insets(" + t + "," + bt + ")", null); });
    }

    void normalBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    // ------------------------------------------------------------------ permissions
    String permState() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean img = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            boolean vid = checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            if (img && vid) return "full";
            if (Build.VERSION.SDK_INT >= 34 && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED) return "partial";
            return img || vid ? "partial" : "none";
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ? "full" : "none";
    }

    void askPerm() {
        ui.post(() -> {
            if (Build.VERSION.SDK_INT >= 34) requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"}, REQ_PERM);
            else if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}, REQ_PERM);
            else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        });
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM) emit("perm", permState());
    }

    // ------------------------------------------------------------------ MediaStore
    static Uri uriOf(String kind, long id) {
        return ContentUris.withAppendedId("v".equals(kind) ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
    }

    String listJson() {
        JSONArray a = new JSONArray();
        query(a, "i", MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        query(a, "v", MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        return a.toString();
    }

    void query(JSONArray out, String kind, Uri coll) {
        boolean v = "v".equals(kind);
        List<String> cols = new ArrayList<>();
        cols.add(MediaStore.MediaColumns._ID);
        cols.add(MediaStore.MediaColumns.DISPLAY_NAME);
        cols.add(MediaStore.MediaColumns.DATE_ADDED);
        cols.add(MediaStore.MediaColumns.WIDTH);
        cols.add(MediaStore.MediaColumns.HEIGHT);
        cols.add(MediaStore.MediaColumns.SIZE);
        cols.add(MediaStore.MediaColumns.MIME_TYPE);
        cols.add("bucket_display_name");
        cols.add("bucket_id");
        cols.add("datetaken");
        if (v) cols.add(MediaStore.Video.VideoColumns.DURATION);
        else cols.add(MediaStore.Images.ImageColumns.ORIENTATION);
        try (Cursor c = getContentResolver().query(coll, cols.toArray(new String[0]), null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return;
            int iId = c.getColumnIndex(cols.get(0)), iName = c.getColumnIndex(cols.get(1)), iAdd = c.getColumnIndex(cols.get(2)),
                    iW = c.getColumnIndex(cols.get(3)), iH = c.getColumnIndex(cols.get(4)), iSize = c.getColumnIndex(cols.get(5)),
                    iMime = c.getColumnIndex(cols.get(6)), iBucket = c.getColumnIndex(cols.get(7)), iBid = c.getColumnIndex(cols.get(8)),
                    iTaken = c.getColumnIndex(cols.get(9)), iX = c.getColumnIndex(cols.get(10));
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(iId));
                o.put("k", kind);
                o.put("n", c.getString(iName));
                long taken = iTaken >= 0 ? c.getLong(iTaken) : 0;
                if (taken <= 0) taken = c.getLong(iAdd) * 1000L;
                o.put("t", taken);
                int w = c.getInt(iW), h = c.getInt(iH);
                if (!v && iX >= 0) { int rot = c.getInt(iX); if (rot == 90 || rot == 270) { int x = w; w = h; h = x; } }
                o.put("w", w);
                o.put("h", h);
                o.put("s", c.getLong(iSize));
                o.put("m", c.getString(iMime));
                o.put("b", iBucket >= 0 ? c.getString(iBucket) : "");
                o.put("bid", iBid >= 0 ? c.getString(iBid) : "");
                if (v && iX >= 0) o.put("d", c.getLong(iX));
                out.put(o);
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ fichiers servis à la page
    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        try {
            if (p.equals("/th")) {
                String kind = u.getQueryParameter("k");
                long id = Long.parseLong(u.getQueryParameter("id"));
                String key = kind + id;
                byte[] b = thumbs.get(key);
                if (b == null) {
                    Bitmap bm;
                    Uri mu = uriOf(kind, id);
                    if (Build.VERSION.SDK_INT >= 29) bm = getContentResolver().loadThumbnail(mu, new Size(360, 360), null);
                    else if ("v".equals(kind)) bm = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                    else bm = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                    if (bm == null) return notFound();
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.JPEG, 82, o);
                    bm.recycle();
                    b = o.toByteArray();
                    thumbs.put(key, b);
                }
                h.put("Cache-Control", "max-age=604800");
                return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(b));
            }
            if (p.equals("/img") || p.equals("/ext")) {
                Uri mu = p.equals("/ext") ? Uri.parse(u.getQueryParameter("u")) : uriOf("i", Long.parseLong(u.getQueryParameter("id")));
                String mime = getContentResolver().getType(mu);
                if (mime != null && (mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/webp") || mime.equals("image/gif") || mime.equals("image/bmp"))) {
                    InputStream in = getContentResolver().openInputStream(mu);
                    return new WebResourceResponse(mime, null, 200, "OK", h, in);
                }
                // HEIC, AVIF, DNG… : on convertit pour l'affichage
                Bitmap bm = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), mu), (dec, info, src) -> {
                    Size s = info.getSize();
                    int max = Math.max(s.getWidth(), s.getHeight());
                    if (max > 3000) { float k = 3000f / max; dec.setTargetSize(Math.round(s.getWidth() * k), Math.round(s.getHeight() * k)); }
                    dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 92, o);
                bm.recycle();
                return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(o.toByteArray()));
            }
            String path = p.equals("/") ? "/media.html" : p;
            InputStream in = getAssets().open("www" + path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return notFound();
        }
    }

    WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    // ------------------------------------------------------------------ lecteur vidéo natif
    void play(Uri u, int startMs) {
        stopPlayer();
        tex.setVisibility(View.VISIBLE);
        web.setBackgroundColor(Color.TRANSPARENT);
        immersive();
        prepared = false;
        mp = new MediaPlayer();
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
        mp.setAudioAttributes(attrs);
        mp.setLooping(looping);
        try { mp.setDataSource(this, u); } catch (Exception e) { toast("Vidéo illisible"); emit("player", "{\"error\":true}"); return; }
        if (surface != null) mp.setSurface(surface);
        mp.setOnVideoSizeChangedListener((m, w, h) -> { vw = w; vh = h; fitVideo(); autoRotate(); });
        mp.setOnPreparedListener(m -> {
            prepared = true;
            vw = m.getVideoWidth();
            vh = m.getVideoHeight();
            fitVideo();
            autoRotate();
            if (startMs > 0 && startMs < m.getDuration() - 3000) m.seekTo(startMs);
            setSpeed(speed);
            if (afr == null) afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(fc -> { if (fc < 0 && mp != null && prepared && mp.isPlaying()) { mp.pause(); emit("player", playerState()); } }).build();
            am.requestAudioFocus(afr);
            m.start();
            web.setKeepScreenOn(true);
            emit("player", playerState());
        });
        mp.setOnCompletionListener(m -> { web.setKeepScreenOn(false); emit("ended", playerState()); });
        mp.setOnErrorListener((m, a, x) -> { toast("Erreur de lecture"); emit("player", "{\"error\":true}"); return true; });
        mp.prepareAsync();
    }

    void autoRotate() {
        if (vw <= 0 || vh <= 0) return;
        if (!"auto".equals(getSharedPreferences("pupmedia", MODE_PRIVATE).getString("rot", "auto"))) return;
        setRequestedOrientation(vw > vh ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    void stopPlayer() {
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        prepared = false;
        if (afr != null) am.abandonAudioFocusRequest(afr);
        if (tex != null) tex.setVisibility(View.GONE);
        if (web != null) { web.setBackgroundColor(0xFF0B0614); web.setKeepScreenOn(false); }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        normalBars();
    }

    void fitVideo() {
        if (tex == null || vw <= 0 || vh <= 0) return;
        float W = tex.getWidth(), H = tex.getHeight();
        if (W <= 0 || H <= 0) return;
        float s = "fill".equals(fit) ? Math.max(W / vw, H / vh) : Math.min(W / vw, H / vh);
        if ("stretch".equals(fit)) { Matrix m = new Matrix(); tex.setTransform(m); return; }
        Matrix m = new Matrix();
        m.setScale(vw * s / W, vh * s / H, W / 2f, H / 2f);
        tex.setTransform(m);
    }

    void setSpeed(float s) {
        speed = s;
        if (mp == null || !prepared) return;
        try {
            boolean was = mp.isPlaying();
            PlaybackParams pp = mp.getPlaybackParams();
            pp.setSpeed(s);
            mp.setPlaybackParams(pp);
            if (!was) mp.pause();
        } catch (Exception ignored) { }
    }

    String playerState() {
        try {
            JSONObject o = new JSONObject();
            o.put("pos", mp != null && prepared ? mp.getCurrentPosition() : 0);
            o.put("dur", mp != null && prepared ? mp.getDuration() : 0);
            o.put("playing", mp != null && prepared && mp.isPlaying());
            o.put("w", vw);
            o.put("h", vh);
            o.put("speed", speed);
            o.put("fit", fit);
            o.put("loop", looping);
            o.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
            float br = getWindow().getAttributes().screenBrightness;
            o.put("bright", br < 0 ? -1 : br);
            o.put("rot", getSharedPreferences("pupmedia", MODE_PRIVATE).getString("rot", "auto"));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    // ------------------------------------------------------------------ résultats
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_DEL) {
            if (res == RESULT_OK && pendingDeleteIds != null) emit("deleted", pendingDeleteIds.toString());
            pendingDeleteIds = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    List<Uri> uris(String json) {
        List<Uri> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(uriOf(o.getString("k"), o.getLong("id")));
            }
        } catch (Exception ignored) { }
        return l;
    }

    // ------------------------------------------------------------------ pont
    class Bridge {
        @JavascriptInterface public String mode() { return mode; }
        @JavascriptInterface public boolean pickMultiple() { return pickMultiple; }
        @JavascriptInterface public String pickType() { return pickType; }
        @JavascriptInterface public String perm() { return permState(); }
        @JavascriptInterface public void askPerm() { MediaActivity.this.askPerm(); }
        @JavascriptInterface public void permSettings() {
            ui.post(() -> { try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { } });
        }
        @JavascriptInterface public String list() { return listJson(); }
        @JavascriptInterface public String external() { return extUri == null ? "" : extJson(); }

        @JavascriptInterface public String get(String k) { return getSharedPreferences("pupmedia", MODE_PRIVATE).getString("ui_" + k, null); }
        @JavascriptInterface public void set(String k, String v) { getSharedPreferences("pupmedia", MODE_PRIVATE).edit().putString("ui_" + k, v).apply(); }

        @JavascriptInterface public void share(String json) {
            ui.post(() -> {
                List<Uri> l = uris(json);
                if (l.isEmpty()) return;
                Intent i;
                if (l.size() == 1) i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, l.get(0));
                else i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(l));
                i.setType("*/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Partager"));
            });
        }

        @JavascriptInterface public void trash(String json) {
            ui.post(() -> {
                List<Uri> l = uris(json);
                if (l.isEmpty()) return;
                try {
                    pendingDeleteIds = new JSONArray(json);
                    if (Build.VERSION.SDK_INT >= 30) {
                        PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), l, true);
                        startIntentSenderForResult(pi.getIntentSender(), REQ_DEL, null, 0, 0, 0);
                    } else {
                        ContentResolver cr = getContentResolver();
                        for (Uri u : l) cr.delete(u, null, null);
                        emit("deleted", json);
                        pendingDeleteIds = null;
                    }
                } catch (Exception e) { toast("Suppression impossible"); pendingDeleteIds = null; }
            });
        }

        @JavascriptInterface public void setWallpaper(String kind, long id, String name) {
            ui.post(() -> {
                SharedPreferences.Editor e = getSharedPreferences(PupWallService.PREFS, MODE_PRIVATE).edit();
                Uri u = uriOf(kind, id);
                if ("v".equals(kind)) e.putString("wall_uri", u.toString()).putString("wall_name", name).putString("wall_type", "video");
                else e.putString("img_uri", u.toString()).putString("img_name", name).putString("wall_type", "image");
                e.putLong("wall_ver", System.currentTimeMillis()).commit();
                toast("v".equals(kind) ? "🐾 Fond vidéo (avec le son) appliqué à PuppyPhone" : "🐾 Fond d'écran appliqué à PuppyPhone");
            });
        }

        @JavascriptInterface public void openWith(String kind, long id) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(kind, id), "v".equals(kind) ? "video/*" : "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(i, "Ouvrir avec")); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void edit(String kind, long id) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_EDIT).setDataAndType(uriOf(kind, id), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try { startActivity(Intent.createChooser(i, "Modifier avec")); } catch (Exception e) { toast("Aucune appli de retouche"); }
            });
        }

        @JavascriptInterface public void pick(String json) {
            ui.post(() -> {
                List<Uri> l = uris(json);
                if (l.isEmpty()) { setResult(RESULT_CANCELED); finish(); return; }
                Intent r = new Intent();
                r.setData(l.get(0));
                if (l.size() > 1) {
                    ClipData cd = ClipData.newRawUri("", l.get(0));
                    for (int i = 1; i < l.size(); i++) cd.addItem(new ClipData.Item(l.get(i)));
                    r.setClipData(cd);
                }
                r.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                setResult(RESULT_OK, r);
                finish();
            });
        }

        // lecteur
        @JavascriptInterface public void play(String kind, long id, int startMs) { ui.post(() -> MediaActivity.this.play(uriOf(kind, id), startMs)); }
        @JavascriptInterface public void playExternal(int startMs) { ui.post(() -> { if (extUri != null) MediaActivity.this.play(extUri, startMs); }); }
        @JavascriptInterface public void close() { ui.post(MediaActivity.this::stopPlayer); }
        @JavascriptInterface public void toggle() {
            ui.post(() -> {
                if (mp == null || !prepared) return;
                if (mp.isPlaying()) { mp.pause(); web.setKeepScreenOn(false); }
                else { if (afr != null) am.requestAudioFocus(afr); mp.start(); web.setKeepScreenOn(true); }
                emit("player", playerState());
            });
        }
        @JavascriptInterface public void seek(int ms) { ui.post(() -> { if (mp != null && prepared) { mp.seekTo(Math.max(0, Math.min(ms, mp.getDuration()))); emit("player", playerState()); } }); }
        @JavascriptInterface public String pstate() {
            final String[] r = {"{}"};
            final Object lock = new Object();
            ui.post(() -> { synchronized (lock) { r[0] = playerState(); lock.notifyAll(); } });
            synchronized (lock) { try { lock.wait(300); } catch (InterruptedException ignored) { } }
            return r[0];
        }
        @JavascriptInterface public void speed(float s) { ui.post(() -> { setSpeed(s); emit("player", playerState()); }); }
        @JavascriptInterface public void fit(String f) { ui.post(() -> { fit = f; fitVideo(); emit("player", playerState()); }); }
        @JavascriptInterface public void loop(boolean l) { ui.post(() -> { looping = l; if (mp != null) mp.setLooping(l); emit("player", playerState()); }); }
        @JavascriptInterface public void rotate(String r) {
            ui.post(() -> {
                getSharedPreferences("pupmedia", MODE_PRIVATE).edit().putString("rot", r).apply();
                if ("land".equals(r)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                else if ("port".equals(r)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                else { setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); autoRotate(); }
                emit("player", playerState());
            });
        }
        @JavascriptInterface public void volume(float v) {
            ui.post(() -> {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Math.max(0, Math.min(1, v)) * max), 0);
            });
        }
        @JavascriptInterface public void brightness(float v) {
            ui.post(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = Math.max(0.02f, Math.min(1f, v));
                getWindow().setAttributes(lp);
            });
        }
        @JavascriptInterface public void haptic() { ui.post(() -> web.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)); }
        @JavascriptInterface public void toast(String s) { MediaActivity.this.toast(s); }
        @JavascriptInterface public void exit() { ui.post(MediaActivity.this::finish); }
    }
}

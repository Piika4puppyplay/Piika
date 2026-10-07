package fr.piika.puppyphone;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.TonemapCurve;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.ExifInterface;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.system.Os;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PupCamera — appareil photo/vidéo Camera2.
 * Aucune coupure liée à la chaleur : la température est seulement affichée (chiot qui transpire).
 */
public class CameraActivity extends Activity {
    static final String HOST = "pupcam.local";
    static final int REQ_PERM = 41;
    static final int TOPBAR_DP = 60;

    final Handler ui = new Handler(Looper.getMainLooper());
    HandlerThread bgT;
    Handler bg;
    CameraManager cm;
    CameraDevice dev;
    CameraCaptureSession sess;
    CaptureRequest.Builder prevB;
    ImageReader jpeg;
    MediaRecorder rec;
    ParcelFileDescriptor recPfd;
    Uri recUri;
    File recFile;
    long recStart, recPausedAt, recPausedTotal;
    boolean recording, recPaused, opening;

    FrameLayout root;
    TextureView tex;
    WebView web;
    Surface prevSurface;
    float dp = 1f;
    int insT, insB, screenW, screenH;

    String camId;
    CameraCharacteristics ch;
    int sensorOrient = 90;
    boolean front;
    Rect active;
    Range<Float> zoomRange = new Range<>(1f, 1f);
    float zoom = 1f;
    Size prevSize, photoSize, videoSize;
    int videoFps = 30;
    boolean hevc = true;
    String flash = "off";
    int ev = 0;
    boolean videoMode;
    MeteringRectangle[] focusRegion;
    int devOrient = 0;
    OrientationEventListener oel;
    MediaActionSound sound;
    SharedPreferences sp;
    PowerManager pm;
    int thermal = 0;
    Uri lastUri;
    String lastKind = "i";
    boolean captureIntent, videoCaptureIntent;
    Uri captureOut;

    // ------------------------------------------------------------------ cycle de vie
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.BLACK);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        dp = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        sp = getSharedPreferences("pupcam", MODE_PRIVATE);
        cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        pm = (PowerManager) getSystemService(POWER_SERVICE);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try { getSharedPreferences("pupcam", MODE_PRIVATE).edit().remove("cam").commit(); } catch (Exception ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
        sound = new MediaActionSound();
        sound.load(MediaActionSound.SHUTTER_CLICK);

        Intent in = getIntent();
        String act = in == null ? null : in.getAction();
        captureIntent = MediaStore.ACTION_IMAGE_CAPTURE.equals(act);
        videoCaptureIntent = MediaStore.ACTION_VIDEO_CAPTURE.equals(act);
        if (captureIntent || videoCaptureIntent) captureOut = in.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        videoMode = videoCaptureIntent || MediaStore.INTENT_ACTION_VIDEO_CAMERA.equals(act) || (!captureIntent && sp.getBoolean("video", false));
        hevc = sp.getBoolean("hevc", true);
        flash = sp.getString("flash", "off");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        tex = new TextureView(this);
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int a, int c) { openIfReady(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int a, int c) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        root.addView(tex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !HOST.equals(r.getUrl().getHost()); }
            @Override public void onPageFinished(WebView v, String u) { pushLayout(); emitState(); emitThermal(); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Cam");
        web.setKeepScreenOn(true);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insT = ins.getSystemWindowInsetTop();
            insB = ins.getSystemWindowInsetBottom();
            layoutPreview();
            return ins.consumeSystemWindowInsets();
        });

        oel = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int r = ((o + 45) / 90 * 90) % 360;
                if (r != devOrient) { devOrient = r; emit("orient", String.valueOf(r)); }
            }
        };

        if (Build.VERSION.SDK_INT >= 29) {
            pm.addThermalStatusListener(getMainExecutor(), st -> { thermal = st; emitThermal(); });
            thermal = pm.getCurrentThermalStatus();
        }
        ui.postDelayed(thermalTick, 4000);

        web.loadUrl("https://" + HOST + "/camera.html");
    }

    final Runnable thermalTick = new Runnable() {
        @Override public void run() { emitThermal(); ui.postDelayed(this, 8000); }
    };

    boolean hasPerms() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && (Build.VERSION.SDK_INT >= 29 || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
    }

    void askPerms() {
        List<String> p = new ArrayList<>(Arrays.asList(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO));
        if (Build.VERSION.SDK_INT < 29) p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        requestPermissions(p.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM) { emit("perm", hasPerms() ? "ok" : "no"); openIfReady(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        bgT = new HandlerThread("pupcam");
        bgT.start();
        bg = new Handler(bgT.getLooper());
        if (oel.canDetectOrientation()) oel.enable();
        if (!hasPerms()) askPerms();
        else openIfReady();
        voiceOn = sp.getBoolean("voiceOn", false);
        if (voiceOn && hasPerms()) ui.postDelayed(this::voiceStart, 1200);
    }

    @Override
    protected void onPause() {
        if (recording) stopRec();
        voiceStop();
        closeCam();
        oel.disable();
        if (bgT != null) { bgT.quitSafely(); bgT = null; bg = null; }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(thermalTick);
        if (sound != null) sound.release();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.CamUI&&CamUI.back())?'y':'n'", v -> { if (!"\"y\"".equals(v)) finish(); });
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_CAMERA) && sp.getBoolean("volkey", true)) {
            if (e.getRepeatCount() == 0) emit("key", "shutter");
            return true;
        }
        return super.onKeyDown(code, e);
    }

    void emit(String ev, String data) {
        ui.post(() -> { if (web != null) web.evaluateJavascript("window.CamUI&&CamUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); });
    }

    void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    // ------------------------------------------------------------------ chaleur (affichage uniquement, jamais de coupure)
    void emitThermal() {
        try {
            JSONObject o = new JSONObject();
            o.put("status", thermal);
            Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            float t = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) / 10f;
            o.put("temp", t);
            if (Build.VERSION.SDK_INT >= 30) {
                float h = pm.getThermalHeadroom(10);
                if (!Float.isNaN(h)) o.put("headroom", h);
            }
            emit("thermal", o.toString());
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ capteurs
    String lensesJson() {
        JSONArray a = new JSONArray();
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f == null || f == CameraCharacteristics.LENS_FACING_EXTERNAL) continue;
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("front", f == CameraCharacteristics.LENS_FACING_FRONT);
                float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF ss = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (fl != null && fl.length > 0 && ss != null) o.put("eq", Math.round(fl[0] * 43.27 / Math.hypot(ss.getWidth(), ss.getHeight())));
                Range<Float> zr = zoomRangeOf(c);
                o.put("zmin", zr.getLower());
                o.put("zmax", zr.getUpper());
                int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean logical = false;
                if (caps != null) for (int x : caps) if (x == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) logical = true;
                o.put("logical", logical);
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] js = map.getOutputSizes(ImageFormat.JPEG);
                    if (js != null && js.length > 0) {
                        Size big = Collections.max(Arrays.asList(js), (x, y) -> Long.compare((long) x.getWidth() * x.getHeight(), (long) y.getWidth() * y.getHeight()));
                        o.put("mp", Math.round(big.getWidth() * (long) big.getHeight() / 1e5) / 10.0);
                    }
                }
                a.put(o);
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    Range<Float> zoomRangeOf(CameraCharacteristics c) {
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> r = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (r != null) return r;
        }
        Float m = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        return new Range<>(1f, m == null ? 1f : m);
    }

    String defaultCamId(boolean wantFront) {
        try {
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && (f == CameraCharacteristics.LENS_FACING_FRONT) == wantFront) return id;
            }
        } catch (Exception ignored) { }
        return "0";
    }

    // ------------------------------------------------------------------ tailles et qualités
    static long area(Size s) { return (long) s.getWidth() * s.getHeight(); }

    Size pick(Size[] arr, float ratio, long maxArea) {
        Size best = null;
        if (arr == null) return null;
        for (Size s : arr) {
            float r = Math.max(s.getWidth(), s.getHeight()) / (float) Math.min(s.getWidth(), s.getHeight());
            if (Math.abs(r - ratio) > 0.02f || area(s) > maxArea) continue;
            if (best == null || area(s) > area(best)) best = s;
        }
        if (best == null) for (Size s : arr) if (area(s) <= maxArea && (best == null || area(s) > area(best))) best = s;
        return best;
    }

    boolean fpsOk(StreamConfigurationMap map, Size s, int fps) {
        try {
            long min = map.getOutputMinFrameDuration(MediaRecorder.class, s);
            if (min > 0 && min > 1_000_000_000L / fps + 50_000) return false;
            Range<Integer>[] rs = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            boolean ok = false;
            if (rs != null) for (Range<Integer> r : rs) if (r.getUpper() >= fps) ok = true;
            return ok;
        } catch (Exception e) { return false; }
    }

    boolean encoderOk(String mime, Size s, int fps) {
        try {
            MediaFormat f = MediaFormat.createVideoFormat(mime, s.getWidth(), s.getHeight());
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f) != null;
        } catch (Exception e) { return false; }
    }

    static final String[][] QUALITIES = {
            {"8k30", "7680", "4320", "30"}, {"4k60", "3840", "2160", "60"}, {"4k30", "3840", "2160", "30"},
            {"1080p60", "1920", "1080", "60"}, {"1080p30", "1920", "1080", "30"}};

    JSONArray videoQualities() {
        JSONArray a = new JSONArray();
        if (ch == null) return a;
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return a;
        Size[] mr;
        try { mr = map.getOutputSizes(MediaRecorder.class); } catch (Exception e) { mr = null; }
        if (mr == null || mr.length == 0) return a;
        List<Size> rs = Arrays.asList(mr);
        for (String[] q : QUALITIES) {
            Size s = new Size(Integer.parseInt(q[1]), Integer.parseInt(q[2]));
            int fps = Integer.parseInt(q[3]);
            if (rs.contains(s) && fpsOk(map, s, fps) && (encoderOk(MediaFormat.MIMETYPE_VIDEO_HEVC, s, fps) || encoderOk(MediaFormat.MIMETYPE_VIDEO_AVC, s, fps))) a.put(q[0]);
        }
        return a;
    }

    void chooseSizes() {
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return;
        String ratioS = sp.getString("ratio", "4:3");
        float ratio = ratioS.equals("16:9") ? 16 / 9f : ratioS.equals("1:1") ? 1f : 4 / 3f;
        Size[] jpegs = map.getOutputSizes(ImageFormat.JPEG);
        photoSize = pick(jpegs, ratio == 1f ? 4 / 3f : ratio, Long.MAX_VALUE);
        // qualité vidéo : 4K60 par défaut, sinon la meilleure possible
        JSONArray qs = videoQualities();
        String want = sp.getString("quality", "4k60");
        String chosen = null;
        for (int i = 0; i < qs.length(); i++) if (qs.optString(i).equals(want)) chosen = want;
        if (chosen == null) {
            String[] order = {"4k60", "4k30", "1080p60", "1080p30", "8k30"};
            for (String o : order) for (int i = 0; i < qs.length() && chosen == null; i++) if (qs.optString(i).equals(o)) chosen = o;
        }
        videoSize = new Size(1920, 1080);
        videoFps = 30;
        if (chosen != null) for (String[] q : QUALITIES) if (q[0].equals(chosen)) { videoSize = new Size(Integer.parseInt(q[1]), Integer.parseInt(q[2])); videoFps = Integer.parseInt(q[3]); }
        float pr = videoMode ? videoSize.getWidth() / (float) videoSize.getHeight() : (ratio == 1f ? 4 / 3f : ratio);
        prevSize = pick(map.getOutputSizes(SurfaceTexture.class), pr, 1920L * 1440);
        if (prevSize == null) prevSize = new Size(1280, 720);
    }

    // ------------------------------------------------------------------ mise en page de l'aperçu
    float previewRatio() {
        if (videoMode) return 16 / 9f;
        String r = sp.getString("ratio", "4:3");
        return r.equals("16:9") ? 16 / 9f : r.equals("1:1") ? 1f : 4 / 3f;
    }

    void layoutPreview() {
        ui.post(() -> {
            float r = previewRatio();
            int w = root.getWidth() > 0 ? root.getWidth() : screenW;
            int h = Math.round(w * r);
            int top = insT + (int) (TOPBAR_DP * dp);
            int avail = (root.getHeight() > 0 ? root.getHeight() : screenH) - top;
            if (h > avail) top = Math.max(0, (root.getHeight() > 0 ? root.getHeight() : screenH) - h);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tex.getLayoutParams();
            lp.width = w;
            lp.height = h;
            lp.topMargin = top;
            tex.setLayoutParams(lp);
            pushLayout();
        });
    }

    void pushLayout() {
        ui.post(() -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tex.getLayoutParams();
            String js = "window.CamUI&&CamUI.layout(" + (lp.topMargin / dp) + "," + (lp.height / dp) + "," + (insT / dp) + "," + (insB / dp) + ")";
            web.evaluateJavascript(js, null);
        });
    }

    // ------------------------------------------------------------------ ouverture caméra
    void openIfReady() {
        if (!hasPerms() || !tex.isAvailable() || bg == null || dev != null || opening) return;
        if (camId == null) {
            camId = sp.getString("cam", defaultCamId(false));
            boolean known = false;
            try { for (String x : cm.getCameraIdList()) if (x.equals(camId)) known = true; } catch (Exception ignored) { }
            if (!known) camId = defaultCamId(false);
        }
        openCam(camId);
    }

    String lastGoodCam;

    /** Ouvre un capteur ; si ce module refuse les applis, on revient sans planter sur le précédent. */
    void openCam(String id) {
        try {
            openCamUnsafe(id);
        } catch (Throwable e) {
            opening = false;
            fallbackCam(id, e);
        }
    }

    void fallbackCam(String badId, Throwable e) {
        String back = lastGoodCam != null && !lastGoodCam.equals(badId) ? lastGoodCam : defaultCamId(front);
        sp.edit().remove("cam").apply();
        toast("Ce module refuse d'être utilisé par les applis (" + (e == null ? "erreur" : e.getClass().getSimpleName()) + ") → retour au principal");
        emit("lenserr", badId);
        if (back != null && !back.equals(badId)) ui.postDelayed(() -> { try { openCamUnsafe(back); } catch (Throwable t) { toast("Caméra indisponible"); } }, 300);
    }

    void openCamUnsafe(String id) throws Exception {
        {
            closeCam();
            camId = id;
            ch = cm.getCameraCharacteristics(id);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrient = so == null ? 90 : so;
            Integer f = ch.get(CameraCharacteristics.LENS_FACING);
            front = f != null && f == CameraCharacteristics.LENS_FACING_FRONT;
            active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            zoomRange = zoomRangeOf(ch);
            zoom = Math.max(zoomRange.getLower(), Math.min(zoomRange.getUpper(), 1f));
            focusRegion = null;
            ev = 0;
            chooseSizes();
            layoutPreview();
            opening = true;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    opening = false; dev = d; lastGoodCam = id; sp.edit().putString("cam", id).apply();
                    try { startPreview(); } catch (Throwable t) { fallbackCam(id, t); }
                }
                @Override public void onDisconnected(CameraDevice d) { opening = false; d.close(); dev = null; }
                @Override public void onError(CameraDevice d, int e) {
                    opening = false; try { d.close(); } catch (Exception ignored) { } dev = null;
                    fallbackCam(id, new Exception("code " + e));
                }
            }, bg);
        }
    }

    void closeCam() {
        try { if (sess != null) sess.close(); } catch (Exception ignored) { }
        sess = null;
        try { if (dev != null) dev.close(); } catch (Exception ignored) { }
        dev = null;
        try { if (jpeg != null) jpeg.close(); } catch (Exception ignored) { }
        jpeg = null;
        try { if (rawReader != null) rawReader.close(); } catch (Exception ignored) { }
        rawReader = null;
        try { if (snap != null) snap.close(); } catch (Exception ignored) { }
        snap = null;
    }

    void startPreview() {
        if (dev == null) return;
        try {
            try { if (sess != null) sess.close(); } catch (Exception ignored) { }
            sess = null;
            SurfaceTexture st = tex.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(prevSize.getWidth(), prevSize.getHeight());
            if (prevSurface != null) prevSurface.release();
            prevSurface = new Surface(st);
            List<Surface> outs = new ArrayList<>();
            outs.add(prevSurface);
            if (!videoMode && photoSize != null) {
                if (jpeg != null) jpeg.close();
                jpeg = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 2);
                jpeg.setOnImageAvailableListener(r -> {
                    try (Image img = r.acquireNextImage()) {
                        if (img == null) return;
                        ByteBuffer buf = img.getPlanes()[0].getBuffer();
                        byte[] data = new byte[buf.remaining()];
                        buf.get(data);
                        savePhoto(data);
                    } catch (Exception e) { toast("Photo non enregistrée"); }
                }, bg);
                outs.add(jpeg.getSurface());
                if (rawOn && rawCapable()) {
                    Size rs = rawSize();
                    if (rs != null) {
                        if (rawReader != null) rawReader.close();
                        rawReader = ImageReader.newInstance(rs.getWidth(), rs.getHeight(), ImageFormat.RAW_SENSOR, 2);
                        rawReader.setOnImageAvailableListener(r -> {
                            try {
                                Image img = r.acquireNextImage();
                                if (img == null) return;
                                synchronized (rawLock) { rawImages.put(img.getTimestamp(), img); }
                                pairRaw();
                            } catch (Exception ignored) { }
                        }, bg);
                        outs.add(rawReader.getSurface());
                    }
                }
            }
            final boolean triedRaw = rawReader != null && !videoMode;
            dev.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) { sess = s; repeat(); emitState(); }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    if (triedRaw) { rawOn = false; try { rawReader.close(); } catch (Exception ignored) { } rawReader = null; toast("RAW refusé par ce capteur"); startPreview(); }
                    else fallbackCam(camId, new Exception("configuration refusée"));
                }
            }, bg);
        } catch (Throwable e) { toast("Aperçu impossible : " + e.getClass().getSimpleName()); }
    }

    void common(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AF_MODE, videoMode ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        if (Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        else if (active != null && zoom > 1f) {
            int cw = Math.round(active.width() / zoom), chh = Math.round(active.height() / zoom);
            int cx = active.centerX(), cy = active.centerY();
            b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(cx - cw / 2, cy - chh / 2, cx + cw / 2, cy + chh / 2));
        }
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
        if (focusRegion != null) {
            Integer maxAf = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF), maxAe = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            if (maxAf != null && maxAf > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, focusRegion);
            if (maxAe != null && maxAe > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, focusRegion);
        }
        boolean torch = "torch".equals(flash) || (videoMode && "on".equals(flash));
        if (torch) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
        } else if (!videoMode && "auto".equals(flash)) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        } else if (!videoMode && "on".equals(flash)) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        }
        int[] ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (ois != null) for (int m : ois) if (m == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, m);
        if (videoMode) {
            boolean eis = sp.getBoolean("eis", false);
            int[] vs = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            if (vs != null) for (int m : vs) if (m == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eis ? m : CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange(videoFps));
        }
        if (pro) applyPro(b);
    }

    // ------------------------------------------------------------------ MODE PRO (limites réelles du capteur, sans bride)
    boolean pro, rawOn, flat, minimal;
    int isoMan, awb = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    long expMan;
    float focusMan = -1f;
    volatile int lastIso = 100;
    volatile long lastExp = 10_000_000L;
    volatile float lastFocus = 0f;
    long lastLiveEmit;

    boolean hasCap(int cap) {
        int[] caps = ch == null ? null : ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps != null) for (int c : caps) if (c == cap) return true;
        return false;
    }
    boolean manualCapable() { return hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR); }
    boolean rawCapable() { return hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) && rawSize() != null; }
    Size rawSize() {
        StreamConfigurationMap map = ch == null ? null : ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] r = map == null ? null : map.getOutputSizes(ImageFormat.RAW_SENSOR);
        if (r == null || r.length == 0) return null;
        return Collections.max(Arrays.asList(r), (x, y) -> Long.compare(area(x), area(y)));
    }

    void applyPro(CaptureRequest.Builder b) {
        boolean manualExp = manualCapable() && (isoMan > 0 || expMan > 0);
        if (manualExp) {
            Range<Integer> isoR = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> expR = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Long maxFrame = ch.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
            int iso = isoMan > 0 ? isoMan : lastIso;
            long exp = expMan > 0 ? expMan : lastExp;
            if (isoR != null) iso = Math.max(isoR.getLower(), Math.min(isoR.getUpper(), iso));
            if (expR != null) exp = Math.max(expR.getLower(), Math.min(expR.getUpper(), exp));
            if (videoMode) exp = Math.min(exp, 1_000_000_000L / Math.max(1, videoFps));
            long frame = videoMode ? 1_000_000_000L / Math.max(1, videoFps) : Math.max(exp, 33_333_333L);
            if (maxFrame != null) frame = Math.min(frame, maxFrame);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.FLASH_MODE, "torch".equals(flash) ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frame);
        }
        if (focusMan >= 0 && manualCapable()) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusMan);
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, awb);
        if (minimal) {
            int[] nr = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
            if (nr != null) for (int m : nr) if (m == CameraMetadata.NOISE_REDUCTION_MODE_OFF) b.set(CaptureRequest.NOISE_REDUCTION_MODE, m);
            int[] ed = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
            if (ed != null) for (int m : ed) if (m == CameraMetadata.EDGE_MODE_OFF) b.set(CaptureRequest.EDGE_MODE, m);
        }
        if (videoMode && flat && hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) {
            // profil « plat » (pseudo-log) : ombres relevées, hautes lumières compressées = marge d'étalonnage
            float[] c = {0f, 0.07f, 0.06f, 0.24f, 0.15f, 0.38f, 0.30f, 0.54f, 0.50f, 0.70f, 0.75f, 0.85f, 1f, 0.95f};
            b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
            b.set(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(c, c, c));
        }
    }

    final CameraCaptureSession.CaptureCallback liveCb = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
            Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Float fd = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (iso != null) lastIso = iso;
            if (exp != null) lastExp = exp;
            if (fd != null) lastFocus = fd;
            long now = System.currentTimeMillis();
            if (now - lastLiveEmit > 300) {
                lastLiveEmit = now;
                emit("live", "{\"iso\":" + lastIso + ",\"exp\":" + lastExp + ",\"focus\":" + lastFocus + "}");
            }
        }
    };

    // ------------------------------------------------------------------ RAW (DNG) apparié à sa photo
    final Object rawLock = new Object();
    final Map<Long, Image> rawImages = new HashMap<>();
    final Map<Long, TotalCaptureResult> rawResults = new HashMap<>();
    ImageReader rawReader;

    void pairRaw() {
        List<Image> imgs = new ArrayList<>();
        List<TotalCaptureResult> res = new ArrayList<>();
        synchronized (rawLock) {
            for (Long ts : new ArrayList<>(rawImages.keySet())) {
                TotalCaptureResult r = rawResults.remove(ts);
                if (r != null) { imgs.add(rawImages.remove(ts)); res.add(r); }
            }
        }
        for (int i = 0; i < imgs.size(); i++) saveDng(imgs.get(i), res.get(i));
    }

    void saveDng(Image img, TotalCaptureResult res) {
        try (DngCreator dng = new DngCreator(ch, res)) {
            int o = jpegOrientation();
            dng.setOrientation(o == 90 ? ExifInterface.ORIENTATION_ROTATE_90 : o == 180 ? ExifInterface.ORIENTATION_ROTATE_180 : o == 270 ? ExifInterface.ORIENTATION_ROTATE_270 : ExifInterface.ORIENTATION_NORMAL);
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, "PUP_" + stamp() + "_RAW.dng");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PuppyPhone/RAW");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = getContentResolver();
            Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            try (ParcelFileDescriptor pf = cr.openFileDescriptor(u, "w"); FileOutputStream out = new FileOutputStream(pf.getFileDescriptor())) {
                dng.writeImage(out, img);
                out.flush();
                out.getFD().sync();
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, v, null, null);
            emit("raw", "");
        } catch (Exception e) {
            toast("DNG non enregistré : " + e.getMessage());
        } finally {
            try { img.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ photo pendant le tournage
    ImageReader snap;
    boolean snapOk;

    void snapshot() {
        if (!recording || sess == null || snap == null || !snapOk) { toast("Photo pendant le tournage non disponible ici"); return; }
        try {
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT);
            b.addTarget(prevSurface);
            b.addTarget(rec.getSurface());
            b.addTarget(snap.getSurface());
            common(b);
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
            sess.capture(b.build(), null, bg);
            sound.play(MediaActionSound.SHUTTER_CLICK);
            emit("shutter", "");
        } catch (Exception e) { toast("Photo ratée"); }
    }

    void recSession(boolean withSnap, boolean codecH, int brF) throws CameraAccessException {
        List<Surface> outs = new ArrayList<>();
        outs.add(prevSurface);
        outs.add(rec.getSurface());
        snapOk = false;
        if (withSnap) {
            if (snap != null) snap.close();
            snap = ImageReader.newInstance(videoSize.getWidth(), videoSize.getHeight(), ImageFormat.JPEG, 2);
            snap.setOnImageAvailableListener(r -> {
                try (Image img = r.acquireNextImage()) {
                    if (img == null) return;
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    boolean ci = captureIntent;
                    captureIntent = false;
                    savePhoto(data);
                    captureIntent = ci;
                } catch (Exception ignored) { }
            }, bg);
            outs.add(snap.getSurface());
        }
        try { if (sess != null) sess.close(); } catch (Exception ignored) { }
        sess = null;
        dev.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession s) {
                sess = s;
                snapOk = withSnap;
                recording = true;
                recPaused = false;
                recPausedTotal = 0;
                repeat();
                try {
                    rec.start();
                    recStart = System.currentTimeMillis();
                    if (tsMode && syncTick != null && bg != null) bg.postDelayed(syncTick, 1000);
                    if (voiceStarted) { bell(); voiceStarted = false; }
                    else if (sp.getBoolean("sound", true)) sound.play(MediaActionSound.START_VIDEO_RECORDING);
                    try {
                        JSONObject o = new JSONObject();
                        o.put("q", qualityLabel());
                        o.put("codec", (codecH ? "HEVC" : "H.264") + (tsMode ? " · TS" : ""));
                        o.put("ts", tsMode);
                        o.put("mbps", brF / 1_000_000);
                        o.put("snap", withSnap);
                        emit("rec", o.toString());
                    } catch (Exception ignored) { }
                } catch (Exception e) { failRec("Le téléphone refuse cet enregistrement"); }
            }
            @Override public void onConfigureFailed(CameraCaptureSession s) {
                if (withSnap) { try { recSession(false, codecH, brF); } catch (Exception e) { failRec("Configuration refusée"); } }
                else failRec("Configuration " + qualityLabel() + " refusée");
            }
        }, bg);
    }

    // ------------------------------------------------------------------ déclencheur vocal FR / EN / ES
    SpeechRecognizer sr;
    boolean voiceOn, voiceBusy, voiceStarted;
    long voiceCooldown;
    static final String[] W_PHOTO = {"photo", "foto", "picture", "pic", "cheese", "ouistiti", "clic", "click", "patata", "shoot"};
    static final String[] W_FILM = {"film", "filme", "filmer", "filma", "video", "vidéo", "record", "enregistre", "graba", "grabar", "action", "acción", "accion", "moteur", "rec"};
    static final String[] W_FLASH = {"flash", "lumière", "lumiere", "light", "luz", "lampe", "torche", "torch", "linterna", "luces"};

    void voiceStart() {
        ui.post(() -> {
            if (!voiceOn || recording || voiceBusy) return;
            if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("Reconnaissance vocale indisponible sur ce téléphone"); voiceOn = false; emit("voice", "off"); return; }
            if (sr == null) {
                sr = SpeechRecognizer.createSpeechRecognizer(this);
                sr.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle p) { emit("voice", "listen"); }
                    @Override public void onBeginningOfSpeech() { emit("voice", "hear"); }
                    @Override public void onRmsChanged(float v) { }
                    @Override public void onBufferReceived(byte[] b) { }
                    @Override public void onEndOfSpeech() { }
                    @Override public void onError(int e) { voiceBusy = false; ui.postDelayed(CameraActivity.this::voiceStart, e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1200 : 250); }
                    @Override public void onResults(Bundle r) { voiceBusy = false; handleWords(r); ui.postDelayed(CameraActivity.this::voiceStart, 200); }
                    @Override public void onPartialResults(Bundle r) { handleWords(r); }
                    @Override public void onEvent(int t, Bundle p) { }
                });
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, sp.getString("vlang", Locale.getDefault().toLanguageTag()));
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L);
            try { voiceBusy = true; sr.startListening(i); } catch (Exception e) { voiceBusy = false; }
        });
    }

    void voiceStop() {
        ui.post(() -> {
            voiceBusy = false;
            if (sr != null) { try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { } sr = null; }
        });
    }

    static boolean hasWord(String text, String[] words) {
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ") + " ";
        for (String w : words) if (t.contains(" " + w + " ")) return true;
        return false;
    }

    void handleWords(Bundle r) {
        ArrayList<String> l = r == null ? null : r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (l == null || l.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now < voiceCooldown) return;
        String all = String.join(" ", l);
        if (hasWord(all, W_FLASH)) {
            voiceCooldown = now + 1500;
            flash = "torch".equals(flash) ? "off" : "torch";
            sp.edit().putString("flash", flash).apply();
            if (bg != null) bg.post(this::repeat);
            sound.play(MediaActionSound.FOCUS_COMPLETE);
            emit("voicecmd", "flash");
            emitState();
        } else if (hasWord(all, W_FILM)) {
            voiceCooldown = now + 3000;
            emit("voicecmd", "film");
            voiceStarted = true;
            voiceStop();
            if (!videoMode) {
                videoMode = true;
                sp.edit().putBoolean("video", true).apply();
                ui.post(() -> { chooseSizes(); layoutPreview(); emitState(); });
                if (bg != null) { bg.post(this::startPreview); bg.postDelayed(this::startRec, 1200); }
            } else if (bg != null) bg.post(this::startRec);
        } else if (hasWord(all, W_PHOTO)) {
            voiceCooldown = now + 1500;
            emit("voicecmd", "photo");
            if (videoMode && !recording) {
                videoMode = false;
                sp.edit().putBoolean("video", false).apply();
                ui.post(() -> { chooseSizes(); layoutPreview(); emitState(); });
                if (bg != null) { bg.post(this::startPreview); bg.postDelayed(this::voicePhoto, 1200); }
            } else if (bg != null) bg.post(this::voicePhoto);
        }
    }

    void voicePhoto() {
        boolean s0 = sp.getBoolean("sound", true);
        sp.edit().putBoolean("sound", true).apply(); // le clic confirme toujours la commande vocale
        takePhoto();
        if (!s0) bg.postDelayed(() -> sp.edit().putBoolean("sound", false).apply(), 300);
    }

    /** Son de cloche synthétisé : confirme que le tournage a démarré. */
    void bell() {
        new Thread(() -> {
            try {
                int rate = 44100, n = (int) (rate * 1.4);
                short[] pcm = new short[n];
                double[] f = {880, 1318.5, 1760, 2637};
                double[] a = {0.55, 0.3, 0.18, 0.08};
                for (int i = 0; i < n; i++) {
                    double t = i / (double) rate, v = 0;
                    for (int k = 0; k < f.length; k++) v += a[k] * Math.sin(2 * Math.PI * f[k] * t) * Math.exp(-t * (2.2 + k * 1.4));
                    double atk = Math.min(1, t / 0.004);
                    pcm[i] = (short) Math.max(-32767, Math.min(32767, v * atk * 26000));
                }
                AudioTrack at = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
                at.write(pcm, 0, n);
                at.play();
                Thread.sleep(1600);
                at.release();
            } catch (Exception ignored) { }
        }, "pupcam-bell").start();
    }

    Range<Integer> fpsRange(int fps) {
        Range<Integer>[] rs = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Range<Integer> best = null;
        if (rs != null) for (Range<Integer> r : rs) {
            if (r.getUpper() == fps && r.getLower() == fps) return r;
            if (r.getUpper() == fps && (best == null || r.getLower() > best.getLower())) best = r;
        }
        return best != null ? best : new Range<>(fps, fps);
    }

    void repeat() {
        if (sess == null || dev == null) return;
        try {
            prevB = dev.createCaptureRequest(videoMode ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            prevB.addTarget(prevSurface);
            if (recording && rec != null) prevB.addTarget(rec.getSurface());
            common(prevB);
            sess.setRepeatingRequest(prevB.build(), liveCb, bg);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ photo
    int jpegOrientation() {
        int d = front ? -devOrient : devOrient;
        return (sensorOrient + d + 360) % 360;
    }

    void takePhoto() {
        if (sess == null || dev == null || jpeg == null || videoMode) return;
        try {
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(jpeg.getSurface());
            b.addTarget(prevSurface);
            final boolean withRaw = rawReader != null;
            if (withRaw) b.addTarget(rawReader.getSurface());
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
            b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
            b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
            b.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY);
            common(b);
            // zoom net : on capture tout le capteur puis on recadre nous-mêmes (pas de pixels inventés)
            boolean crop = sp.getBoolean("cropzoom", true) && zoom > 1.05f && Build.VERSION.SDK_INT >= 30;
            if (crop) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.max(zoomRange.getLower(), 1f));
            cropQueue.add(crop ? zoom : 1f);
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            b.set(CaptureRequest.JPEG_QUALITY, (byte) sp.getInt("jpegq", 100));
            if (withRaw) b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            final CameraCaptureSession.CaptureCallback stillCb = new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    if (!withRaw) return;
                    Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (ts == null) return;
                    synchronized (rawLock) { rawResults.put(ts, res); }
                    pairRaw();
                }
            };
            Runnable go = () -> {
                try {
                    sess.capture(b.build(), stillCb, bg);
                    if (sp.getBoolean("sound", true)) sound.play(MediaActionSound.SHUTTER_CLICK);
                    emit("shutter", "");
                } catch (Exception e) { toast("Capture ratée"); }
            };
            if ("auto".equals(flash) || "on".equals(flash)) {
                prevB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                sess.capture(prevB.build(), null, bg);
                prevB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                bg.postDelayed(go, 450);
            } else go.run();
        } catch (Exception e) { toast("Capture ratée"); }
    }

    String stamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(new Date()); }

    final java.util.concurrent.ConcurrentLinkedQueue<Float> cropQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Recadrage centré à la résolution native du capteur (aucun agrandissement artificiel). */
    byte[] cropJpeg(byte[] data, float z) {
        try {
            int orient = new ExifInterface(new ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            android.graphics.BitmapRegionDecoder d = android.graphics.BitmapRegionDecoder.newInstance(data, 0, data.length, false);
            int W = d.getWidth(), H = d.getHeight();
            int cw = Math.round(W / z), chh = Math.round(H / z);
            Bitmap bm = d.decodeRegion(new Rect((W - cw) / 2, (H - chh) / 2, (W + cw) / 2, (H + chh) / 2), null);
            d.recycle();
            int deg = orient == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : orient == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : orient == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (deg != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(deg);
                Bitmap r2 = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                bm.recycle();
                bm = r2;
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 97, o);
            bm.recycle();
            return o.toByteArray();
        } catch (Throwable t) {
            return data;
        }
    }

    void savePhoto(byte[] data) {
        try {
            Float cz = cropQueue.poll();
            if (cz != null && cz > 1.05f) data = cropJpeg(data, cz);
            if (captureIntent) { returnCapture(data); return; }
            ContentResolver cr = getContentResolver();
            String name = "PUP_" + stamp() + ".jpg";
            Uri u;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PuppyPhone");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                try (ParcelFileDescriptor pf = cr.openFileDescriptor(u, "w"); FileOutputStream o = new FileOutputStream(pf.getFileDescriptor())) { o.write(data); o.flush(); o.getFD().sync(); }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(u, v, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "PuppyPhone");
                dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DATA, f.getAbsolutePath());
                v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            }
            lastUri = u;
            lastKind = "i";
            lastThumb = null;
            emit("saved", "i");
        } catch (Exception e) { toast("Photo non enregistrée : " + e.getMessage()); }
    }

    void returnCapture(byte[] data) {
        ui.post(() -> {
            try {
                if (captureOut != null) {
                    try (OutputStream o = getContentResolver().openOutputStream(captureOut)) { o.write(data); }
                    setResult(RESULT_OK);
                } else {
                    Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                    Bitmap small = Bitmap.createScaledBitmap(bm, bm.getWidth() / 8, bm.getHeight() / 8, true);
                    setResult(RESULT_OK, new Intent().putExtra("data", small));
                }
            } catch (Exception e) { setResult(RESULT_CANCELED); }
            finish();
        });
    }

    // ------------------------------------------------------------------ vidéo
    void startRec() {
        if (dev == null || recording) return;
        try {
            tsMode = "ts".equals(sp.getString("container", "mp4")) && !(videoCaptureIntent && captureOut != null) && Build.VERSION.SDK_INT >= 29;
            // le TS (blindé) n'accepte que le H.264
            boolean useHevc = !tsMode && hevc && encoderOk(MediaFormat.MIMETYPE_VIDEO_HEVC, videoSize, videoFps);
            long px = area(videoSize);
            int br;
            if (px >= 7680L * 4320) br = useHevc ? 80_000_000 : 100_000_000;
            else if (px >= 3840L * 2160) br = videoFps >= 60 ? (useHevc ? 64_000_000 : 85_000_000) : (useHevc ? 40_000_000 : 56_000_000);
            else br = videoFps >= 60 ? (useHevc ? 22_000_000 : 28_000_000) : (useHevc ? 14_000_000 : 17_000_000);
            if (sp.getBoolean("maxbr", true)) br = Math.round(br * 1.25f);

            rec = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            rec.setOutputFormat(tsMode ? MediaRecorder.OutputFormat.MPEG_2_TS : MediaRecorder.OutputFormat.MPEG_4);
            String name = "PUP_" + stamp() + ".mp4";
            if (tsMode) {
                // mode blindé : un seul fichier .ts lisible jusqu'à la dernière fraction de seconde,
                // visible dès le départ et forcé sur le stockage chaque seconde
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, "PUP_" + stamp() + ".ts");
                v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp2t");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PuppyPhone");
                recUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                recPfd = getContentResolver().openFileDescriptor(recUri, "w");
                rec.setOutputFile(recPfd.getFileDescriptor());
                final ParcelFileDescriptor syncFd = recPfd;
                syncTick = new Runnable() {
                    @Override public void run() {
                        if (!recording || recPfd != syncFd) return;
                        try { Os.fsync(syncFd.getFileDescriptor()); } catch (Exception ignored) { }
                        if (bg != null) bg.postDelayed(this, 1000);
                    }
                };
            } else if (videoCaptureIntent && captureOut != null) {
                recPfd = getContentResolver().openFileDescriptor(captureOut, "w");
                rec.setOutputFile(recPfd.getFileDescriptor());
            } else if (Build.VERSION.SDK_INT >= 29) {
                // sauvegarde en temps réel : un petit fichier complet toutes les SEG_SEC secondes
                segmented = true;
                recBase = "PUP_" + stamp();
                parts.clear();
                partPfds.clear();
                finalized = 0;
                Uri u0 = newPart();
                ParcelFileDescriptor p0 = getContentResolver().openFileDescriptor(u0, "w");
                partPfds.add(p0);
                rec.setOutputFile(p0.getFileDescriptor());
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "PuppyPhone");
                dir.mkdirs();
                recFile = new File(dir, name);
                rec.setOutputFile(recFile.getAbsolutePath());
            }
            rec.setVideoEncodingBitRate(br);
            rec.setVideoFrameRate(videoFps);
            rec.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            rec.setVideoEncoder(useHevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioEncodingBitRate(256_000);
            rec.setAudioSamplingRate(48_000);
            rec.setAudioChannels(2);
            recOrient = jpegOrientation();
            rec.setOrientationHint(recOrient);
            if (segmented) {
                rec.setMaxFileSize((long) br / 8 * SEG_SEC + 256_000L / 8 * SEG_SEC + 400_000);
                rec.setOnInfoListener((mr, what, extra) -> {
                    try {
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING) {
                            Uri nu = newPart();
                            ParcelFileDescriptor np = getContentResolver().openFileDescriptor(nu, "w");
                            partPfds.add(np);
                            mr.setNextOutputFile(np.getFileDescriptor());
                        } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED) {
                            finalizePart(finalized++);
                            emit("seg", String.valueOf(finalized));
                        }
                    } catch (Exception ignored) { }
                });
            }
            rec.prepare();
            final boolean codecH = useHevc;
            final int brF = br;
            if (voiceOn) voiceStop();
            recSession(true, codecH, brF);
        } catch (Exception e) {
            failRec("Enregistrement impossible : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ sauvegarde en temps réel (segments)
    static final int SEG_SEC = 5;
    boolean tsMode;
    Runnable syncTick;
    boolean segmented;
    String recBase;
    int recOrient, finalized;
    final List<Uri> parts = new ArrayList<>();
    final List<ParcelFileDescriptor> partPfds = new ArrayList<>();

    Uri newPart() {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, recBase + "_part" + String.format(Locale.ROOT, "%03d", parts.size() + 1) + ".mp4");
        v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PuppyPhone");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        parts.add(u);
        return u;
    }

    /** Segment terminé : écrit physiquement sur le stockage puis rendu visible (survit à une coupure). */
    void finalizePart(int i) {
        if (i < 0 || i >= partPfds.size()) return;
        ParcelFileDescriptor p = partPfds.get(i);
        try { Os.fsync(p.getFileDescriptor()); } catch (Exception ignored) { }
        try { p.close(); } catch (Exception ignored) { }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(parts.get(i), v, null, null);
        } catch (Exception ignored) { }
    }

    /** Recolle les segments en une seule vidéo, sans réencodage (aucune perte de qualité). */
    boolean mergeParts(List<Uri> src, Uri out) {
        ParcelFileDescriptor pfd = null;
        MediaMuxer mux = null;
        try {
            pfd = getContentResolver().openFileDescriptor(out, "rw");
            mux = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mux.setOrientationHint(recOrient);
            MediaExtractor e0 = new MediaExtractor();
            e0.setDataSource(this, src.get(0), null);
            int n = e0.getTrackCount();
            String[] mimes = new String[n];
            int[] dst = new int[n];
            for (int i = 0; i < n; i++) {
                MediaFormat f = e0.getTrackFormat(i);
                mimes[i] = f.getString(MediaFormat.KEY_MIME);
                dst[i] = mux.addTrack(f);
            }
            e0.release();
            mux.start();
            ByteBuffer buf = ByteBuffer.allocateDirect(24 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long offset = 0;
            for (int k = 0; k < src.size(); k++) {
                MediaExtractor ex = new MediaExtractor();
                ex.setDataSource(this, src.get(k), null);
                int[] map = new int[ex.getTrackCount()];
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    map[i] = -1;
                    for (int j = 0; j < n; j++) if (mimes[j].equals(m)) { map[i] = dst[j]; break; }
                    if (map[i] >= 0) ex.selectTrack(i);
                }
                long start = ex.getSampleTime();
                if (start < 0) start = 0;
                long maxPts = 0;
                while (true) {
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) break;
                    int ti = ex.getSampleTrackIndex();
                    long pts = ex.getSampleTime() - start;
                    if (pts < 0) pts = 0;
                    if (ti >= 0 && map[ti] >= 0) {
                        info.set(0, size, pts + offset, (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        mux.writeSampleData(map[ti], buf, info);
                    }
                    if (pts > maxPts) maxPts = pts;
                    ex.advance();
                }
                ex.release();
                offset += maxPts + 1_000_000L / Math.max(1, videoFps);
                emit("merge", String.valueOf(Math.round((k + 1) * 100f / src.size())));
            }
            mux.stop();
            mux.release();
            mux = null;
            try { Os.fsync(pfd.getFileDescriptor()); } catch (Exception ignored) { }
            pfd.close();
            return true;
        } catch (Exception e) {
            try { if (mux != null) mux.release(); } catch (Exception ignored) { }
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
            return false;
        }
    }

    void finishSegments(boolean ok) {
        for (int i = finalized; i < partPfds.size(); i++) finalizePart(i);
        finalized = partPfds.size();
        final List<Uri> src = new ArrayList<>(parts);
        segmented = false;
        if (!ok || src.isEmpty()) return;
        if (src.size() == 1) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, recBase + ".mp4");
                getContentResolver().update(src.get(0), v, null, null);
            } catch (Exception ignored) { }
            lastUri = src.get(0);
            lastKind = "v";
            lastThumb = null;
            emit("saved", "v");
            return;
        }
        final String base = recBase;
        new Thread(() -> {
            Uri out = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, base + ".mp4");
                v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PuppyPhone");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                out = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            } catch (Exception ignored) { }
            if (out != null && mergeParts(src, out)) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(out, v, null, null);
                for (Uri u : src) try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
                lastUri = out;
            } else {
                if (out != null) try { getContentResolver().delete(out, null, null); } catch (Exception ignored) { }
                lastUri = src.get(src.size() - 1);
                toast("Assemblage impossible : ta vidéo est gardée en " + src.size() + " morceaux dans DCIM/PuppyPhone");
            }
            lastKind = "v";
            lastThumb = null;
            emit("saved", "v");
            emit("merge", "done");
        }, "pupcam-merge").start();
    }

    String qualityLabel() {
        String r = videoSize.getHeight() >= 4320 ? "8K" : videoSize.getHeight() >= 2160 ? "4K" : videoSize.getHeight() >= 1080 ? "1080p" : videoSize.getHeight() + "p";
        return r + " · " + videoFps + " i/s";
    }

    void failRec(String msg) {
        recording = false;
        releaseRec(true);
        // repli automatique : 60 → 30 i/s pour ne jamais rater le moment
        if (videoFps >= 60) {
            videoFps = 30;
            toast(msg + " → je passe en " + qualityLabel());
        } else toast(msg);
        emit("recstop", "");
        startPreview();
    }

    void releaseRec(boolean deleteFile) {
        try { if (rec != null) rec.reset(); } catch (Exception ignored) { }
        try { if (rec != null) rec.release(); } catch (Exception ignored) { }
        rec = null;
        try { if (recPfd != null) recPfd.close(); } catch (Exception ignored) { }
        recPfd = null;
        if (deleteFile && segmented) {
            for (ParcelFileDescriptor p : partPfds) try { p.close(); } catch (Exception ignored) { }
            for (Uri u : parts) try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
            parts.clear();
            partPfds.clear();
            segmented = false;
        }
        if (deleteFile) {
            try { if (recUri != null) getContentResolver().delete(recUri, null, null); } catch (Exception ignored) { }
            if (recFile != null) recFile.delete();
            recUri = null;
            recFile = null;
        }
    }

    void stopRec() {
        if (!recording) return;
        recording = false;
        boolean ok = true;
        try { rec.stop(); } catch (Exception e) { ok = false; }
        // en mode blindé, même un arrêt raté laisse un fichier lisible : on le garde
        if (!ok && tsMode && recUri != null) { ok = true; }
        if (sp.getBoolean("sound", true)) sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
        if (segmented) {
            try { if (rec != null) rec.release(); } catch (Exception ignored) { }
            rec = null;
            // même si stop() échoue, les segments déjà terminés sont gardés
            if (!ok && parts.size() > finalized) {
                int last = parts.size() - 1;
                try { partPfds.get(last).close(); } catch (Exception ignored) { }
                try { getContentResolver().delete(parts.get(last), null, null); } catch (Exception ignored) { }
                parts.remove(last);
                partPfds.remove(last);
            }
            finishSegments(true);
            emit("recstop", "");
            if (voiceOn) ui.postDelayed(this::voiceStart, 800);
            if (dev != null) startPreview();
            return;
        }
        Uri u = recUri;
        File f = recFile;
        releaseRec(!ok);
        try {
            if (ok && u != null && tsMode) {
                lastUri = u;
            } else if (ok && u != null && Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(u, v, null, null);
                lastUri = u;
            } else if (ok && f != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DATA, f.getAbsolutePath());
                v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                lastUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            }
        } catch (Exception ignored) { }
        recUri = null;
        recFile = null;
        if (ok) { lastKind = "v"; lastThumb = null; emit("saved", "v"); }
        emit("recstop", "");
        if (voiceOn) ui.postDelayed(this::voiceStart, 800);
        final boolean okF = ok;
        if (videoCaptureIntent) { ui.post(() -> { setResult(okF ? RESULT_OK : RESULT_CANCELED, captureOut == null && lastUri != null ? new Intent().setData(lastUri) : null); finish(); }); return; }
        if (dev != null) startPreview();
    }

    // ------------------------------------------------------------------ miniature de la dernière prise
    byte[] lastThumb;

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        try {
            if (p.equals("/last")) {
                if (lastThumb == null && lastUri != null) {
                    Bitmap bm = Build.VERSION.SDK_INT >= 29 ? getContentResolver().loadThumbnail(lastUri, new Size(240, 240), null) : null;
                    if (bm != null) {
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        bm.compress(Bitmap.CompressFormat.JPEG, 85, o);
                        lastThumb = o.toByteArray();
                    }
                }
                if (lastThumb == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Vide", h, new ByteArrayInputStream(new byte[0]));
                return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(lastThumb));
            }
            String path = p.equals("/") ? "/camera.html" : p;
            InputStream in = getAssets().open("www" + path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    void emitState() {
        ui.post(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("cam", camId);
                o.put("front", front);
                o.put("video", videoMode);
                o.put("zoom", zoom);
                o.put("zmin", zoomRange.getLower());
                o.put("zmax", zoomRange.getUpper());
                o.put("flash", flash);
                Boolean fl = ch == null ? null : ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                o.put("hasFlash", fl != null && fl);
                o.put("ratio", sp.getString("ratio", "4:3"));
                o.put("quality", qualityLabel());
                o.put("qualities", videoQualities());
                o.put("qsel", sp.getString("quality", "4k60"));
                o.put("hevc", hevc);
                o.put("eis", sp.getBoolean("eis", false));
                o.put("sound", sp.getBoolean("sound", true));
                o.put("volkey", sp.getBoolean("volkey", true));
                o.put("grid", sp.getBoolean("grid", false));
                o.put("maxbr", sp.getBoolean("maxbr", true));
                o.put("cropzoom", sp.getBoolean("cropzoom", true));
                o.put("container", sp.getString("container", "mp4"));
                o.put("photo", photoSize == null ? "" : photoSize.getWidth() + "×" + photoSize.getHeight());
                o.put("mp", photoSize == null ? 0 : Math.round(area(photoSize) / 1e5) / 10.0);
                Range<Integer> evr = ch == null ? null : ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                o.put("evmin", evr == null ? 0 : evr.getLower());
                o.put("evmax", evr == null ? 0 : evr.getUpper());
                o.put("ev", ev);
                o.put("hasLast", lastUri != null);
                o.put("lastKind", lastKind);
                o.put("capture", captureIntent || videoCaptureIntent);
                o.put("pro", pro);
                o.put("manual", manualCapable());
                o.put("rawOk", rawCapable());
                o.put("raw", rawOn && rawReader != null);
                o.put("flat", flat);
                o.put("minimal", minimal);
                o.put("flatOk", hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING));
                o.put("voice", voiceOn);
                o.put("isoMan", isoMan);
                o.put("expMan", expMan);
                o.put("focusMan", focusMan);
                o.put("awb", awb);
                Range<Integer> isoR = ch == null ? null : ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                Range<Long> expR = ch == null ? null : ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                Float minF = ch == null ? null : ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                Integer maxAnalog = ch == null ? null : ch.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
                o.put("isoMin", isoR == null ? 0 : isoR.getLower());
                o.put("isoMax", isoR == null ? 0 : isoR.getUpper());
                o.put("isoAnalog", maxAnalog == null ? 0 : maxAnalog);
                o.put("expMin", expR == null ? 0 : expR.getLower());
                o.put("expMax", expR == null ? 0 : expR.getUpper());
                o.put("focusMin", minF == null ? 0 : minF);
                int[] awbs = ch == null ? null : ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
                JSONArray aw = new JSONArray();
                if (awbs != null) for (int m : awbs) aw.put(m);
                o.put("awbModes", aw);
                Size rs = ch == null ? null : rawSize();
                o.put("rawRes", rs == null ? "" : rs.getWidth() + "×" + rs.getHeight());
                web.evaluateJavascript("window.CamUI&&CamUI.on('state'," + JSONObject.quote(o.toString()) + ")", null);
            } catch (Exception ignored) { }
        });
    }

    // ------------------------------------------------------------------ pont
    class Bridge {
        @JavascriptInterface public String lenses() { return lensesJson(); }
        @JavascriptInterface public boolean perms() { return hasPerms(); }
        @JavascriptInterface public void askPerms() { ui.post(CameraActivity.this::askPerms); }

        @JavascriptInterface public void shutter() {
            if (bg == null) return;
            bg.post(() -> {
                if (videoMode) { if (recording) stopRec(); else startRec(); }
                else takePhoto();
            });
        }

        @JavascriptInterface public void pauseRec() {
            if (bg == null) return;
            bg.post(() -> {
                if (!recording || rec == null) return;
                try {
                    if (recPaused) { rec.resume(); recPausedTotal += System.currentTimeMillis() - recPausedAt; recPaused = false; }
                    else { rec.pause(); recPausedAt = System.currentTimeMillis(); recPaused = true; }
                    emit("paused", recPaused ? "1" : "0");
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public long recTime() {
            if (!recording) return 0;
            long now = recPaused ? recPausedAt : System.currentTimeMillis();
            return now - recStart - recPausedTotal;
        }

        @JavascriptInterface public void setMode(boolean video) {
            ui.post(() -> {
                if (recording || video == videoMode || (captureIntent && video)) return;
                videoMode = video;
                sp.edit().putBoolean("video", video).apply();
                if (dev != null) { chooseSizes(); layoutPreview(); bg.post(CameraActivity.this::startPreview); }
                emitState();
            });
        }

        @JavascriptInterface public void selectCam(String id, float z) {
            ui.post(() -> {
                if (recording) return;
                if (id.equals(camId) && dev != null) { setZoomNow(z); return; }
                openCam(id);
                if (z > 0) zoom = Math.max(zoomRange.getLower(), Math.min(zoomRange.getUpper(), z));
                emitState();
            });
        }

        @JavascriptInterface public void zoom(float z) { ui.post(() -> setZoomNow(z)); }

        @JavascriptInterface public void flash(String f) { flash = f; sp.edit().putString("flash", f).apply(); if (bg != null) bg.post(CameraActivity.this::repeat); emitState(); }

        @JavascriptInterface public void ev(int v) { ev = v; if (bg != null) bg.post(CameraActivity.this::repeat); }

        /** Mise au point au toucher : x,y normalisés dans l'aperçu (portrait). */
        @JavascriptInterface public void focus(float x, float y) {
            if (bg == null || active == null) return;
            bg.post(() -> {
                float sx, sy;
                switch (sensorOrient) {
                    case 90: sx = y; sy = 1 - x; break;
                    case 270: sx = 1 - y; sy = x; break;
                    case 180: sx = 1 - x; sy = 1 - y; break;
                    default: sx = x; sy = y;
                }
                if (front && sensorOrient == 270) sy = 1 - sy;
                int half = Math.max(active.width(), active.height()) / 14;
                int cx = Math.round(sx * active.width()), cy = Math.round(sy * active.height());
                Rect r = new Rect(Math.max(0, cx - half), Math.max(0, cy - half), Math.min(active.width(), cx + half), Math.min(active.height(), cy + half));
                focusRegion = new MeteringRectangle[]{new MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)};
                try {
                    common(prevB);
                    prevB.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                    prevB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    sess.capture(prevB.build(), null, bg);
                    prevB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                    sess.setRepeatingRequest(prevB.build(), null, bg);
                } catch (Exception ignored) { }
                // retour à l'autofocus continu après 5 s
                bg.postDelayed(() -> { focusRegion = null; repeat(); }, 5000);
            });
        }

        @JavascriptInterface public void setPref(String k, String v) {
            ui.post(() -> {
                SharedPreferences.Editor e = sp.edit();
                if (k.equals("ratio") || k.equals("quality") || k.equals("container")) e.putString(k, v);
                else e.putBoolean(k, "true".equals(v));
                e.apply();
                if (k.equals("hevc")) hevc = "true".equals(v);
                if (!recording && dev != null && (k.equals("ratio") || k.equals("quality") || k.equals("eis"))) {
                    chooseSizes();
                    layoutPreview();
                    bg.post(CameraActivity.this::startPreview);
                }
                emitState();
            });
        }

        @JavascriptInterface public void openLast() {
            ui.post(() -> {
                if (lastUri == null) return;
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(lastUri, "v".equals(lastKind) ? "video/*" : "image/*");
                i.setClass(CameraActivity.this, "v".equals(lastKind) ? VideoActivity.class : GalleryActivity.class);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(i); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void gallery() { ui.post(() -> startActivity(new Intent(CameraActivity.this, GalleryActivity.class))); }
        @JavascriptInterface public void haptic() { ui.post(() -> web.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)); }
        @JavascriptInterface public void state() { emitState(); }
        @JavascriptInterface public void thermal() { emitThermal(); }

        @JavascriptInterface public void setPro(String k, String v) {
            ui.post(() -> {
                try {
                    switch (k) {
                        case "pro": pro = "true".equals(v); break;
                        case "iso": isoMan = Integer.parseInt(v); break;
                        case "exp": expMan = Long.parseLong(v); break;
                        case "focus": focusMan = Float.parseFloat(v); break;
                        case "awb": awb = Integer.parseInt(v); break;
                        case "flat": flat = "true".equals(v); break;
                        case "minimal": minimal = "true".equals(v); break;
                        case "raw":
                            rawOn = "true".equals(v);
                            if (!recording && dev != null && !videoMode) bg.post(CameraActivity.this::startPreview);
                            break;
                    }
                } catch (Exception ignored) { }
                if (bg != null && !"raw".equals(k)) bg.post(CameraActivity.this::repeat);
                emitState();
            });
        }

        @JavascriptInterface public void snapshot() { if (bg != null) bg.post(CameraActivity.this::snapshot); }

        @JavascriptInterface public void voice(boolean on) {
            ui.post(() -> {
                voiceOn = on;
                sp.edit().putBoolean("voiceOn", on).apply();
                if (on) voiceStart(); else voiceStop();
                emitState();
            });
        }

        @JavascriptInterface public void voiceLang(String tag) { sp.edit().putString("vlang", tag).apply(); voiceStop(); if (voiceOn) ui.postDelayed(CameraActivity.this::voiceStart, 400); }
    }

    void setZoomNow(float z) {
        zoom = Math.max(zoomRange.getLower(), Math.min(zoomRange.getUpper(), z));
        if (bg != null) bg.post(this::repeat);
    }
}

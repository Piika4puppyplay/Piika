package fr.piika.puppyphone;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;

/**
 * Le volet PuppyPhone : une bande invisible en haut de l'écran (fenêtre d'accessibilité, au-dessus de la barre d'état)
 * attrape le geste « glisser vers le bas » et fait descendre un volet puppyplay au lieu de celui d'Android.
 * Les pop-ups de notifications puppyplay passent par le même chemin, par-dessus le pop-up d'Android.
 * Écran verrouillé : la bande se retire, c'est le volet One UI (et sa sécurité) qui reste aux commandes.
 */
final class PupVolet {
    private PupVolet() { }
    static final String HOST = "pupvolet.local";
    static final Handler h = new Handler(Looper.getMainLooper());
    static AccessibilityService svc;
    static WindowManager wm;
    static View trigger; static WindowManager.LayoutParams tLp; static boolean tTouchable = true, barVisible = true, everVis;
    static FrameLayout shade; static WebView sweb; static WindowManager.LayoutParams sLp; static boolean shadeAdded, open; static long openedAt;
    static WebView pweb; static WindowManager.LayoutParams pLp; static boolean popAdded; static String popKey = "";
    static float dens = 1; static int sbH;
    static boolean torchOn; static String torchId;

    static SharedPreferences sp(Context c) { return Pelage.sp(c); }
    static boolean voletOn(Context c) { return sp(c).getBoolean("voletOn", true); }
    static boolean popOn(Context c) { return sp(c).getBoolean("popOn", true); }

    static final BroadcastReceiver rx = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) { closeNow(); popHideNow(); }
        update();
    } };
    static final Runnable notifL = () -> { emitShade("notifs", ""); if (trigger != null && barreOn()) trigger.invalidate(); };

    static void start(AccessibilityService s) {
        svc = s; wm = s.getSystemService(WindowManager.class);
        dens = s.getResources().getDisplayMetrics().density;
        int id = s.getResources().getIdentifier("status_bar_height", "dimen", "android");
        sbH = Math.max(Math.round(24 * dens), id > 0 ? s.getResources().getDimensionPixelSize(id) : 0);
        IntentFilter f = new IntentFilter(); f.addAction(Intent.ACTION_SCREEN_ON); f.addAction(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_USER_PRESENT);
        try { s.registerReceiver(rx, f); } catch (Exception ignored) { }
        PupNotifs.listeners.add(notifL);
        try {
            CameraManager cm = s.getSystemService(CameraManager.class);
            for (String cid : cm.getCameraIdList()) { Boolean fl = cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE); if (fl != null && fl) { torchId = cid; break; } }
            cm.registerTorchCallback(new CameraManager.TorchCallback() { @Override public void onTorchModeChanged(String cid, boolean on) { if (cid.equals(torchId)) { torchOn = on; emitShade("state", ""); } } }, h);
        } catch (Exception ignored) { }
        update();
    }
    static void stop() {
        if (svc == null) return;
        try { svc.unregisterReceiver(rx); } catch (Exception ignored) { }
        PupNotifs.listeners.remove(notifL);
        removeTrigger(); closeNow(); popHideNow();
        if (sweb != null) { sweb.destroy(); sweb = null; } if (pweb != null) { pweb.destroy(); pweb = null; }
        shade = null; svc = null;
    }

    // ------------------------------------------------------------ bande de déclenchement
    static boolean locked() { try { return svc.getSystemService(KeyguardManager.class).isKeyguardLocked(); } catch (Exception e) { return false; } }
    static boolean awake() { try { return svc.getSystemService(PowerManager.class).isInteractive(); } catch (Exception e) { return true; } }
    static boolean hiddenApp() { String hide = PupNav.prefs(svc).getString("hideIn", ""); String pk = PupNavA11y.fgPkg; return !hide.isEmpty() && !pk.isEmpty() && ("," + hide + ",").contains("," + pk + ","); }

    /** Recalcule si la bande doit attraper le geste (appelée à chaque changement de fenêtre, d'écran ou de réglage). */
    static void update() {
        if (svc == null) return;
        if (!voletOn(svc)) { removeTrigger(); return; }
        if (trigger == null) addTrigger();
        boolean want = awake() && !locked() && !open && barVisible && !hiddenApp() && !PupVeille.showing;
        if (want != tTouchable && trigger != null) {
            tTouchable = want;
            if (want) tLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else tLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { wm.updateViewLayout(trigger, tLp); } catch (Exception ignored) { }
        }
        int[] z = zoneRect();
        if (trigger != null && (tLp.width != z[2] || tLp.height != z[3] || tLp.x != z[0] || tLp.y != z[1] || tLp.gravity != (Gravity.TOP | Gravity.START))) {
            tLp.gravity = Gravity.TOP | Gravity.START; tLp.x = z[0]; tLp.y = z[1]; tLp.width = z[2]; tLp.height = z[3];
            try { wm.updateViewLayout(trigger, tLp); } catch (Exception ignored) { }
        }
        if (trigger != null) trigger.invalidate();
        barLoop();
    }

    /** Zone du geste {x, y, largeur, hauteur} en pixels : préréglages ou mode perso (déplaçable, redimensionnable). */
    static int[] zoneRect() {
        int sw = svc.getResources().getDisplayMetrics().widthPixels;
        SharedPreferences p = sp(svc);
        int x, y, w, hh;
        if (p.getBoolean("voletPerso", false)) {
            hh = Math.round(p.getInt("voletZH", 64) * dens); y = Math.round(p.getInt("voletZY", 0) * dens);
            w = Math.max(Math.round(40 * dens), sw * p.getInt("voletZW", 100) / 100);
            x = Math.round((sw - w) * p.getInt("voletZX", 50) / 100f);
        } else {
            int zone = p.getInt("voletZone", 0);
            hh = zoneH(); y = 0; w = zone == 0 ? sw : sw / 2; x = zone == 1 ? sw - w : 0;
        }
        if (barreOn() && y == 0) hh = Math.max(hh, sbH);
        return new int[]{x, y, w, hh};
    }
    static boolean barreOn() { return svc != null && sp(svc).getBoolean("barrePuppy", false); }
    static long previewUntil;
    /** Montre la zone en rose pendant quelques secondes (réglages en direct). */
    static void previewZone(long ms) { h.post(() -> { previewUntil = android.os.SystemClock.uptimeMillis() + ms; update(); if (trigger != null) trigger.invalidate(); h.postDelayed(() -> { if (trigger != null) trigger.invalidate(); }, ms + 50); }); }

    // ------------------------------------------------------------ « Barre puppy » : la barre d'état en fausse vidéo
    static final Runnable barTick = () -> { if (trigger != null) trigger.invalidate(); barLoop(); };
    static void barLoop() { h.removeCallbacks(barTick); if (barreOn() && trigger != null) h.postDelayed(barTick, 15_000); }
    static android.graphics.Typeface face;
    static final java.util.HashMap<String, android.graphics.Bitmap> iconBmp = new java.util.HashMap<>();
    static android.graphics.Bitmap icon(String pkg) {
        if (iconBmp.containsKey(pkg)) return iconBmp.get(pkg);
        byte[] b = PupNotifs.appIcon(svc, pkg);
        android.graphics.Bitmap bm = b == null ? null : android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
        iconBmp.put(pkg, bm); return bm;
    }
    static class ZoneView extends View {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        ZoneView(Context c) { super(c); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            int W = getWidth(), H = getHeight();
            boolean show = tTouchable; // pas en plein écran, pas verrouillé, volet fermé
            if (barreOn() && show && tLp != null && tLp.y == 0) drawBar(c, W);
            if (android.os.SystemClock.uptimeMillis() < previewUntil) {
                int acc = 0xFFFF3FA4; try { acc = android.graphics.Color.parseColor(Pelage.cur(svc)[2]); } catch (Exception ignored) { }
                p.setStyle(android.graphics.Paint.Style.FILL); p.setColor((acc & 0xFFFFFF) | 0x66000000); c.drawRect(0, 0, W, H, p);
                p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(3 * dens); p.setColor(0xFFFFFFFF);
                p.setPathEffect(new android.graphics.DashPathEffect(new float[]{10 * dens, 6 * dens}, 0)); c.drawRect(1.5f * dens, 1.5f * dens, W - 1.5f * dens, H - 1.5f * dens, p); p.setPathEffect(null);
                p.setStyle(android.graphics.Paint.Style.FILL); p.setTextAlign(android.graphics.Paint.Align.CENTER); p.setTextSize(Math.min(H * .45f, 15 * dens)); p.setColor(0xFFFFFFFF); p.setShadowLayer(4, 0, 1, 0xFF000000);
                c.drawText("🐾 Zone du volet PuppyPhone", W / 2f, H / 2f + p.getTextSize() * .35f, p); p.clearShadowLayer(); p.setTextAlign(android.graphics.Paint.Align.LEFT);
            }
        }
        void drawBar(android.graphics.Canvas c, int W) {
            float H = sbH;
            int acc = 0xFFFF3FA4, acc2 = 0xFF29E6FF;
            try { acc = android.graphics.Color.parseColor(Pelage.cur(svc)[2]); acc2 = android.graphics.Color.parseColor(Pelage.cur(svc)[3]); } catch (Exception ignored) { }
            if (face == null) try { face = android.graphics.Typeface.createFromAsset(svc.getAssets(), "www/fonts/Bungee-Regular.ttf"); } catch (Exception e) { face = android.graphics.Typeface.DEFAULT_BOLD; }
            SharedPreferences sp = sp(svc);
            int opa = Math.max(20, Math.min(100, sp.getInt("barreOpa", 100)));
            int bgA = (int) (0xFF * opa / 100f);
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, H, (bgA << 24) | 0x22113A, (bgA << 24) | 0x0D0617, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, p); p.setShader(null);
            p.setShader(new android.graphics.LinearGradient(0, 0, W, 0, new int[]{acc2, acc, acc2}, null, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, H - 2 * dens, W, H, p); p.setShader(null);
            float cy = H / 2f + 1 * dens, pad = 14 * dens, shift = sp.getInt("barreX", 0) * dens;
            // deux listes : à gauche (depuis le bord gauche) et à droite (depuis le bord droit)
            float xl = pad + shift, xr = W - pad + shift;
            for (String it : sp.getString("barreGauche", "heure,patte,medailles").split(",")) {
                it = it.trim(); if (it.isEmpty() || !sp.getBoolean("bar_" + it, true)) continue;
                xl = seg(c, it, xl, cy, H, acc, acc2, false);
            }
            for (String it : sp.getString("barreDroite", "reseau,batterie").split(",")) {
                it = it.trim(); if (it.isEmpty() || !sp.getBoolean("bar_" + it, true)) continue;
                xr = seg(c, it, xr, cy, H, acc, acc2, true);
            }
            p.setTypeface(null);
        }
        /** Dessine un élément de la barre. rightAligned : x est le bord droit (on dessine vers la gauche). Renvoie le nouveau x. */
        float seg(android.graphics.Canvas c, String it, float x, float cy, float H, int acc, int acc2, boolean rightAligned) {
            switch (it) {
                case "heure": {
                    java.util.Calendar k = java.util.Calendar.getInstance();
                    String t = String.format(java.util.Locale.FRANCE, "%02d:%02d", k.get(java.util.Calendar.HOUR_OF_DAY), k.get(java.util.Calendar.MINUTE));
                    p.setTypeface(face); p.setTextSize(H * .48f); p.setColor(0xFFFFFFFF); p.setShadowLayer(6 * dens, 0, 0, acc);
                    float w = p.measureText(t);
                    float dx = rightAligned ? x - w : x;
                    c.drawText(t, dx, cy + p.getTextSize() * .36f, p); p.clearShadowLayer();
                    return rightAligned ? dx - 10 * dens : x + w + 10 * dens;
                }
                case "patte":
                    PupDraw.paw(c, p, (rightAligned ? x - 7 * dens : x + 7 * dens), cy, 6 * dens, -12, acc, 255);
                    return rightAligned ? x - 20 * dens : x + 20 * dens;
                case "medailles": {
                    float r = H * .3f, step = 2 * r + 5 * dens; int W = getWidth();
                    try {
                        org.json.JSONArray a = new org.json.JSONArray(PupNotifs.summary(svc, false));
                        int n = Math.min(5, a.length());
                        for (int i = 0; i < n; i++) {
                            float cxl = rightAligned ? x - r - i * step : x + r + i * step;
                            if (!rightAligned && cxl + r > W * .5f) break;
                            android.graphics.Bitmap b = icon(a.getJSONObject(i).getString("pkg"));
                            p.setColor(0xFFD6D0E2); c.drawCircle(cxl, cy, r + 1.5f * dens, p);
                            if (b != null) { android.graphics.Path cl = new android.graphics.Path(); cl.addCircle(cxl, cy, r, android.graphics.Path.Direction.CW); c.save(); c.clipPath(cl); c.drawBitmap(b, null, new android.graphics.RectF(cxl - r, cy - r, cxl + r, cy + r), p); c.restore(); }
                        }
                        float used = n * step;
                        return rightAligned ? x - used : x + used;
                    } catch (Exception e) { return x; }
                }
                case "batterie": {
                    android.content.Intent bi = svc.registerReceiver(null, new IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                    int lv = bi == null ? 50 : bi.getIntExtra(BatteryManager.EXTRA_LEVEL, 50) * 100 / Math.max(1, bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                    boolean chg = bi != null && bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                    p.setTypeface(face); p.setTextSize(H * .42f); String pc = lv + "%";
                    float bw = 24 * dens, bh = H * .42f, pcw = p.measureText(pc);
                    float bx = x - bw, by = cy - bh / 2;
                    p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(1.6f * dens); p.setColor(0xFFD6D0E2);
                    c.drawRoundRect(new android.graphics.RectF(bx, by, bx + bw, by + bh), 3 * dens, 3 * dens, p);
                    p.setStyle(android.graphics.Paint.Style.FILL); c.drawRect(bx + bw, cy - bh * .22f, bx + bw + 2.2f * dens, cy + bh * .22f, p);
                    p.setColor(lv <= 15 && !chg ? 0xFFFF4D5E : chg ? 0xFF3DFFB0 : acc2);
                    c.drawRoundRect(new android.graphics.RectF(bx + 2.2f * dens, by + 2.2f * dens, bx + 2.2f * dens + (bw - 4.4f * dens) * lv / 100f, by + bh - 2.2f * dens), 1.5f * dens, 1.5f * dens, p);
                    p.setColor(0xFFFFFFFF); c.drawText(pc, bx - 6 * dens - pcw, cy + p.getTextSize() * .36f, p);
                    return bx - 6 * dens - pcw - 12 * dens;
                }
                case "reseau": {
                    float wx = x - 8 * dens;
                    try {
                        android.net.ConnectivityManager cm = svc.getSystemService(android.net.ConnectivityManager.class);
                        android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                        boolean wifi = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI), cell = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
                        p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(1.8f * dens); p.setColor(0xFFFFFFFF); p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                        if (wifi) for (int i = 1; i <= 3; i++) { float rr = i * 3.6f * dens; c.drawArc(new android.graphics.RectF(wx - rr, cy + 4 * dens - rr, wx + rr, cy + 4 * dens + rr), 225, 90, false, p); }
                        else if (cell) { p.setStyle(android.graphics.Paint.Style.FILL); for (int i = 0; i < 4; i++) c.drawRect(wx - 8 * dens + i * 4.2f * dens, cy + 5 * dens - (i + 1) * 2.6f * dens, wx - 5.4f * dens + i * 4.2f * dens, cy + 5 * dens, p); }
                        p.setStyle(android.graphics.Paint.Style.FILL);
                    } catch (Exception ignored) { }
                    return x - 22 * dens;
                }
                default: return x;
            }
        }
    }

    /** Hauteur de la zone qui attrape le geste : barre d'état + marge (fine / large / très large). */
    static int zoneH() { int t = sp(svc).getInt("voletTaille", 1); return sbH + Math.round((t == 0 ? 10 : t == 2 ? 96 : 44) * dens); }

    /** Un simple appui dans la zone n'est pas pour nous : on le rejoue sur l'appli en dessous. */
    static void forwardTap(float x, float y) {
        if (svc == null || trigger == null || Build.VERSION.SDK_INT < 24) return;
        tLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(trigger, tLp); } catch (Exception ignored) { }
        h.postDelayed(() -> {
            android.graphics.Path p = new android.graphics.Path(); p.moveTo(x, y);
            try {
                svc.dispatchGesture(new android.accessibilityservice.GestureDescription.Builder().addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 40)).build(), null, null);
            } catch (Exception ignored) { }
            h.postDelayed(() -> { if (trigger != null && tTouchable) { tLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; try { wm.updateViewLayout(trigger, tLp); } catch (Exception ignored) { } } }, 140);
        }, 30);
    }

    static void addTrigger() {
        View v = new ZoneView(svc);
        tLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, zoneH(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        tLp.gravity = Gravity.TOP | Gravity.START; tLp.setTitle("PupVoletBande");
        // jusqu'au tout dernier pixel du bord, encoche comprise
        if (Build.VERSION.SDK_INT >= 30) tLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        else if (Build.VERSION.SDK_INT >= 28) tLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        tTouchable = true;
        // appli en plein écran (vidéo, jeu) : la barre d'état est cachée → on laisse passer les doigts
        v.setOnApplyWindowInsetsListener((x, ins) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                boolean vis = ins.isVisible(WindowInsets.Type.statusBars());
                if (vis) everVis = true;
                boolean b = vis || !everVis; // tant qu'on ne l'a jamais vue « visible », on ne croit pas au plein écran
                if (b != barVisible) { barVisible = b; diag(b ? "barre d'état visible" : "appli plein écran : bande en pause"); h.post(PupVolet::update); }
            }
            return ins;
        });
        final float[] y0 = {0}, x0 = {0}; final long[] t0 = {0}; final boolean[] drag = {false}; final VelocityTracker[] vt = {null};
        v.setOnTouchListener((x, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: y0[0] = e.getRawY(); x0[0] = e.getRawX(); t0[0] = e.getEventTime(); drag[0] = false; vt[0] = VelocityTracker.obtain(); vt[0].addMovement(e); return true;
                case MotionEvent.ACTION_MOVE: {
                    if (vt[0] != null) vt[0].addMovement(e);
                    float dy = e.getRawY() - y0[0];
                    if (!drag[0] && dy > 6 * dens) { drag[0] = true; diag("bande : glissé vers le bas ✓"); showShade(); }
                    if (drag[0]) setProgress(dy);
                    return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                    float vy = 0; if (vt[0] != null) { vt[0].addMovement(e); vt[0].computeCurrentVelocity(1000); vy = vt[0].getYVelocity(); vt[0].recycle(); vt[0] = null; }
                    if (drag[0]) { float dy = e.getRawY() - y0[0]; if (vy > 700 || (vy > -300 && dy > panelH() * .22f)) animOpen(); else animClose(); }
                    else if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.hypot(e.getRawX() - x0[0], e.getRawY() - y0[0]) < 12 * dens && e.getEventTime() - t0[0] < 450) {
                        // Barre puppy bloquante : on avale le toucher (Android ne voit rien), sinon on le rejoue à l'appli
                        boolean inBar = barreOn() && tLp != null && tLp.y == 0 && e.getRawY() <= sbH + 2 * dens;
                        if (!(inBar && sp(svc).getBoolean("barreBloque", true))) forwardTap(e.getRawX(), e.getRawY());
                    }
                    drag[0] = false; return true;
                }
            }
            return true;
        });
        try { wm.addView(v, tLp); trigger = v; } catch (Exception e) { trigger = null; }
    }
    static void removeTrigger() { if (trigger != null) try { wm.removeViewImmediate(trigger); } catch (Exception ignored) { } trigger = null; }

    // ------------------------------------------------------------ le volet
    static int screenH() { return svc.getResources().getDisplayMetrics().heightPixels + sbH * 2; }
    static float panelH() { return svc.getResources().getDisplayMetrics().heightPixels * .8f; }
    static float prog;
    static void setProgress(float dy) {
        if (shade == null) return;
        float H = screenH();
        float t = Math.max(-H, Math.min(0, dy - H));
        if (sweb != null) sweb.setTranslationY(t);
        prog = Math.max(0, Math.min(1, dy / panelH()));
        shade.setBackgroundColor(((int) (prog * 150) << 24));
    }
    static void ensureShade() {
        if (shade != null) return;
        shade = new FrameLayout(svc) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (e.getAction() == KeyEvent.ACTION_UP) { if (sweb != null) sweb.evaluateJavascript("window.VoletUI&&VoletUI.back()", r -> { if (!"true".equals(r)) animClose(); }); else animClose(); } return true; }
                return super.dispatchKeyEvent(e);
            }
        };
        sweb = web("/volet.html", true);
        shade.addView(sweb, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        sLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        sLp.gravity = Gravity.TOP | Gravity.START; sLp.setTitle("PupVolet");
        sLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        if (Build.VERSION.SDK_INT >= 28) sLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    }
    static void showShade() {
        if (svc == null) return;
        ensureShade();
        popHideNow();
        if (!shadeAdded) {
            sweb.setTranslationY(-screenH()); shade.setBackgroundColor(0);
            sLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            try { wm.addView(shade, sLp); shadeAdded = true; } catch (Exception e) { shadeAdded = false; return; }
        }
        emitShade("open", "");
    }
    static ValueAnimator anim;
    static void animTo(float from, float to, Runnable end) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(from, to); anim.setDuration(260); anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> { float t = (float) a.getAnimatedValue(); if (sweb != null) sweb.setTranslationY(t); float k = 1 + t / screenH(); if (shade != null) shade.setBackgroundColor(((int) (Math.max(0, Math.min(1, k)) * 150) << 24)); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator a) { if (end != null) end.run(); } });
        anim.start();
    }
    static void animOpen() {
        if (sweb == null) return;
        open = true; openedAt = android.os.SystemClock.uptimeMillis(); update();
        animTo(sweb.getTranslationY(), 0, () -> {
            sLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; // clavier (réponse rapide) + touche Retour
            try { wm.updateViewLayout(shade, sLp); } catch (Exception ignored) { }
            shade.requestFocus();
        });
        emitShade("opened", "");
    }
    static void animClose() {
        if (sweb == null || !shadeAdded) { closeNow(); return; }
        animTo(sweb.getTranslationY(), -screenH(), PupVolet::closeNow);
    }
    static void closeNow() {
        if (anim != null) { anim.cancel(); anim = null; }
        if (shadeAdded && shade != null) try { wm.removeViewImmediate(shade); } catch (Exception ignored) { }
        shadeAdded = false;
        if (open) { open = false; emitShade("closed", ""); }
        update();
    }
    /** Ouvre le volet sans geste (depuis l'horloge de l'accueil, l'aperçu des réglages…). */
    static boolean openFromApp() {
        if (svc == null) return false;
        h.post(() -> { showShade(); h.postDelayed(PupVolet::animOpen, 60); });
        return true;
    }
    static void onWindowChange(String pkg) {
        if (open && android.os.SystemClock.uptimeMillis() - openedAt > 700 && pkg != null && !pkg.equals(svc.getPackageName()) && !pkg.equals("com.android.systemui")) animClose();
        update();
    }

    // ------------------------------------------------------------ pop-ups
    static void popup(Context c, StatusBarNotification sbn) {
        if (svc != null) diag("notification importante : " + PupNotifs.label(svc, sbn.getPackageName()) + (popOn(svc) ? "" : " (pop-ups éteints)") + (locked() ? " (verrouillé)" : ""));
        if (svc == null || !popOn(svc) || open || PupVeille.showing || !awake() || locked()) return;
        android.app.Notification n = sbn.getNotification();
        if (sbn.isOngoing() || (n.flags & android.app.Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (android.app.Notification.CATEGORY_CALL.equals(n.category) || n.fullScreenIntent != null) return; // appels et réveils : on laisse l'écran d'Android
        if (sbn.getPackageName().equals(svc.getPackageName()) && android.app.Notification.CATEGORY_SERVICE.equals(n.category)) return;
        String js;
        try { js = PupNotifs.one(svc, sbn, true).toString(); } catch (Exception e) { return; }
        if (pweb == null) pweb = web("/pop.html", false);
        if (!popAdded) {
            pLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, sbH + Math.round(158 * dens), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            pLp.gravity = Gravity.TOP | Gravity.START; pLp.setTitle("PupPopup");
            if (Build.VERSION.SDK_INT >= 28) pLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            try { wm.addView(pweb, pLp); popAdded = true; } catch (Exception e) { return; }
        }
        popKey = sbn.getKey();
        final String title = PupNotifs.str(PupNotifs.cs(n.extras, android.app.Notification.EXTRA_TITLE));
        final String key = popKey;
        if (sp(svc).getBoolean("popForce", true)) { h.postDelayed(() -> kickHun(key, title, 0), 380); }
        final String d = js;
        pweb.evaluateJavascript("window.PopUI?PopUI.show(" + JSONObject.quote(d) + "):(window.__pend=" + JSONObject.quote(d) + ")", null);
    }
    // ------------------------------------------------------------ mode costaud
    static long androidOk, lastHijack; static boolean androidShade;
    static final java.util.HashSet<String> seenTitles = new java.util.HashSet<>();
    static final java.util.ArrayDeque<String> DIAG = new java.util.ArrayDeque<>();
    static void diag(String m) {
        String t = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE).format(new java.util.Date());
        synchronized (DIAG) { DIAG.addFirst(t + "  " + m); while (DIAG.size() > 14) DIAG.removeLast(); }
    }
    static String diagText() { synchronized (DIAG) { return String.join("\n", DIAG); } }

    /** Le volet d'Android vient de s'ouvrir quand même ? On le referme et on ouvre celui de PuppyPhone. */
    static void checkSystemShade() {
        if (svc == null || !voletOn(svc) || !sp(svc).getBoolean("voletForce", true)) return;
        boolean shade = false;
        try {
            int sh = svc.getResources().getDisplayMetrics().heightPixels, sw = svc.getResources().getDisplayMetrics().widthPixels;
            for (android.view.accessibility.AccessibilityWindowInfo w : svc.getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                android.graphics.Rect r = new android.graphics.Rect(); w.getBoundsInScreen(r);
                CharSequence t = Build.VERSION.SDK_INT >= 24 ? w.getTitle() : null;
                String ti = t == null ? "" : t.toString();
                if (r.height() > sh * .5f && !ti.isEmpty() && !seenTitles.contains(ti)) { seenTitles.add(ti); diag("fenêtre système : « " + ti + " » " + r.width() + "×" + r.height()); }
                // repérage par la FORME (le nom change selon Samsung/Android) : grande fenêtre système qui part du haut et prend toute la largeur = le volet déroulé
                boolean full = r.top <= sh * .12f && r.height() > sh * .5f && r.width() > sw * .7f;
                if (full) { shade = true; break; }
            }
        } catch (Exception ignored) { }
        long now = android.os.SystemClock.uptimeMillis();
        if (shade && !androidShade) diag("volet Android repéré (forme)" + (locked() ? " — verrouillé, on laisse" : ""));
        androidShade = shade;
        if (!shade) { if (now - androidOk > 1500) androidOk = 0; return; }
        if (locked() || androidOk != 0 || now - lastHijack < 650) return;
        lastHijack = now;
        diag("→ je referme le volet Android et j'ouvre le tien 🐾");
        slamShade(0);
        h.postDelayed(() -> { if (!open) { showShade(); h.postDelayed(PupVolet::animOpen, 60); } }, 170);
    }
    /** Ferme le volet système, et réessaie si Samsung le laisse ouvert. */
    static void slamShade(int tryN) {
        if (svc == null || tryN > 3) return;
        if (Build.VERSION.SDK_INT >= 31) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        else svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        h.postDelayed(() -> {
            boolean still = false;
            try {
                int sh = svc.getResources().getDisplayMetrics().heightPixels, sw = svc.getResources().getDisplayMetrics().widthPixels;
                for (android.view.accessibility.AccessibilityWindowInfo w : svc.getWindows()) {
                    if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                    android.graphics.Rect r = new android.graphics.Rect(); w.getBoundsInScreen(r);
                    if (r.top <= sh * .12f && r.height() > sh * .5f && r.width() > sw * .7f) { still = true; break; }
                }
            } catch (Exception ignored) { }
            if (still) { diag("…têtu, nouvelle tentative"); slamShade(tryN + 1); }
        }, 220);
    }

    /** Pousse le pop-up d'Android vers le haut (geste d'accessibilité) pour ne laisser que la carte puppyplay. */
    /** Laisse passer les doigts (vrais ou simulés) à travers nos fenêtres pendant un instant. */
    static void passThrough(boolean on) {
        if (pLp != null && popAdded) { if (on) pLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else pLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; try { wm.updateViewLayout(pweb, pLp); } catch (Exception ignored) { } }
        if (trigger != null && tLp != null) { if (on || !tTouchable) tLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else tLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; try { wm.updateViewLayout(trigger, tLp); } catch (Exception ignored) { } }
    }
    /** Où est le pop-up d'Android ? (par son titre, sinon par la petite fenêtre système en haut de l'écran) */
    static android.graphics.Rect findHun(String title) {
        int sh = svc.getResources().getDisplayMetrics().heightPixels;
        android.graphics.Rect byWin = null;
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : svc.getWindows()) {
                android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                boolean sys = root != null && root.getPackageName() != null && "com.android.systemui".contentEquals(root.getPackageName());
                if (!sys) continue;
                android.graphics.Rect r = findText(root, title, sh * .5f, 0);
                if (r != null) return r;
                android.graphics.Rect wr = new android.graphics.Rect(); w.getBoundsInScreen(wr);
                if (wr.top < sh * .25f && wr.height() > 50 * dens && wr.height() < sh * .45f && wr.bottom > sbH + 20 * dens) byWin = wr;
            }
        } catch (Exception ignored) { }
        return byWin;
    }
    static boolean canGesture() {
        try { return (svc.getServiceInfo().getCapabilities() & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0; } catch (Exception e) { return false; }
    }
    /** Chasse le pop-up d'Android : 1) coup de patte vers le haut (geste), 2) si têtu, ouvre/referme le volet Android en un éclair. */
    static void kickHun(String key, String title, int tryN) {
        if (svc == null || !popAdded || !key.equals(popKey) || Build.VERSION.SDK_INT < 24) return;
        android.graphics.Rect r = findHun(title);
        if (r == null) { if (tryN >= 1) diag("pop-up Android : plus là ✓"); return; }
        if (tryN >= 2 || !canGesture()) {
            if (!canGesture()) diag("⚠️ PupNav n'a pas le droit aux gestes : éteins/rallume-le dans l'accessibilité");
            if (Build.VERSION.SDK_INT >= 31 && sp(svc).getBoolean("popFlick", true)) {
                diag("pop-up Android têtu → éclair de volet pour le ranger");
                androidOk = android.os.SystemClock.uptimeMillis();
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
                h.postDelayed(() -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE), 170);
                h.postDelayed(() -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE), 520);
            }
            return;
        }
        diag("pop-up Android repéré (" + r.top + "→" + r.bottom + ") → coup de patte n°" + (tryN + 1));
        passThrough(true);
        android.graphics.Path path = new android.graphics.Path();
        float x = r.centerX(), y = Math.max(r.centerY(), sbH + 20 * dens);
        path.moveTo(x, y); path.lineTo(x, Math.max(2, r.top - 160 * dens));
        android.accessibilityservice.GestureDescription g = new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 120)).build();
        boolean ok = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(android.accessibilityservice.GestureDescription d) { h.postDelayed(() -> passThrough(false), 80); h.postDelayed(() -> kickHun(key, title, tryN + 1), 550); }
            @Override public void onCancelled(android.accessibilityservice.GestureDescription d) { passThrough(false); diag("coup de patte annulé"); h.postDelayed(() -> kickHun(key, title, 2), 300); }
        }, h);
        if (!ok) { passThrough(false); h.postDelayed(() -> kickHun(key, title, 2), 100); }
    }
    static android.graphics.Rect findText(android.view.accessibility.AccessibilityNodeInfo n, String title, float maxY, int depth) {
        if (n == null || depth > 40 || title == null || title.isEmpty()) return null;
        CharSequence t = n.getText();
        if (t != null && t.toString().trim().equals(title.trim())) {
            android.graphics.Rect r = new android.graphics.Rect(); n.getBoundsInScreen(r);
            if (r.top < maxY && r.height() > 0) {
                // on remonte jusqu'à la carte entière (le pop-up)
                android.view.accessibility.AccessibilityNodeInfo p = n.getParent(); android.graphics.Rect best = r;
                for (int i = 0; i < 6 && p != null; i++) { android.graphics.Rect pr = new android.graphics.Rect(); p.getBoundsInScreen(pr); if (pr.height() > maxY) break; best = pr; p = p.getParent(); }
                return best;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) { android.graphics.Rect r = findText(n.getChild(i), title, maxY, depth + 1); if (r != null) return r; }
        return null;
    }

    static void popupGone(String key) { if (popAdded && key.equals(popKey) && pweb != null) pweb.evaluateJavascript("window.PopUI&&PopUI.gone()", null); }
    static void popHideNow() { if (popAdded && pweb != null) try { wm.removeViewImmediate(pweb); } catch (Exception ignored) { } popAdded = false; popKey = ""; }

    // ------------------------------------------------------------ pages web
    static void emitShade(String ev, String data) { h.post(() -> { if (sweb != null) sweb.evaluateJavascript("window.VoletUI&&VoletUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data) + ")", null); }); }

    static WebView web(String page, boolean isShade) {
        WebView w = new WebView(svc);
        w.setBackgroundColor(0);
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setTextZoom(100);
        w.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl(), page); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override public void onPageFinished(WebView v, String u) {
                float t = sbH / dens;
                v.evaluateJavascript("document.documentElement.style.setProperty('--st','" + t + "px')", null);
                if (!isShade) v.evaluateJavascript("window.__pend&&window.PopUI&&PopUI.show(window.__pend)", null);
            }
        });
        w.addJavascriptInterface(new Bridge(), "Volet");
        w.loadUrl("https://" + HOST + page);
        return w;
    }
    static WebResourceResponse serve(Uri u, String page) {
        if (u == null || !HOST.equals(u.getHost()) || svc == null) return null;
        String p = u.getPath() == null || u.getPath().equals("/") ? page : u.getPath();
        HashMap<String, String> hd = new HashMap<>();
        try {
            byte[] img = null;
            if (p.equals("/nicon")) img = PupNotifs.appIcon(svc, u.getQueryParameter("pkg"));
            else if (p.equals("/nimg")) img = PupNotifs.image(svc, u.getQueryParameter("key"), u.getQueryParameter("k"));
            else if (p.equals("/nart")) img = PupNotifs.mediaArt(svc);
            if (p.equals("/nicon") || p.equals("/nimg") || p.equals("/nart")) {
                if (img == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", hd, new ByteArrayInputStream(new byte[0]));
                return new WebResourceResponse("image/png", null, 200, "OK", hd, new ByteArrayInputStream(img));
            }
            InputStream in = Pelage.open(svc, p);
            String mime = MainActivity.mime(p);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", hd, in);
        } catch (Exception e) { return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", hd, new ByteArrayInputStream(new byte[0])); }
    }

    static void launch(Intent i) { animClose(); h.postDelayed(() -> { try { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }, 120); }

    // ------------------------------------------------------------ pont JS
    static class Bridge {
        Context c() { return svc; }
        @JavascriptInterface public String prefs() {
            try { return new JSONObject().put("acc", Pelage.cur(svc)[2]).put("acc2", Pelage.cur(svc)[3]).put("granted", PupNotifs.granted(svc)).put("listening", PupNotifs.I != null).toString(); } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public String state() {
            JSONObject o = new JSONObject();
            try {
                Context c = svc;
                Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int lv = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b == null ? 100 : b.getIntExtra(BatteryManager.EXTRA_SCALE, 100), stt = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                o.put("pct", sc > 0 && lv >= 0 ? Math.round(lv * 100f / sc) : -1).put("charging", stt == BatteryManager.BATTERY_STATUS_CHARGING || stt == BatteryManager.BATTERY_STATUS_FULL);
                WifiManager wf = c.getApplicationContext().getSystemService(WifiManager.class); o.put("wifi", wf != null && wf.isWifiEnabled());
                try { BluetoothManager bm = c.getSystemService(BluetoothManager.class); o.put("bt", bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled()); } catch (Exception e) { o.put("bt", false); }
                o.put("torch", torchOn).put("hasTorch", torchId != null);
                AudioManager am = c.getSystemService(AudioManager.class);
                o.put("ringer", am.getRingerMode()).put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC)).put("volMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                o.put("dnd", nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL).put("dndOk", nm.isNotificationPolicyAccessGranted());
                o.put("rot", Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1);
                o.put("writeOk", Settings.System.canWrite(c));
                o.put("bright", Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128)).put("autoBright", Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1);
                if (Build.VERSION.SDK_INT >= 28) o.put("loc", c.getSystemService(LocationManager.class).isLocationEnabled());
                o.put("plane", Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
                o.put("veille", Pelage.sp(c).getBoolean("veille", false)).put("dicta", DictaService.rec).put("alarm", Reveil.nextText(c));
            } catch (Exception ignored) { }
            return o.toString();
        }
        @JavascriptInterface public String notifs() { return PupNotifs.json(svc, true); }
        @JavascriptInterface public String media() { return PupNotifs.media(svc); }
        @JavascriptInterface public void mediaCmd(String cmd) { h.post(() -> PupNotifs.mediaCmd(svc, cmd)); }
        @JavascriptInterface public void openNotif(String key) { h.post(() -> { animClose(); popHideNow(); h.postDelayed(() -> PupNotifs.open(svc, key), 140); }); }
        @JavascriptInterface public boolean act(String key, int i, String reply) { boolean ok = PupNotifs.action(svc, key, i, reply == null || reply.isEmpty() ? null : reply); if (reply == null || reply.isEmpty()) h.post(PupVolet::animClose); return ok; }
        @JavascriptInterface public void dismiss(String key) { PupNotifs.dismiss(key); }
        @JavascriptInterface public void clearAll() { PupNotifs.clearAll(); }
        @JavascriptInterface public void grant() { h.post(() -> { animClose(); PupNotifs.openSettings(svc); }); }

        @JavascriptInterface public void tile(String id) { h.post(() -> doTile(id, false)); }
        @JavascriptInterface public void tileLong(String id) { h.post(() -> doTile(id, true)); }
        @JavascriptInterface public void setBright(int v) {
            h.post(() -> {
                if (!Settings.System.canWrite(svc)) { launch(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + svc.getPackageName()))); return; }
                Settings.System.putInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                Settings.System.putInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.min(255, v)));
            });
        }
        @JavascriptInterface public void setVol(int v) { h.post(() -> { try { svc.getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void close() { h.post(PupVolet::animClose); }
        @JavascriptInterface public void drag(float dyCss) { h.post(() -> { if (sweb != null) { if (anim != null) anim.cancel(); sweb.setTranslationY(Math.min(0, dyCss * dens)); } }); }
        @JavascriptInterface public void release(float dyCss, float vyCss) { h.post(() -> { if (vyCss < -500 || dyCss < -120) animClose(); else animTo(sweb.getTranslationY(), 0, null); }); }
        @JavascriptInterface public void glob(String id) {
            h.post(() -> {
                switch (id) {
                    case "power": animClose(); h.postDelayed(() -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG), 250); break;
                    case "android": androidOk = android.os.SystemClock.uptimeMillis(); closeNow(); h.postDelayed(() -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS), 150); break;
                    case "settings": launch(new Intent(Settings.ACTION_SETTINGS)); break;
                    case "niche": launch(new Intent(svc, NicheActivity.class)); break;
                    case "alarm": launch(new Intent(svc, ReveilActivity.class)); break;
                    default: break;
                }
            });
        }
        // pop-up
        @JavascriptInterface public void popHide() { h.post(PupVolet::popHideNow); }
        @JavascriptInterface public void popPull() { h.post(() -> { popHideNow(); showShade(); h.postDelayed(PupVolet::animOpen, 40); }); }
        @JavascriptInterface public void haptic() { h.post(() -> { View v = sweb != null && open ? sweb : pweb; if (v != null) v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); }); }
    }

    static void doTile(String id, boolean lng) {
        Context c = svc; if (c == null) return;
        try {
            switch (id) {
                case "wifi":
                    if (lng) launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
                    else if (Build.VERSION.SDK_INT >= 29) launch(new Intent(Settings.Panel.ACTION_WIFI));
                    else { @SuppressWarnings("deprecation") boolean ok = c.getApplicationContext().getSystemService(WifiManager.class).setWifiEnabled(!c.getApplicationContext().getSystemService(WifiManager.class).isWifiEnabled()); }
                    break;
                case "bt": launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); break;
                case "data": launch(Build.VERSION.SDK_INT >= 29 && !lng ? new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) : new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)); break;
                case "torch": if (torchId != null) c.getSystemService(CameraManager.class).setTorchMode(torchId, !torchOn); break;
                case "son": {
                    if (lng) { launch(new Intent(Settings.ACTION_SOUND_SETTINGS)); break; }
                    AudioManager am = c.getSystemService(AudioManager.class); int m = am.getRingerMode();
                    boolean pol = c.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
                    int nx = m == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE : m == AudioManager.RINGER_MODE_VIBRATE && pol ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
                    am.setRingerMode(nx); break;
                }
                case "dnd": {
                    NotificationManager nm = c.getSystemService(NotificationManager.class);
                    if (lng || !nm.isNotificationPolicyAccessGranted()) { launch(new Intent(lng ? "android.settings.ZEN_MODE_SETTINGS" : Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); break; }
                    nm.setInterruptionFilter(nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                    break;
                }
                case "rot":
                    if (!Settings.System.canWrite(c)) { launch(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName()))); break; }
                    Settings.System.putInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1 ? 0 : 1);
                    break;
                case "autobright":
                    if (!Settings.System.canWrite(c)) { launch(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName()))); break; }
                    Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1 ? 0 : 1);
                    break;
                case "loc": launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); break;
                case "plane": launch(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); break;
                case "veille": Pelage.sp(c).edit().putBoolean("veille", !Pelage.sp(c).getBoolean("veille", false)).apply(); break;
                case "capture": closeNow(); h.postDelayed(() -> { if (Build.VERSION.SDK_INT >= 28) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT); }, 650); break;
                case "lock": closeNow(); if (Build.VERSION.SDK_INT >= 28) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); break;
                case "scan": launch(new Intent(c, ScanActivity.class)); break;
                case "dicta": launch(new Intent(c, DictaActivity.class)); break;
                default: break;
            }
        } catch (Exception ignored) { }
        h.postDelayed(() -> emitShade("state", ""), 250);
    }
}

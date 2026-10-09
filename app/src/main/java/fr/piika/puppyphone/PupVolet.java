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
    static final Runnable notifL = () -> { emitShade("notifs", ""); };

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
        int zone = sp(svc).getInt("voletZone", 0);
        int w = zone == 0 ? WindowManager.LayoutParams.MATCH_PARENT : svc.getResources().getDisplayMetrics().widthPixels / 2;
        int g = Gravity.TOP | (zone == 2 ? Gravity.START : Gravity.END);
        if (trigger != null && (tLp.width != w || tLp.gravity != g)) { tLp.width = w; tLp.gravity = g; try { wm.updateViewLayout(trigger, tLp); } catch (Exception ignored) { } }
    }

    static void addTrigger() {
        View v = new View(svc);
        tLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, sbH + Math.round(6 * dens), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        tLp.gravity = Gravity.TOP | Gravity.END; tLp.setTitle("PupVoletBande");
        if (Build.VERSION.SDK_INT >= 28) tLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
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
        final float[] y0 = {0}; final boolean[] drag = {false}; final VelocityTracker[] vt = {null};
        v.setOnTouchListener((x, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: y0[0] = e.getRawY(); drag[0] = false; vt[0] = VelocityTracker.obtain(); vt[0].addMovement(e); return true;
                case MotionEvent.ACTION_MOVE: {
                    if (vt[0] != null) vt[0].addMovement(e);
                    float dy = e.getRawY() - y0[0];
                    if (!drag[0] && dy > 10 * dens) { drag[0] = true; diag("bande : glissé vers le bas ✓"); showShade(); }
                    if (drag[0]) setProgress(dy);
                    return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                    float vy = 0; if (vt[0] != null) { vt[0].addMovement(e); vt[0].computeCurrentVelocity(1000); vy = vt[0].getYVelocity(); vt[0].recycle(); vt[0] = null; }
                    if (drag[0]) { float dy = e.getRawY() - y0[0]; if (vy > 900 || (vy > -300 && dy > panelH() * .3f)) animOpen(); else animClose(); }
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
        if (sp(svc).getBoolean("popForce", true)) { h.postDelayed(() -> kickHun(key, title, 0), 350); h.postDelayed(() -> kickHun(key, title, 1), 1300); }
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
            int sh = svc.getResources().getDisplayMetrics().heightPixels;
            for (android.view.accessibility.AccessibilityWindowInfo w : svc.getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                CharSequence t = Build.VERSION.SDK_INT >= 24 ? w.getTitle() : null;
                String ti = t == null ? "" : t.toString().toLowerCase(java.util.Locale.ROOT);
                android.graphics.Rect r = new android.graphics.Rect(); w.getBoundsInScreen(r);
                if (r.height() > sh * .55f && !ti.isEmpty() && !seenTitles.contains(ti)) { seenTitles.add(ti); diag("fenêtre système vue : « " + t + " »"); }
                boolean named = ti.contains("notificationshade") || ti.contains("notification shade") || ti.contains("quickpanel") || ti.contains("notificationpanel") || ti.contains("volet");
                // déplié = la fenêtre du volet a pris la main (focus) ; repliée, elle reste là mais sans focus
                if (named && r.height() > sh * .55f && (w.isFocused() || w.isActive())) { shade = true; break; }
            }
        } catch (Exception ignored) { }
        long now = android.os.SystemClock.uptimeMillis();
        if (shade && !androidShade) diag("volet Android détecté" + (locked() ? " (verrouillé : on laisse)" : ""));
        androidShade = shade;
        if (!shade) { if (now - androidOk > 1500) androidOk = 0; return; }
        if (locked() || androidOk != 0 || now - lastHijack < 700) return;
        lastHijack = now;
        diag("→ volet Android refermé, volet PuppyPhone ouvert 🐾");
        if (Build.VERSION.SDK_INT >= 31) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        else svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        h.postDelayed(() -> { if (!open) { showShade(); h.postDelayed(PupVolet::animOpen, 60); } }, 160);
    }

    /** Pousse le pop-up d'Android vers le haut (geste d'accessibilité) pour ne laisser que la carte puppyplay. */
    static void kickHun(String key, String title, int tryN) {
        if (svc == null || !popAdded || !key.equals(popKey) || Build.VERSION.SDK_INT < 24) return;
        android.graphics.Rect hit = null;
        try {
            int sh = svc.getResources().getDisplayMetrics().heightPixels;
            for (android.view.accessibility.AccessibilityWindowInfo w : svc.getWindows()) {
                android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !"com.android.systemui".contentEquals(root.getPackageName())) continue;
                hit = findText(root, title, sh * .5f, 0);
                if (hit != null) break;
            }
        } catch (Exception ignored) { }
        if (hit == null) { if (tryN == 1) diag("pop-up Android introuvable (déjà parti ?)"); return; }
        diag("pop-up Android repéré → on le pousse vers le haut");
        final android.graphics.Rect r = hit;
        // la carte puppyplay laisse passer le geste le temps du coup de patte
        if (pLp != null && popAdded) { pLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; try { wm.updateViewLayout(pweb, pLp); } catch (Exception ignored) { } }
        android.graphics.Path path = new android.graphics.Path();
        float x = r.centerX(), y = Math.max(r.centerY(), sbH + 20 * dens);
        path.moveTo(x, y); path.lineTo(x, Math.max(2, r.top - 120 * dens));
        android.accessibilityservice.GestureDescription g = new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 140)).build();
        svc.dispatchGesture(g, null, null);
        h.postDelayed(() -> { if (pLp != null && popAdded) { pLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; try { wm.updateViewLayout(pweb, pLp); } catch (Exception ignored) { } } }, 320);
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

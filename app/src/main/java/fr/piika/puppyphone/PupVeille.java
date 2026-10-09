package fr.piika.puppyphone;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Écran éteint maison : la Veille puppy (horloge + batterie) et la Sieste du chiot en charge.
 * Affichées dans une fenêtre d'accessibilité (comme la barre PupNav) : elle passe AU-DESSUS de l'écran de verrouillage
 * One UI (qui reste intact derrière), et impose sa propre luminosité comme un lecteur vidéo.
 * À la fin de la durée, l'écran est rééteint par le service PupNav (verrouillage conservé), sans boucle.
 */
final class PupVeille {
    private PupVeille() { }
    static volatile boolean showing, suppressNext;
    static long lastShow;
    static final Handler h = new Handler(Looper.getMainLooper());
    static Sieste s;
    static View view;
    static WindowManager wm;
    static String page;
    static boolean previewMode;

    static boolean veilleOn(Context c) { return Pelage.sp(c).getBoolean("veille", false); }
    static boolean siesteOn(Context c) { return Pelage.sp(c).getBoolean("siesteCharge", false); }

    static void onUnlock() { suppressNext = false; }

    static void onScreenOff(Context c, boolean charging) {
        if (showing) { hide(); suppressNext = false; return; }   // éteint pendant l'affichage : on laisse dormir
        if (suppressNext) { suppressNext = false; return; }     // c'est nous qui venons d'éteindre
        String pg = null;
        if (charging && siesteOn(c)) pg = "/sieste.html";
        else if (veilleOn(c) && (charging || Pelage.sp(c).getInt("veilleQuand", 0) == 1)) pg = "/veille.html";
        if (pg == null) return;
        if (SystemClock.uptimeMillis() - lastShow < 1500) return;
        lastShow = SystemClock.uptimeMillis();
        final String p = pg;
        h.postDelayed(() -> {
            try { if (c.getSystemService(PowerManager.class).isInteractive()) return; } catch (Exception ignored) { } // tu as déjà rallumé : on ne gêne pas
            // Sécurité : si One UI attend encore avant de verrouiller (délai « après X secondes »), on verrouille tout de suite,
            // pour que le téléphone soit bien verrouillé derrière la sieste / la veille.
            boolean locked = true;
            try { locked = c.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(); } catch (Exception ignored) { }
            PupNavA11y a = PupNavA11y.I;
            if (!locked && a != null && Build.VERSION.SDK_INT >= 28) {
                lastShow = SystemClock.uptimeMillis();
                a.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                h.postDelayed(() -> show(c, p, false), 450);
            } else show(c, p, false);
        }, 700); // on laisse le verrouillage se mettre en place, puis on passe devant
    }

    static void onUnplug() { if (showing && "/sieste.html".equals(page) && !previewMode) { hide(); sleepNow(); } }

    @SuppressWarnings("deprecation")
    static void wake(Context c) {
        try {
            PowerManager pm = c.getSystemService(PowerManager.class);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "puppyphone:veille");
            wl.acquire(2500);
        } catch (Exception ignored) { }
    }

    static void show(Context c, String pg, boolean preview) {
        PupNavA11y svc = PupNavA11y.I;
        if (svc == null) { // sans PupNav : simple écran d'aperçu
            if (preview) c.startActivity(new Intent(c, "/sieste.html".equals(pg) ? SiesteActivity.class : VeilleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("preview", true));
            return;
        }
        hide();
        if (!preview) wake(c);
        page = pg; previewMode = preview;
        boolean sieste = "/sieste.html".equals(pg);
        int lum = sieste ? Sieste.lum(svc) : Pelage.sp(svc).getInt("veilleLum", 0);
        wm = svc.getSystemService(WindowManager.class);
        s = new Sieste(svc, pg);
        View v = s.create();
        v.setBackgroundColor(0xFF000000);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        // la « ruse du lecteur vidéo » : la fenêtre impose sa luminosité à l'écran
        lp.screenBrightness = lum >= 2 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : lum == 1 ? 0.12f : 0.03f;
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.setTitle("PupVeille");
        v.setOnTouchListener((x, e) -> { if (e.getAction() == MotionEvent.ACTION_UP) hide(); return true; });
        try { wm.addView(v, lp); view = v; } catch (Exception e) { s.stop(); s = null; return; }
        s.start();
        showing = true;
        int sec = sieste ? Sieste.duree(svc) : Pelage.sp(svc).getInt("veilleDuree", 30);
        if (preview) sec = 10;
        if (sec > 0) {
            h.postDelayed(() -> { if (s != null) s.emit("dodo", ""); }, Math.max(0, sec * 1000L - 2200));
            h.postDelayed(() -> { boolean pv = previewMode; hide(); if (!pv) sleepNow(); }, sec * 1000L);
        }
    }

    static void hide() {
        h.removeCallbacksAndMessages(null);
        if (view != null && wm != null) try { wm.removeViewImmediate(view); } catch (Exception ignored) { }
        if (s != null) s.stop();
        view = null; s = null; showing = false;
    }

    /** Rééteint l'écran (verrouillage One UI conservé). */
    static boolean sleepNow() {
        PupNavA11y a = PupNavA11y.I;
        if (a != null && Build.VERSION.SDK_INT >= 28) {
            suppressNext = true;
            return a.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }
}

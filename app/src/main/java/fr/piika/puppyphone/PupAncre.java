package fr.piika.puppyphone;

import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.HashSet;

/**
 * « La fausse vidéo » : une fenêtre d'accessibilité de 1 pixel, invisible et non touchable.
 * Tant qu'elle est posée, Android considère PuppyPhone comme visible à l'écran : One UI ne le met pas
 * en sommeil ni ne le ferme (enregistrement PupDicta, etc.). Aucun effet sur l'écran ni le verrouillage.
 */
final class PupAncre {
    private PupAncre() { }
    static final HashSet<String> who = new HashSet<>();
    static View v;
    static final Handler h = new Handler(Looper.getMainLooper());

    static void hold(String tag) { h.post(() -> { who.add(tag); apply(); }); }
    static void release(String tag) { h.post(() -> { who.remove(tag); apply(); }); }
    /** Le service PupNav vient (re)démarrer : on repose l'ancre si quelqu'un en a besoin. */
    static void reattach() { h.post(() -> { v = null; apply(); }); }

    static void apply() {
        PupNavA11y a = PupNavA11y.I;
        if (who.isEmpty()) {
            if (v != null && a != null) try { a.getSystemService(WindowManager.class).removeViewImmediate(v); } catch (Exception ignored) { }
            v = null; return;
        }
        if (v != null || a == null) return;
        try {
            View x = new View(a);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START; lp.x = 0; lp.y = 0; lp.alpha = 0f;
            lp.setTitle("PupAncre");
            a.getSystemService(WindowManager.class).addView(x, lp); v = x;
        } catch (Exception ignored) { v = null; }
    }
}

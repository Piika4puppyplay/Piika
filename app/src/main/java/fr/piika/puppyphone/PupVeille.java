package fr.piika.puppyphone;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Veille puppy : un « faux Always On Display » maison. Quand l'écran s'éteint, PuppyPhone rallume un écran presque
 * noir par-dessus le verrouillage One UI (intact) avec l'horloge et la batterie puppy, puis le rééteint après la durée
 * choisie. Désactivé par défaut ; l'interrupteur dans « Ta niche » le coupe complètement.
 */
final class PupVeille {
    private PupVeille() { }
    static volatile boolean showing;
    static volatile boolean suppressNext;
    static long lastShow;

    static boolean enabled(Context c) { return Pelage.sp(c).getBoolean("veille", false); }

    static void onUnlock() { suppressNext = false; }

    static void onScreenOff(Context c, boolean charging) {
        if (showing) { showing = false; suppressNext = false; return; } // tu as éteint pendant la veille : on laisse dormir
        if (suppressNext) { suppressNext = false; return; }            // c'est nous qui avons éteint à la fin de la veille
        if (!enabled(c)) return;
        boolean always = Pelage.sp(c).getInt("veilleQuand", 0) == 1;
        if (!always && !charging) return;
        if (SystemClock.uptimeMillis() - lastShow < 1500) return;
        lastShow = SystemClock.uptimeMillis();
        show(c, false);
    }

    static void show(Context c, boolean preview) {
        try {
            PowerManager pm = c.getSystemService(PowerManager.class);
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "puppyphone:veille");
            wl.acquire(3000);
        } catch (Exception ignored) { }
        Intent i = new Intent(c, VeilleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra("preview", preview);
        try { c.startActivity(i); } catch (Exception ignored) { }
    }

    /** Fin de la veille : on rééteint l'écran (verrouillage One UI conservé). */
    static void sleepNow() {
        PupNavA11y a = PupNavA11y.I;
        if (a != null && Build.VERSION.SDK_INT >= 28) {
            suppressNext = true;
            a.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        }
    }
}

package fr.piika.puppyphone;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Le garde-fou de PuppyPhone. Si le lanceur se plante plusieurs fois de suite sans jamais tenir debout,
 * il ouvre tout seul PupRescousse (le chiot malade) : mise à jour, retour arrière, ou repartir sur une base propre.
 * Rien n'est envoyé nulle part.
 */
final class CrashGuard {
    private CrashGuard() { }
    static final String PREFS = "pupcrash";
    static final int LIMIT = 3;            // nb de démarrages ratés d'affilée
    static final long WINDOW = 45_000;     // dans cette fenêtre (ms)
    static final long HEALTHY = 9_000;     // debout depuis ce temps = on considère que tout va bien

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Installé très tôt (PupApp) : compte un crash non rattrapé avant de laisser Android fermer l'appli. */
    static void installHandler(Context ctx) {
        final Context c = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                SharedPreferences p = sp(c);
                String msg = e == null ? "?" : (e.getClass().getSimpleName() + ": " + e.getMessage());
                p.edit().putBoolean("crashed", true).putString("lastErr", msg).putLong("crashAt", System.currentTimeMillis()).apply();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        });
    }

    /** Au démarrage du lanceur : compte les démarrages rapprochés. Renvoie true s'il faut filer en rescousse. */
    static boolean armAndCheck(Context c) {
        SharedPreferences p = sp(c);
        long now = SystemClock.elapsedRealtime(), firstBoot = p.getLong("firstBoot", 0);
        int streak = p.getInt("streak", 0);
        if (firstBoot == 0 || now - firstBoot > WINDOW) { firstBoot = now; streak = 0; } // fenêtre dépassée : on repart à zéro
        streak++;
        p.edit().putLong("firstBoot", firstBoot).putInt("streak", streak).apply();
        return streak >= LIMIT;
    }

    /** Appelé quand le lanceur est resté debout un moment : tout va bien, on remet les compteurs à zéro. */
    static void markHealthyLater(Context c) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { sp(c).edit().putInt("streak", 0).remove("firstBoot").putBoolean("crashed", false).apply(); } catch (Exception ignored) { }
        }, HEALTHY);
    }

    static void openRescue(Context c) {
        try {
            c.startActivity(new Intent(c, RecoveryActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("auto", true));
        } catch (Exception ignored) { }
    }

    static void reset(Context c) { try { sp(c).edit().putInt("streak", 0).remove("firstBoot").putBoolean("crashed", false).apply(); } catch (Exception ignored) { } }
}

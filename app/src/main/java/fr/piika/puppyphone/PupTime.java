package fr.piika.puppyphone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Horloge internet de confiance de PuppyPhone (anti-triche).
 * On récupère l'heure réseau et on l'ancre sur SystemClock.elapsedRealtime() — une horloge
 * monotone qui avance toute seule et que l'utilisateur ne peut PAS reculer en changeant l'heure
 * du téléphone (elle ne se remet à zéro qu'au redémarrage). Résultat : once synchronisée,
 * l'heure reste juste même si on trafique l'horloge Android.
 */
final class PupTime {
    private PupTime() { }
    static final String PREFS = "puptime";
    static final long STALE = 12L * 3600 * 1000; // re-synchro conseillée au-delà de 12 h

    interface Cb { void done(long trusted); }

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Heure de confiance en ms epoch, ou -1 si pas encore synchronisée depuis le dernier démarrage. */
    static long now(Context c) {
        SharedPreferences p = sp(c);
        long net = p.getLong("net", 0), el = p.getLong("elapsed", -1);
        if (net <= 0 || el < 0) return -1;
        long cur = SystemClock.elapsedRealtime();
        if (cur < el) return -1;                 // redémarrage depuis la synchro → l'ancre n'est plus valable
        return net + (cur - el);
    }
    static boolean trusted(Context c) { return now(c) > 0; }
    static boolean stale(Context c) { long n = now(c); return n <= 0 || Math.abs(System.currentTimeMillis() - n) > STALE; }

    /** Synchronise en tâche de fond (jamais sur le thread UI). Rappelle cb avec l'heure de confiance (ou -1). */
    static void syncAsync(Context c, Cb cb) {
        final Context ctx = c.getApplicationContext();
        new Thread(() -> {
            long t = sync(ctx);
            if (cb != null) cb.done(t);
        }).start();
    }

    /** Synchrone (à appeler hors thread UI). Enregistre l'ancre et renvoie l'heure de confiance, ou -1. */
    static long sync(Context c) {
        long net = fetch(c);
        if (net > 0) {
            sp(c).edit().putLong("net", net).putLong("elapsed", SystemClock.elapsedRealtime()).putLong("at", net).apply();
            return now(c);
        }
        return now(c); // on garde l'ancre précédente si elle existe encore
    }

    /** Récupère l'heure réseau : d'abord l'heure NTP du système (non modifiable à la main), sinon l'en-tête Date d'un serveur. */
    private static long fetch(Context c) {
        if (Build.VERSION.SDK_INT >= 29) {
            try { long t = SystemClock.currentNetworkTimeMillis(); if (t > 0) return t; } catch (Throwable ignored) { }
        }
        for (String host : new String[]{"https://www.google.com", "https://cloudflare.com", "https://www.bing.com"}) {
            long t = httpDate(host);
            if (t > 0) return t;
        }
        return -1;
    }

    /** Lit l'heure dans l'en-tête HTTP « Date » (notre propre requête → non trafiquable côté téléphone). */
    private static long httpDate(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("HEAD");
            con.setConnectTimeout(6000);
            con.setReadTimeout(6000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "PuppyPhone");
            con.connect();
            long d = con.getDate(); // en-tête Date du serveur, en ms epoch
            return d > 0 ? d : -1;
        } catch (Exception e) { return -1; }
        finally { if (con != null) con.disconnect(); }
    }
}

package fr.piika.puppyphone;

import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Chaîne d'effets PupMusic, attachée à la session audio du lecteur.
 * Moteur principal : DynamicsProcessing (Android 9+) → EQ 10 bandes précis + pré-ampli + limiteur.
 * Repli : Equalizer système (5 bandes en général) interpolé.
 * En plus : LoudnessEnhancer (boost) et Virtualizer (largeur stéréo).
 */
final class MusicFx {
    static final float[] F = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final float[] WB = {1f, 1f, .8f, .45f, .15f, 0, 0, 0, 0, 0};
    static final float[] WT = {0, 0, 0, 0, 0, 0, .2f, .55f, .9f, 1f};

    DynamicsProcessing dp;
    Equalizer eq;
    LoudnessEnhancer le;
    Virtualizer virt;
    int session = -1;
    String backend = "aucun";
    String err = "";

    void attach(int s) {
        if (s == session && (dp != null || eq != null)) return;
        release();
        session = s;
        err = "";
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, F.length, false, 0, false, 0, true).setPreferredFrameDuration(10f).build();
                dp = new DynamicsProcessing(0, s, cfg);
                backend = "DynamicsProcessing · 10 bandes";
            } catch (Throwable e) { dp = null; err = "DP: " + e.getMessage(); }
        }
        if (dp == null) {
            try { eq = new Equalizer(0, s); backend = "Égaliseur système · " + eq.getNumberOfBands() + " bandes"; }
            catch (Throwable e) { eq = null; err += " EQ: " + e.getMessage(); backend = "aucun (effets refusés)"; }
        }
        try { le = new LoudnessEnhancer(s); } catch (Throwable e) { le = null; }
        try { virt = new Virtualizer(0, s); } catch (Throwable e) { virt = null; }
    }

    void release() {
        try { if (dp != null) dp.release(); } catch (Throwable ignored) { }
        try { if (eq != null) eq.release(); } catch (Throwable ignored) { }
        try { if (le != null) le.release(); } catch (Throwable ignored) { }
        try { if (virt != null) virt.release(); } catch (Throwable ignored) { }
        dp = null; eq = null; le = null; virt = null; session = -1;
    }

    static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    /** Réglages JSON : {on, bands[10], pre, bass, treble, boost, limiter, virt, unlock}. */
    void apply(JSONObject s) {
        if (s == null) s = new JSONObject();
        boolean on = s.optBoolean("on", true);
        boolean unlock = s.optBoolean("unlock", false);
        float bandMax = unlock ? 24 : 12;
        float pre = clamp((float) s.optDouble("pre", 0), -15, unlock ? 15 : 6);
        float bass = clamp((float) s.optDouble("bass", 0), -bandMax, bandMax);
        float treble = clamp((float) s.optDouble("treble", 0), -bandMax, bandMax);
        float boost = clamp((float) s.optDouble("boost", 0), 0, unlock ? 24 : 6);
        boolean limiter = !unlock || s.optBoolean("limiter", true);
        int vs = (int) clamp((float) s.optDouble("virt", 0), 0, 100);
        JSONArray b = s.optJSONArray("bands");
        float[] g = new float[F.length];
        for (int i = 0; i < F.length; i++) {
            float v = b == null ? 0 : (float) b.optDouble(i, 0);
            g[i] = clamp(clamp(v, -bandMax, bandMax) + bass * WB[i] + treble * WT[i], -bandMax * 1.5f, bandMax * 1.5f);
        }
        if (dp != null && Build.VERSION.SDK_INT >= 28) {
            try {
                dp.setInputGainAllChannelsTo(on ? pre : 0);
                for (int i = 0; i < F.length; i++) {
                    float cut = i < F.length - 1 ? (float) Math.sqrt(F[i] * F[i + 1]) : 20000f;
                    dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, cut, on ? g[i] : 0));
                }
                dp.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, on && limiter, 0, 1f, 60f, 10f, -1f, 0f));
                dp.setEnabled(true);
            } catch (Throwable e) { err = "DP apply: " + e.getMessage(); }
        } else if (eq != null) {
            try {
                short[] range = eq.getBandLevelRange();
                for (short k = 0; k < eq.getNumberOfBands(); k++) {
                    float hz = eq.getCenterFreq(k) / 1000f;
                    float v = interp(g, hz) + Math.min(0, pre);
                    eq.setBandLevel(k, (short) clamp(v * 100, range[0], range[1]));
                }
                eq.setEnabled(on);
            } catch (Throwable e) { err = "EQ apply: " + e.getMessage(); }
            if (pre > 0) boost += pre; // le pré-ampli positif passe par le boost
        }
        if (le != null) {
            try { le.setTargetGain((int) (boost * 100)); le.setEnabled(on && boost > 0.05f); } catch (Throwable ignored) { }
        }
        if (virt != null) {
            try {
                if (virt.getStrengthSupported()) virt.setStrength((short) (vs * 10));
                virt.setEnabled(on && vs > 0);
            } catch (Throwable ignored) { }
        }
    }

    static float interp(float[] g, float hz) {
        if (hz <= F[0]) return g[0];
        if (hz >= F[F.length - 1]) return g[F.length - 1];
        for (int i = 0; i < F.length - 1; i++) {
            if (hz <= F[i + 1]) {
                double t = (Math.log(hz) - Math.log(F[i])) / (Math.log(F[i + 1]) - Math.log(F[i]));
                return (float) (g[i] + (g[i + 1] - g[i]) * t);
            }
        }
        return 0;
    }
}

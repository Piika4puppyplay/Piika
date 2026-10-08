package fr.piika.puppyphone;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * La boîte à couinements : sons puppy 100 % originaux (synthétisés), joués par la barre PupNav, le clavier,
 * la charge et le déverrouillage. Muets en mode silencieux / vibreur. Réglages dans « Ta niche ».
 */
final class PupSons {
    private PupSons() { }
    static final String[] ALL = {"tap", "pouic", "jappe", "wouf", "wouf2", "croc", "grelot", "couine", "ouvre", "ferme", "halete"};
    static SoundPool pool;
    static final Map<String, Integer> ids = new HashMap<>();
    static final Set<Integer> ready = new HashSet<>();
    static final Map<Integer, Float> waiting = new HashMap<>();

    static SharedPreferences sp(Context c) { return Pelage.sp(c); }
    static boolean on(Context c) { return sp(c).getBoolean("sons", true); }
    static boolean on(Context c, String what) { return on(c) && sp(c).getBoolean(what, defOf(what)); }
    static boolean defOf(String k) { return !("sonsClavier".equals(k) || "sonsVerrou".equals(k)); }
    static float vol(Context c) { return Math.max(0, Math.min(100, sp(c).getInt("sonsVol", 60))) / 100f; }
    static boolean silent(Context c) {
        try { return c.getSystemService(AudioManager.class).getRingerMode() != AudioManager.RINGER_MODE_NORMAL; } catch (Exception e) { return false; }
    }

    static synchronized void init(Context c) {
        if (pool != null) return;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p, id, st) -> {
            synchronized (PupSons.class) {
                if (st != 0) return;
                ready.add(id);
                Float v = waiting.remove(id);
                if (v != null) p.play(id, v, v, 1, 0, 1f);
            }
        });
        for (String n : ALL) {
            try {
                int id;
                try (AssetFileDescriptor afd = app.getAssets().openFd("www/sons/" + n + ".wav")) { id = pool.load(afd, 1); }
                catch (Exception compressed) { id = pool.load(copy(app, n).getAbsolutePath(), 1); }
                ids.put(n, id);
            } catch (Exception ignored) { }
        }
    }
    static File copy(Context c, String n) throws Exception {
        File f = new File(c.getCacheDir(), "son_" + n + ".wav");
        if (f.length() > 0) return f;
        try (InputStream in = c.getAssets().open("www/sons/" + n + ".wav"); FileOutputStream o = new FileOutputStream(f)) {
            byte[] b = new byte[16384]; int k; while ((k = in.read(b)) > 0) o.write(b, 0, k);
        }
        return f;
    }

    /** Joue un son si la boîte à couinements est allumée (et le téléphone pas en silencieux). */
    static void play(Context c, String name) { if (on(c) && !silent(c)) raw(c, name, vol(c)); }
    /** Joue si l'option précise (sonsNav, sonsCharge…) est active. */
    static void play(Context c, String opt, String name) { if (on(c, opt) && !silent(c)) raw(c, name, vol(c)); }

    static synchronized void raw(Context c, String name, float v) {
        try {
            init(c);
            Integer id = ids.get(name);
            if (id == null) return;
            if (ready.contains(id)) pool.play(id, v, v, 1, 0, 1f); else waiting.put(id, v);
        } catch (Exception ignored) { }
    }

    /** En-tête JS ajouté à icons.js : les pages savent si elles peuvent couiner. */
    static String js(Context c) {
        return "\n;window.PUPSONS={on:" + (on(c) && !silent(c)) + ",vol:" + vol(c) + "};\n";
    }
}

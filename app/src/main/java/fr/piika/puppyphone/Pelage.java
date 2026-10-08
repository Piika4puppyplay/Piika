package fr.piika.puppyphone;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;

/** Pelage global : une seule couleur pour toute la niche (Pup-apps, clavier, barre PupNav, habillage des sites, fond animé, sieste). */
public final class Pelage {
    private Pelage() { }
    // id → {nom, accent, accent2, fond}
    static final String[][] ALL = {
            {"auto", "Chacun son pelage", "#ff3fa4", "#29e6ff", "#0b0614"},
            {"rose", "Néon Rose", "#ff3fa4", "#29e6ff", "#0b0614"},
            {"cyan", "Laser Turquoise", "#29e6ff", "#ff3fa4", "#030b12"},
            {"violet", "Violet Velours", "#9b5cff", "#29e6ff", "#0b0418"},
            {"cuir", "Cuir & Rivets", "#ff3fa4", "#29e6ff", "#120a10"},
            {"or", "Or Royal", "#ffb627", "#ff3fa4", "#0e0802"},
            {"vert", "Vert Fluo", "#3dffb0", "#9b5cff", "#020e0a"},
    };
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("pupniche", Context.MODE_MULTI_PROCESS); }
    static String id(Context c) { return sp(c).getString("pelage", "auto"); }
    static String[] cur(Context c) { String id = id(c); for (String[] p : ALL) if (p[0].equals(id)) return p; return ALL[0]; }
    static int color(String hex) { return 0xFF000000 | Integer.parseInt(hex.substring(1), 16); }
    static int acc(Context c) { return color(cur(c)[2]); }
    static int acc2(Context c) { return color(cur(c)[3]); }
    static int bg(Context c) { return color(cur(c)[4]); }

    static void set(Context c, String id) {
        sp(c).edit().putString("pelage", id).putLong("ver", System.currentTimeMillis()).commit();
        if (!"auto".equals(id)) {
            // le clavier suit le pelage (mêmes noms de thèmes)
            c.getSharedPreferences("pupkbd", Context.MODE_MULTI_PROCESS).edit().putString("theme", id).putInt("resetVer", c.getSharedPreferences("pupkbd", Context.MODE_MULTI_PROCESS).getInt("resetVer", 0)).commit();
        }
        try { PupNav.refreshAll(); } catch (Throwable ignored) { }
    }

    static final java.util.WeakHashMap<android.webkit.WebView, Long> SEEN = new java.util.WeakHashMap<>();
    /** À appeler dans onResume : recharge la page si le pelage a changé depuis son affichage. */
    static void watch(Context c, android.webkit.WebView w) {
        if (w == null) return;
        long v = sp(c).getLong("ver", 0);
        Long seen = SEEN.get(w);
        if (seen == null) { SEEN.put(w, v); return; }
        if (seen != v) { SEEN.put(w, v); try { w.reload(); } catch (Exception ignored) { } }
    }

    /** CSS ajouté à style.css pour toutes les pages des Pup-apps. */
    static String css(Context c) {
        String[] p = cur(c);
        if ("auto".equals(p[0])) return "";
        StringBuilder b = new StringBuilder("\n/* Pelage global : ").append(p[1]).append(" */\n");
        b.append("html body{--acc:").append(p[2]).append("!important;--acc2:").append(p[3]).append("!important;--pink:").append(p[2]).append("!important;--ok:").append(p[2]).append("!important}\n");
        b.append("body.wall-neon,body.ptasks,body.kbd,body.upd{background-color:").append(p[4]).append("}\n");
        if ("cuir".equals(p[0])) b.append(".bg,.leather{background-image:radial-gradient(70% 45% at 50% 0%,rgba(255,63,164,.22),transparent 70%),url(leather.png)!important;background-size:auto,220px!important}\n");
        return b.toString();
    }

    /** Ouvre un fichier www/… en ajoutant le pelage à style.css. */
    static InputStream open(Context c, String path) throws java.io.IOException {
        InputStream in = c.getAssets().open("www" + path);
        if ("/icons.js".equals(path)) {
            // la boîte à couinements suit icons.js dans toutes les Pup-apps
            InputStream extra = new SequenceInputStream(new ByteArrayInputStream(PupSons.js(c).getBytes(StandardCharsets.UTF_8)), c.getAssets().open("www/pupsons.js"));
            return new SequenceInputStream(in, extra);
        }
        if (!"/style.css".equals(path)) return in;
        String extra = css(c);
        if (extra.isEmpty()) return in;
        return new SequenceInputStream(in, new ByteArrayInputStream(extra.getBytes(StandardCharsets.UTF_8)));
    }

    /** Recolore un CSS/JS d'habillage (rose/cyan d'origine → couleurs du pelage). */
    static String tint(Context c, String s) {
        String[] p = cur(c);
        if ("auto".equals(p[0]) || "rose".equals(p[0])) return s;
        return s.replace("#ff3fa4", "§A§").replace("#29e6ff", "§B§").replace("§A§", p[2]).replace("§B§", p[3])
                .replace("255,63,164", hexRgb(p[2])).replace("41,230,255", hexRgb(p[3]));
    }
    static String hexRgb(String h) { int v = Integer.parseInt(h.substring(1), 16); return ((v >> 16) & 255) + "," + ((v >> 8) & 255) + "," + (v & 255); }
}

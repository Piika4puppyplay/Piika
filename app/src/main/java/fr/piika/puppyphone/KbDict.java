package fr.piika.puppyphone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Dictionnaire de PupKeyboard : fréquences (FrequencyWords) + mots appris par le chien. */
final class KbDict {
    final String lang;
    String[] words = new String[0];     // forme affichée
    String[] keys = new String[0];      // forme normalisée (sans accents, minuscule), triée
    int[] freq = new int[0];
    final Map<String, Integer> exact = new HashMap<>();
    final Map<String, Integer> user = new HashMap<>();
    final Map<String, String> normCache = new HashMap<>();
    volatile boolean ready;

    KbDict(String lang) { this.lang = lang; }

    static String norm(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").replace("œ", "oe").replace("æ", "ae").replace("’", "'");
    }

    void load(Context c) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("kbd/" + lang + ".txt"), StandardCharsets.UTF_8))) {
            String line;
            int rank = 0;
            while ((line = r.readLine()) != null) {
                int sp = line.lastIndexOf(' ');
                if (sp <= 0) continue;
                String w = line.substring(0, sp);
                if (w.length() < 1) continue;
                rows.add(new String[]{w, String.valueOf(rank++)});
            }
        } catch (Exception ignored) { }
        int n = rows.size();
        Integer[] idx = new Integer[n];
        String[] k = new String[n];
        for (int i = 0; i < n; i++) { idx[i] = i; k[i] = norm(rows.get(i)[0]); }
        Arrays.sort(idx, (a, b) -> k[a].compareTo(k[b]));
        words = new String[n]; keys = new String[n]; freq = new int[n];
        for (int i = 0; i < n; i++) {
            int j = idx[i];
            words[i] = rows.get(j)[0];
            keys[i] = k[j];
            freq[i] = Math.max(1, 1000000 / (Integer.parseInt(rows.get(j)[1]) + 10)); // score par rang
            exact.put(words[i].toLowerCase(Locale.ROOT), freq[i]);
        }
        loadUser(c);
        ready = true;
    }

    SharedPreferences up(Context c) { return c.getSharedPreferences("pupkbd_words_" + lang, Context.MODE_MULTI_PROCESS); }
    void loadUser(Context c) {
        try {
            JSONObject o = new JSONObject(up(c).getString("w", "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) { String w = it.next(); user.put(w, o.getInt(w)); }
        } catch (Exception ignored) { }
    }
    void saveUser(Context c) {
        try { up(c).edit().putString("w", new JSONObject(new HashMap<>(user)).toString()).apply(); } catch (Exception ignored) { }
    }
    void learn(Context c, String w) {
        if (w == null || w.length() < 2 || w.length() > 30 || w.matches(".*\\d.*")) return;
        String key = w;
        Integer v = user.get(key);
        user.put(key, v == null ? 1 : Math.min(9999, v + 1));
        if (user.size() > 3000) { // on oublie les mots les moins utilisés
            String min = null; int mv = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> e : user.entrySet()) if (e.getValue() < mv) { mv = e.getValue(); min = e.getKey(); }
            if (min != null) user.remove(min);
        }
        saveUser(c);
    }
    void forget(Context c) { user.clear(); saveUser(c); }

    boolean known(String w) {
        String l = w.toLowerCase(Locale.ROOT);
        return exact.containsKey(l) || user.containsKey(w) || user.containsKey(l);
    }

    int lower(String k) {
        int lo = 0, hi = keys.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (keys[m].compareTo(k) < 0) lo = m + 1; else hi = m; }
        return lo;
    }

    /** Suggestions pour le mot en cours (préfixe + petites fautes de frappe). */
    List<String> suggest(String typed, int max) {
        List<String> out = new ArrayList<>();
        if (typed == null || typed.isEmpty()) return out;
        String k = norm(typed);
        Map<String, Double> score = new HashMap<>();
        // préfixes
        if (ready) {
            int i = lower(k);
            int seen = 0;
            for (; i < keys.length && keys[i].startsWith(k) && seen < 4000; i++, seen++) {
                double s = freq[i] * (keys[i].length() == k.length() ? 3.0 : 1.0);
                score.merge(words[i], s, Math::max);
            }
        }
        for (Map.Entry<String, Integer> e : user.entrySet()) {
            String uk = normCache.get(e.getKey());
            if (uk == null) { uk = norm(e.getKey()); normCache.put(e.getKey(), uk); }
            if (uk.startsWith(k)) score.merge(e.getKey(), 20000.0 * e.getValue() * (uk.length() == k.length() ? 3 : 1), Double::sum);
        }
        // corrections (distance 1) si peu de résultats
        if (ready && score.size() < 3 && k.length() >= 3) {
            for (String cand : edits(k)) {
                int i = lower(cand);
                if (i < keys.length && keys[i].equals(cand)) score.merge(words[i], freq[i] * 0.6, Math::max);
            }
        }
        List<Map.Entry<String, Double>> l = new ArrayList<>(score.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Double> e : l) {
            String w = e.getKey();
            if (Character.isUpperCase(typed.charAt(0)) && w.length() > 0) w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            if (typed.length() > 1 && typed.equals(typed.toUpperCase(Locale.ROOT)) && !typed.equals(typed.toLowerCase(Locale.ROOT))) w = w.toUpperCase(Locale.ROOT);
            if (!out.contains(w)) out.add(w);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** Correction automatique : meilleur mot proche si le mot tapé est inconnu. */
    String autocorrect(String typed) {
        if (!ready || typed.length() < 3 || known(typed)) return null;
        String k = norm(typed);
        // même mot sans accents ? (ex. « tres » → « très »)
        int i = lower(k);
        String best = null; int bf = 0;
        for (int j = i; j < keys.length && keys[j].equals(k); j++) if (freq[j] > bf) { bf = freq[j]; best = words[j]; }
        if (best == null) {
            for (String cand : edits(k)) {
                int x = lower(cand);
                if (x < keys.length && keys[x].equals(cand) && freq[x] > bf) { bf = freq[x]; best = words[x]; }
            }
            if (bf < 40) return null; // trop rare : on ne touche à rien
        }
        if (best == null) return null;
        if (Character.isUpperCase(typed.charAt(0))) best = Character.toUpperCase(best.charAt(0)) + best.substring(1);
        return best.equals(typed) ? null : best;
    }

    static final String AZ = "abcdefghijklmnopqrstuvwxyz'";
    static List<String> edits(String w) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i <= w.length(); i++) {
            String a = w.substring(0, i), b = w.substring(i);
            if (!b.isEmpty()) r.add(a + b.substring(1));                                   // suppression
            if (b.length() > 1) r.add(a + b.charAt(1) + b.charAt(0) + b.substring(2));      // inversion
            for (int c = 0; c < AZ.length(); c++) {
                char ch = AZ.charAt(c);
                if (!b.isEmpty()) r.add(a + ch + b.substring(1));                           // remplacement
                r.add(a + ch + b);                                                          // insertion
            }
        }
        return r;
    }
}

package fr.piika.puppyphone;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** PupKeyboard — le clavier puppyplay. */
public class PupKeyboard extends InputMethodService {
    static PupKeyboard I;
    final Handler h = new Handler(Looper.getMainLooper());
    KbView view;
    SharedPreferences sp;
    final KbDict[] dicts = new KbDict[2]; // 0 fr, 1 en
    int shift = 0;         // 0 minuscule, 1 une majuscule, 2 verrouillé
    int layer = 0;         // 0 lettres, 1 symboles, 2 symboles 2, 3 pavé nombres, 4 pavé téléphone
    EditorInfo ei;
    boolean noSuggest, password, email, url;
    String lang = "fr";
    String lastAuto, lastAutoFrom;   // pour annuler une correction auto avec ⌫
    long lastSpace;
    ClipboardManager cm;
    final List<String> clips = new ArrayList<>();
    Vibrator vib;
    AudioManager am;

    @Override public void onCreate() {
        super.onCreate();
        I = this;
        sp = getSharedPreferences("pupkbd", MODE_MULTI_PROCESS);
        vib = getSystemService(Vibrator.class);
        am = getSystemService(AudioManager.class);
        cm = getSystemService(ClipboardManager.class);
        try {
            JSONArray a = new JSONArray(sp.getString("clips", "[]"));
            for (int i = 0; i < a.length(); i++) clips.add(a.getString(i));
        } catch (Exception ignored) { }
        if (cm != null) cm.addPrimaryClipChangedListener(this::grabClip);
        lang = sp.getString("lang", "fr");
        loadDict(lang);
        resetVer = sp.getInt("resetVer", 0);
    }

    void loadDict(String l) {
        int i = "fr".equals(l) ? 0 : 1;
        if (dicts[i] != null) return;
        KbDict d = new KbDict(l);
        dicts[i] = d;
        new Thread(() -> d.load(this)).start();
    }
    KbDict dict() { KbDict d = dicts["fr".equals(lang) ? 0 : 1]; if (d == null) { loadDict(lang); d = dicts["fr".equals(lang) ? 0 : 1]; } return d; }

    void grabClip() {
        try {
            if (!sp.getBoolean("clipHist", true)) return;
            ClipData c = cm.getPrimaryClip();
            if (c == null || c.getItemCount() == 0) return;
            CharSequence t = c.getItemAt(0).coerceToText(this);
            if (t == null) return;
            String s = t.toString();
            if (s.trim().isEmpty() || s.length() > 5000) return;
            clips.remove(s); clips.add(0, s);
            while (clips.size() > 25) clips.remove(clips.size() - 1);
            saveClips();
        } catch (Exception ignored) { }
    }
    void saveClips() { sp.edit().putString("clips", new JSONArray(clips).toString()).apply(); }
    void clearClips() { clips.clear(); saveClips(); if (view != null) { view.clips = clips; view.refresh(); } }

    @Override public View onCreateInputView() {
        view = new KbView(this, this);
        applyPrefs();
        return view;
    }

    int resetVer;
    void applyPrefs() {
        sp = getSharedPreferences("pupkbd", MODE_MULTI_PROCESS); // relit le fichier si les réglages ont changé
        int rv = sp.getInt("resetVer", 0);
        if (rv != resetVer) {
            resetVer = rv;
            clips.clear();
            try { JSONArray a = new JSONArray(sp.getString("clips", "[]")); for (int i = 0; i < a.length(); i++) clips.add(a.getString(i)); } catch (Exception ignored) { }
            for (KbDict d : dicts) if (d != null) { d.user.clear(); d.loadUser(this); }
        }
        lang = sp.getString("lang", "fr");
        loadDict(lang);
        if (view == null) return;
        int[] sizes = {50, 56, 62, 70};
        view.keyH = (int) (sizes[Math.max(0, Math.min(3, sp.getInt("size", 2)))] * view.dp);
        view.stripH = (int) (48 * view.dp);
        view.bubble = sp.getBoolean("bubble", true);
        view.hints = sp.getBoolean("hints", true);
        view.setTheme(KbView.theme(sp.getString("theme", "rose")));
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        ei = info;
        applyPrefs();
        int cls = info.inputType & InputType.TYPE_MASK_CLASS, var = info.inputType & InputType.TYPE_MASK_VARIATION;
        password = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        email = var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
        url = var == InputType.TYPE_TEXT_VARIATION_URI;
        noSuggest = password || email || url || (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 && !sp.getBoolean("forceSugg", false) || !sp.getBoolean("suggest", true);
        if (cls == InputType.TYPE_CLASS_PHONE) layer = 4;
        else if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME) layer = 3;
        else layer = 0;
        shift = 0;
        if (view != null) { view.setMode(0); view.clips = clips; }
        buildRows();
        autoShift();
        updateSuggestions();
    }

    @Override public void onFinishInputView(boolean finishing) { super.onFinishInputView(finishing); if (view != null) { view.pressed = null; view.popAlts = null; } }

    boolean enterIsEmoji() { return false; }
    String commaKey() { return email ? "@" : url ? "/" : ","; }

    String rowsSig = "";
    void buildRows() {
        if (view == null) return;
        String sig = layer + "|" + sp.getString("layout", "") + "|" + lang + "|" + sp.getBoolean("numRow", false) + "|" + commaKey();
        if (sig.equals(rowsSig) && !view.rows.isEmpty() && view.mode == 0) return; // même clavier : on garde le rendu en cache
        rowsSig = sig;
        List<List<KbLayouts.Key>> rows;
        switch (layer) {
            case 1: rows = KbLayouts.symbols(1, commaKey()); break;
            case 2: rows = KbLayouts.symbols(2, commaKey()); break;
            case 3: rows = KbLayouts.numpad(false); break;
            case 4: rows = KbLayouts.numpad(true); break;
            default: rows = KbLayouts.letters(sp.getString("layout", "fr".equals(lang) ? "azerty" : "qwerty"), sp.getBoolean("numRow", false), commaKey());
        }
        view.setRows(rows);
    }
    int textRows() { return sp.getBoolean("numRow", false) ? 5 : 4; }

    // ------------------------------------------------------------------ libellés
    String brandText() { return "PupKeyboard"; }
    String spaceLabel() { return "🐾 " + ("fr".equals(lang) ? "Français" : "English"); }
    String enterLabel() {
        if (ei == null) return "⏎";
        if ((ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "⏎";
        switch (ei.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_SEARCH: return "🔍";
            case EditorInfo.IME_ACTION_SEND: return "➤";
            case EditorInfo.IME_ACTION_GO: return "➜";
            case EditorInfo.IME_ACTION_NEXT: return "⇥";
            case EditorInfo.IME_ACTION_DONE: return "✓";
            case EditorInfo.IME_ACTION_PREVIOUS: return "⇤";
            default: return "⏎";
        }
    }
    boolean commaOpensSettings() { return true; }

    // ------------------------------------------------------------------ retours
    void feedback(KbLayouts.Key k) {
        int v = sp.getInt("vibe", 2);
        if (v > 0) vibe(new int[]{0, 8, 14, 24}[Math.min(3, v)]);
        if (sp.getBoolean("sound", false) && am != null) {
            int fx = k == null ? AudioManager.FX_KEYPRESS_STANDARD : k.code == KbLayouts.DEL ? AudioManager.FX_KEYPRESS_DELETE : k.code == KbLayouts.SPACE ? AudioManager.FX_KEYPRESS_SPACEBAR : k.code == KbLayouts.ENTER ? AudioManager.FX_KEYPRESS_RETURN : AudioManager.FX_KEYPRESS_STANDARD;
            am.playSoundEffect(fx, sp.getInt("soundVol", 50) / 100f);
        }
    }
    void vibe(int ms) {
        if (vib == null || sp.getInt("vibe", 2) == 0) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(ms, Math.min(255, 60 + sp.getInt("vibe", 2) * 60)));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ saisie
    InputConnection ic() { return getCurrentInputConnection(); }

    void typeText(String s) {
        InputConnection ic = ic(); if (ic == null) return;
        lastAuto = null;
        ic.commitText(s, 1);
        if (shift == 1) setShift(0);
        afterType();
    }

    void typeEmoji(String s) {
        typeText(s);
        try {
            JSONArray a = new JSONArray(sp.getString("recentEmo", "[]"));
            JSONArray n = new JSONArray(); n.put(s);
            for (int i = 0; i < a.length() && n.length() < 32; i++) if (!s.equals(a.getString(i))) n.put(a.getString(i));
            sp.edit().putString("recentEmo", n.toString()).apply();
        } catch (Exception ignored) { }
    }
    String[] emojiList(int tab) {
        if (tab == 0) {
            try { JSONArray a = new JSONArray(sp.getString("recentEmo", "[]")); String[] r = new String[a.length()]; for (int i = 0; i < r.length; i++) r[i] = a.getString(i); return r; } catch (Exception e) { return new String[0]; }
        }
        return KbLayouts.EMO[tab];
    }

    void onKey(KbLayouts.Key k) {
        InputConnection ic = ic(); if (ic == null) return;
        switch (k.code) {
            case KbLayouts.CH: {
                String s = shift > 0 ? k.out.toUpperCase() : k.out;
                if (isPunct(s)) { maybeAutocorrect(); lastAuto = null; ic.commitText(s, 1); if (shift == 1) setShift(0); afterType(); return; }
                typeText(s);
                return;
            }
            case KbLayouts.SPACE: {
                long now = System.currentTimeMillis();
                CharSequence before = ic.getTextBeforeCursor(2, 0);
                if (sp.getBoolean("dblSpace", true) && now - lastSpace < 450 && before != null && before.length() == 2 && before.charAt(1) == ' ' && Character.isLetterOrDigit(before.charAt(0))) {
                    ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1); lastSpace = 0; afterType(); return;
                }
                lastSpace = now;
                boolean corrected = maybeAutocorrect();
                learnCurrent();
                ic.commitText(" ", 1);
                if (!corrected) lastAuto = null;
                afterType();
                return;
            }
            case KbLayouts.ENTER: {
                learnCurrent();
                lastAuto = null;
                int action = ei == null ? EditorInfo.IME_ACTION_NONE : ei.imeOptions & EditorInfo.IME_MASK_ACTION;
                boolean noAct = ei == null || (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED;
                if (noAct) ic.commitText("\n", 1); else ic.performEditorAction(action);
                afterType();
                return;
            }
            case KbLayouts.SYM: layer = 1; buildRows(); return;
            case KbLayouts.SYM2: layer = 2; buildRows(); return;
            case KbLayouts.ABC: layer = 0; buildRows(); autoShift(); return;
            case KbLayouts.EMOJI: view.emoTab = emojiList(0).length > 0 ? 0 : 1; view.setMode(1); return;
        }
    }

    static boolean isPunct(String s) { return s.length() == 1 && ".,;:!?)".contains(s); }

    void deleteChar() {
        InputConnection ic = ic(); if (ic == null) return;
        if (lastAuto != null) { // annule la correction auto
            CharSequence b = ic.getTextBeforeCursor(lastAuto.length() + 1, 0);
            if (b != null && b.toString().equals(lastAuto + " ")) { ic.deleteSurroundingText(lastAuto.length() + 1, 0); ic.commitText(lastAutoFrom, 1); lastAuto = null; afterType(); return; }
            lastAuto = null;
        }
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1);
        else { ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)); }
        afterType();
    }

    void deleteWord() {
        InputConnection ic = ic(); if (ic == null) return;
        CharSequence b = ic.getTextBeforeCursor(64, 0);
        if (b == null || b.length() == 0) return;
        String s = b.toString();
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(s.charAt(i - 1))) i--;
        ic.deleteSurroundingText(s.length() - i, 0);
        lastAuto = null;
        afterType();
    }

    void moveCursor(int dir) {
        InputConnection ic = ic(); if (ic == null) return;
        int code = dir < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)); ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        vibe(5);
        h.postDelayed(this::updateSuggestions, 30);
    }

    void setShift(int s) { shift = s; if (view != null) view.refresh(); }

    void autoShift() {
        if (layer != 0 || !sp.getBoolean("autoCap", true) || ei == null || shift == 2) return;
        InputConnection ic = ic(); if (ic == null) return;
        int caps = 0;
        try { caps = ic.getCursorCapsMode(ei.inputType); } catch (Exception ignored) { }
        boolean want = caps != 0;
        if (want != (shift == 1)) setShift(want ? 1 : 0);
    }

    void afterType() { autoShift(); updateSuggestions(); }

    String currentWord() {
        InputConnection ic = ic(); if (ic == null) return "";
        CharSequence b = ic.getTextBeforeCursor(48, 0);
        if (b == null) return "";
        String s = b.toString();
        int i = s.length();
        while (i > 0 && (Character.isLetter(s.charAt(i - 1)) || s.charAt(i - 1) == '\'' || s.charAt(i - 1) == '’' || s.charAt(i - 1) == '-')) i--;
        String w = s.substring(i);
        // « l'arbre » → on suggère sur « arbre »
        int ap = Math.max(w.lastIndexOf('\''), w.lastIndexOf('’'));
        if (ap >= 0 && ap < w.length() - 1 && ap <= 2) w = w.substring(ap + 1);
        return w;
    }

    void updateSuggestions() {
        if (view == null) return;
        if (noSuggest || layer != 0) { view.setSuggestions(null); return; }
        String w = currentWord();
        if (w.isEmpty()) { view.setSuggestions(null); return; }
        List<String> l = dict().suggest(w, 3);
        if (!l.contains(w)) { l.add(l.size() >= 2 ? 1 : l.size(), w); }
        // meilleur au centre : on veut [tapé/alt, meilleur, alt]
        List<String> out = new ArrayList<>();
        String best = l.get(0).equals(w) && l.size() > 1 ? l.get(1) : l.get(0);
        out.add(best);
        for (String s : l) if (!out.contains(s) && out.size() < 3) out.add(s);
        view.setSuggestions(out);
    }

    void pickSuggestion(String s) {
        InputConnection ic = ic(); if (ic == null) return;
        String w = currentWord();
        if (!w.isEmpty()) ic.deleteSurroundingText(w.length(), 0);
        ic.commitText(s + " ", 1);
        dict().learn(this, s);
        lastAuto = null;
        afterType();
    }

    boolean maybeAutocorrect() {
        if (noSuggest || !sp.getBoolean("autocorrect", false) || layer != 0) return false;
        String w = currentWord();
        if (w.length() < 3) return false;
        String c = dict().autocorrect(w);
        if (c == null) return false;
        InputConnection ic = ic(); if (ic == null) return false;
        ic.deleteSurroundingText(w.length(), 0);
        ic.commitText(c, 1);
        lastAuto = c; lastAutoFrom = w;
        return true;
    }

    void learnCurrent() {
        if (password || !sp.getBoolean("learn", true)) return;
        String w = currentWord();
        if (w.length() >= 2 && !dict().known(w)) dict().learn(this, w);
    }

    // ------------------------------------------------------------------ barre d'outils
    void onTool(int i) {
        switch (i) {
            case 0: view.emoTab = emojiList(0).length > 0 ? 0 : 1; view.setMode(1); break;
            case 1: view.clips = clips; view.setMode(2); break;
            case 2: openSettings(); break;
            case 3: requestHideSelf(0); break;
        }
    }
    void backToKeys() { rowsSig = ""; view.setMode(0); buildRows(); }
    void openSettings() {
        try { startActivity(new Intent(this, KbSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }
    void pickIme() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showInputMethodPicker();
    }

    @Override public void onDestroy() { I = null; super.onDestroy(); }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public void onUpdateSelection(int os, int oe, int ns, int ne, int cs, int ce) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce);
        if (view != null && view.pressed == null) { h.removeCallbacks(suggRun); h.postDelayed(suggRun, 40); }
    }
    final Runnable suggRun = () -> { autoShift(); updateSuggestions(); };
}

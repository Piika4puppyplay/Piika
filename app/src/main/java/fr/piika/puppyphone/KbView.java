package fr.piika.puppyphone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Dessin et toucher de PupKeyboard : touches en verre Vista, néon puppyplay, bulle de frappe, accents en appui long. */
class KbView extends View {
    static final class Theme {
        String name; int bg1, bg2, key1, key2, acc, acc2; boolean leather;
        Theme(String n, int bg1, int bg2, int key1, int key2, int acc, int acc2, boolean leather) { name = n; this.bg1 = bg1; this.bg2 = bg2; this.key1 = key1; this.key2 = key2; this.acc = acc; this.acc2 = acc2; this.leather = leather; }
    }
    static Theme theme(String id) {
        switch (id == null ? "" : id) {
            case "cyan": return new Theme(id, 0xFF06202A, 0xFF030B12, 0xFF1D4A5C, 0xFF07161E, 0xFF29E6FF, 0xFFFF3FA4, false);
            case "violet": return new Theme(id, 0xFF1E0B3A, 0xFF0B0418, 0xFF4A2A80, 0xFF1A0B33, 0xFF9B5CFF, 0xFF29E6FF, false);
            case "cuir": return new Theme(id, 0xFF1A0D15, 0xFF0C0509, 0xFF3A2430, 0xFF140910, 0xFFFF3FA4, 0xFF29E6FF, true);
            case "or": return new Theme(id, 0xFF241604, 0xFF0E0802, 0xFF5A4012, 0xFF1E1404, 0xFFFFB627, 0xFFFF3FA4, false);
            case "vert": return new Theme(id, 0xFF04241A, 0xFF020E0A, 0xFF135A44, 0xFF052016, 0xFF3DFFB0, 0xFF9B5CFF, false);
            default: return new Theme("rose", 0xFF2A0B24, 0xFF0B0614, 0xFF4A2350, 0xFF180A22, 0xFFFF3FA4, 0xFF29E6FF, false);
        }
    }

    final PupKeyboard ime;
    final float dp;
    final Handler h = new Handler(Looper.getMainLooper());
    Theme th = theme("rose");
    List<List<KbLayouts.Key>> rows = new ArrayList<>();
    List<String> sugg = new ArrayList<>();
    int mode; // 0 touches, 1 emojis, 2 presse-papiers
    int keyH, stripH, navInset;
    boolean bubble = true, hints = true;
    Typeface tf, tfHead;
    Bitmap base, leather, logo, stripBmp;
    boolean dirty = true, stripDirty = true;
    final java.util.HashMap<String, Bitmap> cache = new java.util.HashMap<>();
    int rowsVer = 0;

    // toucher
    KbLayouts.Key pressed;
    int pointerId = -1;
    float downX, downY, lastCurX;
    boolean longDone, cursorMode, delWord;
    String[] popAlts; int popSel; RectF popRect; KbLayouts.Key popKey;
    int stripPressed = -1;
    long lastShift;

    // emojis / presse-papiers
    int emoTab = 1; float scroll; float scrollMax; boolean scrolling; float scrollStartY, scrollStart;
    List<String> clips = new ArrayList<>();

    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);

    KbView(Context c, PupKeyboard ime) {
        super(c);
        this.ime = ime;
        dp = getResources().getDisplayMetrics().density;
        try { tf = Typeface.createFromAsset(c.getAssets(), "www/fonts/ChakraPetch-SemiBold.ttf"); } catch (Exception e) { tf = Typeface.DEFAULT_BOLD; }
        try { tfHead = Typeface.createFromAsset(c.getAssets(), "www/fonts/Bungee-Regular.ttf"); } catch (Exception e) { tfHead = Typeface.DEFAULT_BOLD; }
        leather = BitmapFactory.decodeResource(getResources(), R.drawable.nav_leather);
        try { logo = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_pupkeyboard); } catch (Exception ignored) { }
        setOnApplyWindowInsetsListener((v, ins) -> { int b = ins.getSystemWindowInsetBottom(); if (b != navInset) { navInset = b; requestLayout(); } return ins; });
    }

    void clearCache() { cache.clear(); base = null; stripDirty = true; } // pas de recycle() : le rendu matériel peut encore les lire
    void setRows(List<List<KbLayouts.Key>> r) { rows = r; rowsVer++; clearCache(); layoutKeys(); requestLayout(); invalidate(); }
    void setSuggestions(List<String> s) {
        List<String> n = s == null ? new ArrayList<>() : s;
        if (n.equals(sugg)) return;
        sugg = n; stripDirty = true; invalidate();
    }
    void setMode(int m) { if (m == mode) return; mode = m; scroll = 0; requestLayout(); invalidate(); }
    void setTheme(Theme t) { if (th != null && t.name.equals(th.name) && keyHApplied == keyH) return; th = t; keyHApplied = keyH; clearCache(); invalidate(); }
    int keyHApplied = -1;
    void refresh() { stripDirty = true; invalidate(); }

    int rowsCount() { return Math.max(4, rows.size()); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int n = mode == 0 ? rows.size() : ime.textRows();
        int hgt = stripH + (int) (6 * dp) + n * keyH + (n - 1) * (int) (7 * dp) + (int) (8 * dp) + navInset;
        setMeasuredDimension(w, hgt);
    }
    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) { layoutKeys(); clearCache(); }

    void layoutKeys() {
        int W = getWidth();
        if (W == 0 || rows.isEmpty()) return;
        float side = 4 * dp, gapX = 5 * dp, gapY = 7 * dp;
        float maxW = 0;
        for (List<KbLayouts.Key> r : rows) { float s = 0; for (KbLayouts.Key k : r) s += k.w; maxW = Math.max(maxW, s); }
        float unit = (W - 2 * side) / maxW;
        float y = stripH + 6 * dp;
        for (List<KbLayouts.Key> r : rows) {
            float s = 0; for (KbLayouts.Key k : r) s += k.w;
            float x = side + (maxW - s) * unit / 2;
            for (KbLayouts.Key k : r) {
                k.x = x + gapX / 2; k.y = y; k.kw = k.w * unit - gapX; k.kh = keyH;
                x += k.w * unit;
            }
            y += keyH + gapY;
        }
    }

    // ================================================================== DESSIN
    @Override protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        // fond + touches : rendu une seule fois par état (majuscules / disposition / mode) puis réutilisé
        String key = rowsVer + "|" + mode + "|" + (mode == 0 ? ime.shift : 0) + "|" + W + "x" + H;
        Bitmap b = cache.get(key);
        if (b == null) {
            b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            drawBase(new Canvas(b));
            if (cache.size() > 4) clearCache();
            cache.put(key, b);
        }
        c.drawBitmap(b, 0, 0, null);
        // bande des suggestions : petit bitmap redessiné seulement quand les mots changent
        if (stripDirty || stripBmp == null || stripBmp.getWidth() != W) {
            if (stripBmp == null || stripBmp.getWidth() != W || stripBmp.getHeight() != stripH) { stripBmp = Bitmap.createBitmap(W, Math.max(1, stripH), Bitmap.Config.ARGB_8888); }
            stripBmp = Bitmap.createBitmap(W, Math.max(1, stripH), Bitmap.Config.ARGB_8888);
            drawStrip(new Canvas(stripBmp));
            stripDirty = false;
        }
        c.drawBitmap(stripBmp, 0, 0, null);
        if (mode != 0) { drawPanel(c); return; }
        if (stripPressed >= 0) drawStripPress(c);
        if (pressed != null && popAlts == null && !cursorMode) {
            drawKey(c, pressed, true);
            if (bubble && pressed.isChar()) drawBubble(c, pressed);
        }
        if (cursorMode && pressed != null) drawKey(c, pressed, true);
        if (popAlts != null) drawPopup(c);
    }

    void drawBase(Canvas c) {
        int W = getWidth(), H = getHeight();
        p.reset(); p.setAntiAlias(true);
        p.setShader(new LinearGradient(0, 0, 0, H, th.bg1, th.bg2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        if (th.leather && leather != null) {
            p.setShader(new BitmapShader(leather, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            p.setAlpha(255); c.drawRect(0, 0, W, H, p);
        }
        // filigrane de pattes
        p.setShader(null); p.setColor(0x0FFFFFFF);
        for (float y = stripH + 20 * dp; y < H; y += 74 * dp) for (float x = ((int) (y / dp) % 2 == 0 ? 20 : 56) * dp; x < W; x += 92 * dp) paw(c, x, y, 9 * dp, p);
        // bande des suggestions
        p.setShader(new LinearGradient(0, 0, 0, stripH, new int[]{0x40FFFFFF, 0x10FFFFFF, 0x00000000, 0x30000000}, new float[]{0, .48f, .5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, stripH, p);
        p.setShader(null);
        Paint ne = new Paint(Paint.ANTI_ALIAS_FLAG);
        ne.setStrokeWidth(2 * dp); ne.setColor(th.acc); ne.setShadowLayer(8 * dp, 0, 0, th.acc);
        c.drawLine(0, stripH, W, stripH, ne);
        if (mode == 0) for (List<KbLayouts.Key> r : rows) for (KbLayouts.Key k : r) drawKey(c, k, false);
    }

    void paw(Canvas c, float x, float y, float r, Paint p) {
        c.drawOval(x - r, y - r * .2f, x + r, y + r * 1.2f, p);
        float t = r * .42f;
        c.drawCircle(x - r * 1.05f, y - r * .55f, t, p); c.drawCircle(x - r * .4f, y - r * 1.15f, t, p);
        c.drawCircle(x + r * .4f, y - r * 1.15f, t, p); c.drawCircle(x + r * 1.05f, y - r * .55f, t, p);
    }

    boolean showToolbar() { return sugg == null || sugg.isEmpty(); }
    static final String[] TOOLS = {"😊", "📋", "⚙️", "🔽"};

    void drawStrip(Canvas c) {
        int W = getWidth();
        tp.reset(); tp.setAntiAlias(true);
        if (showToolbar()) {
            float x0 = 8 * dp;
            if (logo != null) { c.drawBitmap(logo, null, new RectF(x0, (stripH - 34 * dp) / 2, x0 + 34 * dp, (stripH + 34 * dp) / 2), null); }
            tp.setTypeface(tfHead); tp.setTextSize(14 * dp); tp.setColor(Color.WHITE); tp.setShadowLayer(8 * dp, 0, 0, th.acc);
            c.drawText(ime.brandText(), x0 + 42 * dp, stripH / 2f + 5 * dp, tp);
            tp.clearShadowLayer();
            float cw = 48 * dp;
            tp.setTextSize(22 * dp); tp.setTypeface(Typeface.DEFAULT); tp.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < TOOLS.length; i++) {
                float cx = W - (TOOLS.length - i) * cw + cw / 2 - 4 * dp;
                c.drawText(TOOLS[i], cx, stripH / 2f + 8 * dp, tp);
            }
            tp.setTextAlign(Paint.Align.LEFT);
            return;
        }
        int n = Math.min(3, sugg.size());
        float cw = W / 3f;
        int[] order = n == 1 ? new int[]{-1, 0, -1} : n == 2 ? new int[]{1, 0, -1} : new int[]{1, 0, 2};
        tp.setTypeface(tf); tp.setTextAlign(Paint.Align.CENTER);
        for (int s = 0; s < 3; s++) {
            int i = order[s];
            if (s > 0) { Paint sep = new Paint(); sep.setColor(0x40FFFFFF); c.drawRect(s * cw, stripH * .25f, s * cw + 1 * dp, stripH * .75f, sep); }
            if (i < 0 || i >= sugg.size()) continue;
            String w = sugg.get(i);
            boolean main = s == 1;
            tp.setTextSize((main ? 18 : 16) * dp);
            tp.setColor(main ? Color.WHITE : 0xFFE6D8F5);
            if (main) tp.setShadowLayer(8 * dp, 0, 0, th.acc); else tp.clearShadowLayer();
            String t = TextUtils.ellipsize(w, new android.text.TextPaint(tp), cw - 12 * dp, TextUtils.TruncateAt.END).toString();
            c.drawText(t, s * cw + cw / 2, stripH / 2f + 6 * dp, tp);
        }
        tp.clearShadowLayer(); tp.setTextAlign(Paint.Align.LEFT);
    }
    int[] stripOrder() { int n = Math.min(3, sugg.size()); return n == 1 ? new int[]{-1, 0, -1} : n == 2 ? new int[]{1, 0, -1} : new int[]{1, 0, 2}; }

    void drawStripPress(Canvas c) {
        int W = getWidth();
        RectF r;
        if (showToolbar()) { float cw = 48 * dp; float cx = W - (TOOLS.length - stripPressed) * cw + cw / 2 - 4 * dp; r = new RectF(cx - 22 * dp, 4 * dp, cx + 22 * dp, stripH - 4 * dp); }
        else { float cw = W / 3f; r = new RectF(stripPressed * cw + 4 * dp, 4 * dp, (stripPressed + 1) * cw - 4 * dp, stripH - 4 * dp); }
        p.reset(); p.setAntiAlias(true); p.setColor((th.acc & 0x00FFFFFF) | 0x55000000);
        c.drawRoundRect(r, 12 * dp, 12 * dp, p);
    }

    int keyKind(KbLayouts.Key k) {
        if (k.code == KbLayouts.ENTER) return 2;
        if (k.code == KbLayouts.CH || k.code == KbLayouts.SPACE) return 0;
        return 1;
    }

    void drawKey(Canvas c, KbLayouts.Key k, boolean down) {
        RectF r = new RectF(k.x, k.y, k.x + k.kw, k.y + k.kh);
        float rad = 11 * dp;
        int kind = keyKind(k);
        boolean shiftOn = k.code == KbLayouts.SHIFT && ime.shift > 0;
        // ombre
        p.reset(); p.setAntiAlias(true); p.setColor(0xB0000000);
        c.drawRoundRect(new RectF(r.left, r.top + 2.5f * dp, r.right, r.bottom + 2.5f * dp), rad, rad, p);
        // corps
        int top, bot;
        if (down) { top = mix(th.acc, 0xFFFFFFFF, .25f); bot = mix(th.acc, 0xFF000000, .55f); }
        else if (kind == 2 || shiftOn) { top = mix(th.acc, 0xFFFFFFFF, .1f); bot = mix(th.acc, 0xFF000000, .6f); }
        else if (kind == 1) { top = mix(th.key1, th.acc, .32f); bot = mix(th.key2, th.acc, .18f); }
        else { top = th.key1; bot = th.key2; }
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, top, bot, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, p);
        if (th.leather && kind == 0 && !down && leather != null) {
            Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG); lp.setShader(new BitmapShader(leather, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)); lp.setAlpha(150);
            c.drawRoundRect(r, rad, rad, lp);
        }
        // reflet verre (moitié haute)
        RectF g = new RectF(r.left + 1.5f * dp, r.top + 1.5f * dp, r.right - 1.5f * dp, r.top + r.height() * .5f);
        p.setShader(new LinearGradient(0, g.top, 0, g.bottom, 0x70FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(g, rad - 2 * dp, rad - 2 * dp, p);
        p.setShader(null);
        // lueur basse (néon)
        p.setShader(new RadialGradient(r.centerX(), r.bottom + r.height() * .1f, r.width() * .7f, new int[]{(th.acc & 0x00FFFFFF) | (down ? 0x90000000 : 0x38000000), 0}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        // bordure
        Paint b = new Paint(Paint.ANTI_ALIAS_FLAG); b.setStyle(Paint.Style.STROKE); b.setStrokeWidth(1.2f * dp);
        b.setColor(down || kind == 2 || shiftOn ? 0xFFFFFFFF : 0x80FFFFFF);
        if (kind == 2 || shiftOn) b.setShadowLayer(4 * dp, 0, 0, th.acc);
        c.drawRoundRect(r, rad, rad, b);
        // libellé
        String label = labelOf(k);
        tp.reset(); tp.setAntiAlias(true); tp.setTextAlign(Paint.Align.CENTER); tp.setColor(Color.WHITE);
        boolean emoji = k.code == KbLayouts.EMOJI || (k.code == KbLayouts.ENTER && ime.enterIsEmoji());
        if (k.code == KbLayouts.SPACE) {
            tp.setTypeface(tf); tp.setTextSize(12.5f * dp); tp.setColor(0xCCFFFFFF);
            c.drawText(ime.spaceLabel(), r.centerX(), r.centerY() + 4.5f * dp, tp);
            Paint u = new Paint(Paint.ANTI_ALIAS_FLAG); u.setStrokeWidth(2.5f * dp); u.setStrokeCap(Paint.Cap.ROUND); u.setColor(th.acc); u.setShadowLayer(6 * dp, 0, 0, th.acc);
            c.drawLine(r.left + r.width() * .3f, r.bottom - 7 * dp, r.right - r.width() * .3f, r.bottom - 7 * dp, u);
            return;
        }
        float size;
        if (k.isChar()) { tp.setTypeface(tf); size = Math.min(k.kh * .46f, 26 * dp); }
        else if (emoji) { tp.setTypeface(Typeface.DEFAULT); size = 22 * dp; }
        else if (label.length() > 2) { tp.setTypeface(tfHead); size = 12.5f * dp; }
        else { tp.setTypeface(Typeface.DEFAULT_BOLD); size = 24 * dp; }
        tp.setTextSize(size);
        if (!emoji) tp.setShadowLayer(4 * dp, 0, 0, down ? Color.WHITE : th.acc);
        Paint.FontMetrics fm = tp.getFontMetrics();
        c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, tp);
        tp.clearShadowLayer();
        if (hints && k.hint != null && !down) {
            tp.setTypeface(tf); tp.setTextSize(10 * dp); tp.setColor(th.acc2 | 0xFF000000); tp.setTextAlign(Paint.Align.RIGHT);
            c.drawText(k.hint, r.right - 5 * dp, r.top + 12 * dp, tp);
        } else if (k.alts != null && k.alts.length > 0 && k.isChar() && !down && k.hint == null) {
            Paint d = new Paint(Paint.ANTI_ALIAS_FLAG); d.setColor(0x66FFFFFF);
            c.drawCircle(r.right - 6 * dp, r.top + 6 * dp, 1.6f * dp, d);
        }
    }

    String labelOf(KbLayouts.Key k) {
        switch (k.code) {
            case KbLayouts.SHIFT: return ime.shift == 2 ? "⇪" : "⇧";
            case KbLayouts.ENTER: return ime.enterLabel();
            case KbLayouts.CH: return ime.shift > 0 && k.label.length() == 1 ? k.label.toUpperCase() : k.label;
            default: return k.label;
        }
    }

    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    void drawBubble(Canvas c, KbLayouts.Key k) {
        float bw = Math.max(k.kw * 1.25f, 52 * dp), bh = k.kh * 1.3f;
        float cx = k.x + k.kw / 2;
        RectF r = new RectF(cx - bw / 2, k.y - bh - 4 * dp, cx + bw / 2, k.y - 4 * dp);
        if (r.top < 0) r.offset(0, -r.top);
        if (r.left < 0) r.offset(-r.left, 0);
        if (r.right > getWidth()) r.offset(getWidth() - r.right, 0);
        p.reset(); p.setAntiAlias(true);
        p.setShadowLayer(14 * dp, 0, 4 * dp, 0xCC000000);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, mix(th.acc, 0xFFFFFFFF, .3f), mix(th.acc, 0xFF000000, .5f), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 14 * dp, 14 * dp, p);
        p.clearShadowLayer(); p.setShader(new LinearGradient(0, r.top, 0, r.centerY(), 0x88FFFFFF, 0x10FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(r.left + 2 * dp, r.top + 2 * dp, r.right - 2 * dp, r.centerY()), 12 * dp, 12 * dp, p);
        Paint b = new Paint(Paint.ANTI_ALIAS_FLAG); b.setStyle(Paint.Style.STROKE); b.setStrokeWidth(2 * dp); b.setColor(Color.WHITE); b.setShadowLayer(8 * dp, 0, 0, th.acc);
        c.drawRoundRect(r, 14 * dp, 14 * dp, b);
        tp.reset(); tp.setAntiAlias(true); tp.setTypeface(tf); tp.setTextAlign(Paint.Align.CENTER); tp.setColor(Color.WHITE); tp.setTextSize(Math.min(bh * .55f, 36 * dp));
        tp.setShadowLayer(10 * dp, 0, 0, th.acc);
        Paint.FontMetrics fm = tp.getFontMetrics();
        c.drawText(labelOf(k), r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, tp);
    }

    void openPopup(KbLayouts.Key k) {
        String[] alts = k.alts;
        if (alts == null || alts.length == 0) return;
        popKey = k; popAlts = alts;
        float cw = Math.max(k.kw, 40 * dp), ch = k.kh * 1.05f;
        float w = cw * alts.length;
        float cx = k.x + k.kw / 2;
        float left = cx - cw / 2;
        if (left + w > getWidth() - 4 * dp) left = getWidth() - 4 * dp - w;
        if (left < 4 * dp) left = 4 * dp;
        float top = k.y - ch - 6 * dp;
        if (top < 0) top = 0;
        popRect = new RectF(left, top, left + w, top + ch);
        popSel = 0;
        invalidate();
    }

    void drawPopup(Canvas c) {
        RectF r = popRect;
        p.reset(); p.setAntiAlias(true); p.setShadowLayer(16 * dp, 0, 5 * dp, 0xDD000000);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, mix(th.key1, 0xFFFFFFFF, .12f), th.key2, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 14 * dp, 14 * dp, p); p.clearShadowLayer(); p.setShader(null);
        Paint b = new Paint(Paint.ANTI_ALIAS_FLAG); b.setStyle(Paint.Style.STROKE); b.setStrokeWidth(2 * dp); b.setColor(th.acc); b.setShadowLayer(8 * dp, 0, 0, th.acc);
        c.drawRoundRect(r, 14 * dp, 14 * dp, b);
        float cw = r.width() / popAlts.length;
        for (int i = 0; i < popAlts.length; i++) {
            RectF cell = new RectF(r.left + i * cw + 3 * dp, r.top + 3 * dp, r.left + (i + 1) * cw - 3 * dp, r.bottom - 3 * dp);
            if (i == popSel) {
                p.setShader(new LinearGradient(0, cell.top, 0, cell.bottom, mix(th.acc, 0xFFFFFFFF, .3f), mix(th.acc, 0xFF000000, .45f), Shader.TileMode.CLAMP));
                c.drawRoundRect(cell, 10 * dp, 10 * dp, p); p.setShader(null);
            }
            tp.reset(); tp.setAntiAlias(true); tp.setTypeface(tf); tp.setTextAlign(Paint.Align.CENTER); tp.setColor(Color.WHITE); tp.setTextSize(Math.min(cell.height() * .5f, 24 * dp));
            tp.setShadowLayer(6 * dp, 0, 0, i == popSel ? Color.WHITE : th.acc);
            String s = ime.shift > 0 ? popAlts[i].toUpperCase() : popAlts[i];
            Paint.FontMetrics fm = tp.getFontMetrics();
            c.drawText(s, cell.centerX(), cell.centerY() - (fm.ascent + fm.descent) / 2, tp);
        }
    }

    // ------------------------------------------------------------------ panneaux emojis / presse-papiers
    float panelTop() { return stripH + 4 * dp; }
    float panelBottom() { return getHeight() - navInset - keyH - 12 * dp; }

    void drawPanel(Canvas c) {
        int W = getWidth();
        float top = panelTop(), bot = panelBottom();
        c.save();
        c.clipRect(0, top, W, bot);
        if (mode == 1) {
            String[] list = ime.emojiList(emoTab);
            int cols = 8; float cell = W / (float) cols;
            int rowsN = (list.length + cols - 1) / cols;
            scrollMax = Math.max(0, rowsN * cell - (bot - top));
            tp.reset(); tp.setAntiAlias(true); tp.setTextAlign(Paint.Align.CENTER); tp.setTextSize(cell * .58f);
            for (int i = 0; i < list.length; i++) {
                float x = (i % cols) * cell + cell / 2, y = top + (i / cols) * cell - scroll;
                if (y + cell < top || y > bot) continue;
                c.drawText(list[i], x, y + cell * .7f, tp);
            }
            if (list.length == 0) { tp.setTextSize(15 * dp); tp.setColor(0xCCFFFFFF); tp.setTypeface(tf); c.drawText("Pas encore d'emojis récents 🐾", W / 2f, (top + bot) / 2, tp); }
        } else {
            float rh = 54 * dp;
            scrollMax = Math.max(0, clips.size() * rh - (bot - top));
            tp.reset(); tp.setAntiAlias(true); tp.setTypeface(tf); tp.setTextSize(14.5f * dp); tp.setColor(Color.WHITE);
            for (int i = 0; i < clips.size(); i++) {
                float y = top + i * rh - scroll;
                if (y + rh < top || y > bot) continue;
                RectF r = new RectF(8 * dp, y + 4 * dp, W - 8 * dp, y + rh - 2 * dp);
                p.reset(); p.setAntiAlias(true);
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, th.key1, th.key2, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, 12 * dp, 12 * dp, p); p.setShader(null);
                Paint b = new Paint(Paint.ANTI_ALIAS_FLAG); b.setStyle(Paint.Style.STROKE); b.setStrokeWidth(1 * dp); b.setColor(0x66FFFFFF);
                c.drawRoundRect(r, 12 * dp, 12 * dp, b);
                String t = TextUtils.ellipsize(clips.get(i).replace('\n', ' '), new android.text.TextPaint(tp), r.width() - 24 * dp, TextUtils.TruncateAt.END).toString();
                c.drawText(t, r.left + 12 * dp, r.centerY() + 5 * dp, tp);
            }
            if (clips.isEmpty()) { tp.setTextAlign(Paint.Align.CENTER); tp.setColor(0xCCFFFFFF); c.drawText("La gamelle à copier est vide 🦴", W / 2f, (top + bot) / 2, tp); }
        }
        c.restore();
        // barre du bas du panneau
        float y = bot + 6 * dp;
        String[] bar = mode == 1 ? KbLayouts.EMO_TABS : new String[]{"🗑️"};
        float side = 4 * dp, bw = 64 * dp;
        drawPanelKey(c, new RectF(side, y, side + bw, y + keyH), "ABC", false, true);
        drawPanelKey(c, new RectF(W - side - bw, y, W - side, y + keyH), "⌫", false, true);
        float aw = (W - 2 * side - 2 * bw - 8 * dp) / bar.length;
        for (int i = 0; i < bar.length; i++) {
            RectF r = new RectF(side + bw + 4 * dp + i * aw + 2 * dp, y + 6 * dp, side + bw + 4 * dp + (i + 1) * aw - 2 * dp, y + keyH - 6 * dp);
            drawPanelKey(c, r, mode == 2 ? "Vider" : bar[i], mode == 1 && i == emoTab, false);
        }
    }

    void drawPanelKey(Canvas c, RectF r, String label, boolean on, boolean special) {
        p.reset(); p.setAntiAlias(true);
        int top = on ? mix(th.acc, 0xFFFFFFFF, .2f) : special ? mix(th.key1, th.acc, .3f) : th.key1;
        int bot = on ? mix(th.acc, 0xFF000000, .5f) : th.key2;
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, top, bot, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 10 * dp, 10 * dp, p); p.setShader(null);
        Paint b = new Paint(Paint.ANTI_ALIAS_FLAG); b.setStyle(Paint.Style.STROKE); b.setStrokeWidth(1.2f * dp); b.setColor(on ? Color.WHITE : 0x70FFFFFF);
        c.drawRoundRect(r, 10 * dp, 10 * dp, b);
        tp.reset(); tp.setAntiAlias(true); tp.setTextAlign(Paint.Align.CENTER); tp.setColor(Color.WHITE);
        boolean text = label.matches("[A-Za-z]+");
        tp.setTypeface(text ? tfHead : Typeface.DEFAULT_BOLD); tp.setTextSize((text ? 12 : 20) * dp);
        Paint.FontMetrics fm = tp.getFontMetrics();
        c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, tp);
    }

    // ================================================================== TOUCHER
    KbLayouts.Key keyAt(float x, float y) {
        KbLayouts.Key best = null; float bd = Float.MAX_VALUE;
        for (List<KbLayouts.Key> r : rows) for (KbLayouts.Key k : r) {
            float dx = x < k.x ? k.x - x : x > k.x + k.kw ? x - k.x - k.kw : 0;
            float dy = y < k.y ? k.y - y : y > k.y + k.kh ? y - k.y - k.kh : 0;
            float d = dx * dx + dy * dy * 1.4f;
            if (d < bd) { bd = d; best = k; }
        }
        return bd < (30 * dp) * (30 * dp) ? best : null;
    }

    final Runnable longPress = new Runnable() { @Override public void run() { onLong(); } };
    final Runnable repeat = new Runnable() {
        int n = 0;
        @Override public void run() {
            if (pressed == null || pressed.code != KbLayouts.DEL) { n = 0; return; }
            longDone = true;
            if (n++ > 18) ime.deleteWord(); else ime.deleteChar();
            ime.feedback(pressed);
            h.postDelayed(this, n > 18 ? 220 : 55);
        }
    };

    void onLong() {
        if (pressed == null) return;
        longDone = true;
        if (pressed.code == KbLayouts.SPACE) { ime.pickIme(); pressed = null; invalidate(); return; }
        if (pressed.isChar() && ",".equals(pressed.out) && (pressed.alts == null || ime.commaOpensSettings())) { ime.openSettings(); pressed = null; invalidate(); return; }
        if (pressed.code == KbLayouts.SYM || pressed.code == KbLayouts.ABC) { ime.openSettings(); pressed = null; invalidate(); return; }
        if (pressed.isChar() && pressed.alts != null) { ime.vibe(18); openPopup(pressed); }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (mode != 0) return panelTouch(e);
        float x, y;
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                x = e.getX(idx); y = e.getY(idx);
                if (pressed != null && a == MotionEvent.ACTION_POINTER_DOWN) finish(); // frappe rapide à deux pouces
                if (y < stripH) { stripPressed = stripIndex(x); pointerId = e.getPointerId(idx); invalidate(); return true; }
                pointerId = e.getPointerId(idx);
                pressed = keyAt(x, y);
                downX = x; downY = y; lastCurX = x; longDone = false; cursorMode = false; delWord = false; popAlts = null;
                if (pressed == null) return true;
                ime.feedback(pressed);
                if (pressed.code == KbLayouts.DEL) { ime.deleteChar(); h.postDelayed(repeat, 420); }
                else h.postDelayed(longPress, pressed.code == KbLayouts.SPACE ? 650 : 380);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = e.findPointerIndex(pointerId);
                if (idx < 0) return true;
                x = e.getX(idx); y = e.getY(idx);
                if (stripPressed >= 0) return true;
                if (pressed == null) return true;
                if (popAlts != null) {
                    float cw = popRect.width() / popAlts.length;
                    int s = (int) ((x - popRect.left) / cw);
                    s = Math.max(0, Math.min(popAlts.length - 1, s));
                    if (s != popSel) { popSel = s; invalidate(); }
                    return true;
                }
                if (pressed.code == KbLayouts.SPACE) {
                    if (!cursorMode && Math.abs(x - downX) > 16 * dp) { cursorMode = true; h.removeCallbacks(longPress); }
                    if (cursorMode) { float step = 11 * dp; while (x - lastCurX > step) { ime.moveCursor(1); lastCurX += step; } while (lastCurX - x > step) { ime.moveCursor(-1); lastCurX -= step; } }
                    return true;
                }
                if (pressed.code == KbLayouts.DEL) { if (downX - x > 50 * dp) { delWord = true; h.removeCallbacks(repeat); } return true; }
                if (Math.hypot(x - downX, y - downY) > 14 * dp && pressed.isChar()) {
                    KbLayouts.Key k = keyAt(x, y);
                    if (k != null && k != pressed && k.isChar()) { pressed = k; downX = x; downY = y; h.removeCallbacks(longPress); h.postDelayed(longPress, 380); invalidate(); }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                if (e.getPointerId(idx) != pointerId) return true;
                if (stripPressed >= 0) { int s = stripPressed; stripPressed = -1; invalidate(); onStrip(s); return true; }
                finish();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPress); h.removeCallbacks(repeat); pressed = null; popAlts = null; stripPressed = -1; invalidate();
                return true;
        }
        return true;
    }

    void finish() {
        h.removeCallbacks(longPress); h.removeCallbacks(repeat);
        KbLayouts.Key k = pressed;
        pressed = null;
        if (k == null) { invalidate(); return; }
        if (popAlts != null) {
            String s = popAlts[popSel];
            popAlts = null;
            ime.typeText(ime.shift > 0 ? s.toUpperCase() : s);
            invalidate();
            return;
        }
        if (cursorMode) { cursorMode = false; invalidate(); return; }
        if (longDone && k.code != KbLayouts.DEL) { invalidate(); return; }
        if (k.code == KbLayouts.DEL) { if (delWord) ime.deleteWord(); invalidate(); return; }
        if (k.code == KbLayouts.SHIFT) {
            long now = System.currentTimeMillis();
            if (now - lastShift < 320) ime.setShift(2); else ime.setShift(ime.shift == 0 ? 1 : 0);
            lastShift = now;
        } else ime.onKey(k);
        invalidate();
    }

    int stripIndex(float x) {
        int W = getWidth();
        if (showToolbar()) {
            float cw = 48 * dp;
            for (int i = 0; i < TOOLS.length; i++) { float cx = W - (TOOLS.length - i) * cw + cw / 2 - 4 * dp; if (Math.abs(x - cx) < cw / 2) return i; }
            return -1;
        }
        return Math.max(0, Math.min(2, (int) (x / (W / 3f))));
    }
    void onStrip(int s) {
        if (s < 0) return;
        ime.vibe(10);
        if (showToolbar()) { ime.onTool(s); return; }
        int i = stripOrder()[s];
        if (i >= 0 && i < sugg.size()) ime.pickSuggestion(sugg.get(i));
    }

    boolean panelTouch(MotionEvent e) {
        int W = getWidth();
        float x = e.getX(), y = e.getY();
        float top = panelTop(), bot = panelBottom();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; scrolling = false; scrollStartY = y; scrollStart = scroll;
                if (y > bot && x > W - 70 * dp) { ime.deleteChar(); ime.vibe(10); }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (y >= top && y <= bot && Math.abs(y - downY) > 10 * dp) scrolling = true;
                if (scrolling) { scroll = Math.max(0, Math.min(scrollMax, scrollStart - (y - scrollStartY))); invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                if (scrolling) return true;
                if (y < stripH) { int s = stripIndex(x); if (s >= 0) onStrip(s); return true; }
                if (y > bot) {
                    float side = 4 * dp, bw = 64 * dp;
                    if (x < side + bw) { ime.vibe(10); ime.backToKeys(); return true; }
                    if (x > W - side - bw) return true;
                    if (mode == 1) {
                        float aw = (W - 2 * side - 2 * bw - 8 * dp) / KbLayouts.EMO_TABS.length;
                        int t = (int) ((x - side - bw - 4 * dp) / aw);
                        if (t >= 0 && t < KbLayouts.EMO_TABS.length) { emoTab = t; scroll = 0; ime.vibe(8); invalidate(); }
                    } else { ime.clearClips(); invalidate(); }
                    return true;
                }
                if (mode == 1) {
                    String[] list = ime.emojiList(emoTab);
                    int cols = 8; float cell = W / (float) cols;
                    int i = (int) ((y - top + scroll) / cell) * cols + (int) (x / cell);
                    if (i >= 0 && i < list.length) { ime.vibe(8); ime.typeEmoji(list[i]); }
                } else {
                    int i = (int) ((y - top + scroll) / (54 * dp));
                    if (i >= 0 && i < clips.size()) { ime.vibe(8); ime.typeText(clips.get(i)); }
                }
                return true;
        }
        return true;
    }
}

package fr.piika.puppyphone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Fond animé « peint » : plusieurs calques détaillés (fond, halos néon, décor, héros) précalculés en haute
 * définition, avec parallaxe, halos teintés aux couleurs du pelage, et des effets vivants dessinés à chaque image
 * (étoiles qui scintillent, aurore, pluie, guirlande, jetpack, respiration du chiot…). Coordonnées « design » : 1188 × 2400.
 */
final class LayeredScene implements PupLiveWallpaper.Scene {
    static final float DW = 1188, DH = 2400;
    static final Map<String, Object[]> CACHE = new HashMap<>();
    static final String[] NAMES = {"bg", "glowa", "glowb", "mid", "glowc", "fg"};

    final Context ctx;
    final String id;
    final int acc, acc2;
    JSONObject fx = new JSONObject();
    final Bitmap[] bmp = new Bitmap[NAMES.length];
    final RectF[] dst = new RectF[NAMES.length];
    final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint glowP = new Paint(Paint.FILTER_BITMAP_FLAG), glowP2 = new Paint(Paint.FILTER_BITMAP_FLAG), addP = new Paint(Paint.FILTER_BITMAP_FLAG);
    final Random rnd = new Random();
    int W, H; float s, ox, margin, fmax;
    float pbg, pmid, pfg;

    LayeredScene(Context c, String id) {
        ctx = c; this.id = id; acc = Pelage.acc(c); acc2 = Pelage.acc2(c);
        PorterDuffXfermode ADD = new PorterDuffXfermode(PorterDuff.Mode.ADD);
        glowP.setColorFilter(new PorterDuffColorFilter(acc, PorterDuff.Mode.SRC_IN)); glowP.setXfermode(ADD);
        glowP2.setColorFilter(new PorterDuffColorFilter(acc2, PorterDuff.Mode.SRC_IN)); glowP2.setXfermode(ADD);
        addP.setXfermode(ADD);
        load();
    }

    void load() {
        synchronized (CACHE) {
            Object[] o = CACHE.get(id);
            if (o == null) {
                CACHE.clear(); // une seule scène en mémoire à la fois
                Bitmap[] b = new Bitmap[NAMES.length];
                JSONObject f = new JSONObject();
                try (InputStream in = ctx.getAssets().open("walls/" + id + "/fx.json")) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    f = new JSONObject(bo.toString("UTF-8"));
                } catch (Exception ignored) { }
                JSONObject L = f.optJSONObject("layers");
                for (int i = 0; i < NAMES.length; i++) {
                    if (L == null || !L.has(NAMES[i])) continue;
                    try (InputStream in = ctx.getAssets().open("walls/" + id + "/" + NAMES[i] + ".webp")) { b[i] = BitmapFactory.decodeStream(in); } catch (Exception ignored) { }
                }
                o = new Object[]{b, f};
                CACHE.put(id, o);
            }
            Bitmap[] b = (Bitmap[]) o[0];
            System.arraycopy(b, 0, bmp, 0, b.length);
            fx = (JSONObject) o[1];
        }
        JSONObject L = fx.optJSONObject("layers");
        for (int i = 0; i < NAMES.length; i++) {
            JSONArray r = L == null ? null : L.optJSONArray(NAMES[i]);
            if (r != null) dst[i] = new RectF((float) r.optDouble(0), (float) r.optDouble(1), (float) (r.optDouble(0) + r.optDouble(2)), (float) (r.optDouble(1) + r.optDouble(3)));
        }
        JSONObject par = fx.optJSONObject("par");
        pbg = par == null ? .03f : (float) par.optDouble("bg", .03); pmid = par == null ? .08f : (float) par.optDouble("mid", .08); pfg = par == null ? .15f : (float) par.optDouble("fg", .15);
        fmax = Math.max(pfg, .01f);
    }

    @Override public void size(int w, int h) {
        W = w; H = h;
        s = Math.max(h / DH, w / (DW - 108));
        ox = (w - DW * s) / 2f;
        margin = Math.max(0, (DW * s - w) / 2f);
        initFx();
    }

    // ------------------------------------------------------------ effets
    float[][] snow, snowIn, rain, smoke = new float[0][], drops;
    final ArrayList<float[]> puffs = new ArrayList<>();
    float shootT = -10, nextShoot = 5, shX, shY;
    Bitmap[] strip;

    void initFx() {
        if (fx.has("aurora") && strip == null) { int[] cols = {0xFF3DFFB0, acc2, acc}; strip = new Bitmap[3]; for (int i = 0; i < 3; i++) strip[i] = strip(cols[i]); }
        if ("aurore".equals(id)) { snow = new float[70][4]; for (float[] f : snow) { f[0] = rnd.nextFloat(); f[1] = rnd.nextFloat(); f[2] = .3f + rnd.nextFloat(); f[3] = rnd.nextFloat() * 6.28f; } }
        if (fx.has("snowbox")) { snowIn = new float[46][4]; for (float[] f : snowIn) { f[0] = rnd.nextFloat(); f[1] = rnd.nextFloat(); f[2] = .3f + rnd.nextFloat(); f[3] = rnd.nextFloat() * 6.28f; } }
        if (fx.has("rain")) { rain = new float[110][3]; for (float[] r : rain) { r[0] = rnd.nextFloat(); r[1] = rnd.nextFloat(); r[2] = .6f + rnd.nextFloat() * .8f; } }
        if (fx.has("window")) { drops = new float[26][4]; for (float[] d : drops) { d[0] = rnd.nextFloat(); d[1] = rnd.nextFloat(); d[2] = .02f + rnd.nextFloat() * .06f; d[3] = 2 + rnd.nextFloat() * 4; } }
    }
    static Bitmap strip(int col) {
        Bitmap b = Bitmap.createBitmap(1, 256, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 256; y++) {
            float k = y / 255f, al;
            if (k < .78f) al = (float) Math.pow(k / .78f, 2.2) * .55f; else if (k < .9f) al = .55f + (k - .78f) / .12f * .45f; else al = Math.max(0, 1 - (k - .9f) / .1f);
            int c = k > .82f ? PupLiveWallpaper.mix(col, 0xFFFFFFFF, (k - .82f) * 2.5f) : col;
            b.setPixel(0, y, PupLiveWallpaper.a(c, (int) (al * 255)));
        }
        return b;
    }

    /** Applique la transformation d'un calque (parallaxe propre à sa profondeur). */
    void layer(Canvas c, float px, float f) { c.translate(ox - px * margin * (f / fmax), 0); c.scale(s, s); }

    @Override public void draw(Canvas c, float t, float dt, float xOff) {
        float px = (xOff - .5f) * 2f;
        c.drawColor(0xFF000000);
        // fond + scintillements
        c.save(); layer(c, px, pbg);
        draw(c, 0, bp);
        float pulse = .85f + .15f * (float) Math.sin(t * .7);
        glowP.setAlpha((int) (255 * pulse)); glowP2.setAlpha((int) (255 * (1.85f - pulse)));
        draw(c, 1, glowP); draw(c, 2, glowP2);
        twinkles(c, t); windows(c, t); shooting(c, t);
        if (strip != null) aurora(c, t, 255, false);
        if (drops != null) windowRain(c, t, dt);
        if (snowIn != null) snowBox(c, t, dt);
        c.restore();
        // décor
        c.save(); layer(c, px, pmid);
        draw(c, 3, bp);
        if (strip != null) aurora(c, t, 80, true);
        float fl = 1f; // néon qui grésille de temps en temps
        float ph = t % 7.3f; if (ph < .35f) fl = (rnd.nextFloat() < .5f) ? .25f : 1f;
        glowP.setAlpha((int) (255 * fl)); draw(c, 4, glowP);
        bulbs(c, t);
        c.restore();
        // héros
        c.save(); layer(c, px, pfg);
        hero(c, t, dt);
        c.restore();
        // effets d'écran (devant tout)
        if (snow != null) snow(c, t, dt);
        if (rain != null) rain(c, t, dt, px);
    }
    void draw(Canvas c, int i, Paint paint) { if (bmp[i] != null && dst[i] != null) c.drawBitmap(bmp[i], null, dst[i], paint); }

    void twinkles(Canvas c, float t) {
        JSONArray a = fx.optJSONArray("twinkle"); if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONArray q = a.optJSONArray(i); float x = (float) q.optDouble(0), y = (float) q.optDouble(1), r = (float) q.optDouble(2); int type = q.optInt(3);
            if (type == 2) { boolean on = ((t + i * .37f) % 2.2f) < .25f; if (!on) continue; p.setShader(new RadialGradient(x, y, 18, 0xCCFF2A5A, 0, Shader.TileMode.CLAMP)); c.drawCircle(x, y, 18, p); p.setShader(null); continue; }
            float tw = (float) (.5 + .5 * Math.sin(t * (1.2 + (i % 5) * .3) + i * 1.7));
            int glow = PupLiveWallpaper.a(type == 1 ? acc2 : 0xFFBFD8FF, (int) (150 * tw));
            PupDraw.sparkle(c, p, x, y, r * (type == 1 ? 1.6f : 1.1f) * (.6f + .6f * tw), PupLiveWallpaper.a(0xFFFFFFFF, (int) (90 + 165 * tw)), glow);
        }
    }
    void windows(Canvas c, float t) {
        JSONArray a = fx.optJSONArray("windows"); if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONArray q = a.optJSONArray(i); boolean on = Math.sin(t * .35 + i * 2.1) > -.2;
            if (!on) { p.setColor(0xFF0D0618); c.drawRect((float) q.optDouble(0) - 3, (float) q.optDouble(1) - 4, (float) q.optDouble(0) + 3, (float) q.optDouble(1) + 4, p); }
        }
    }
    void bulbs(Canvas c, float t) {
        JSONArray a = fx.optJSONArray("bulbs"); if (a == null) return;
        int[] cols = {acc, acc2, 0xFFFFC864};
        for (int i = 0; i < a.length(); i++) {
            JSONArray q = a.optJSONArray(i); float x = (float) q.optDouble(0), y = (float) q.optDouble(1);
            float k = (float) (.55 + .45 * Math.sin(t * 2.2 - i * .8)); int col = cols[q.optInt(2) % 3];
            p.setShader(new RadialGradient(x, y, 80, new int[]{PupLiveWallpaper.a(col, (int) (220 * k)), PupLiveWallpaper.a(col, (int) (90 * k)), 0}, new float[]{0, .18f, 1}, Shader.TileMode.CLAMP));
            p.setXfermode(addP.getXfermode()); c.drawCircle(x, y, 80, p); p.setXfermode(null); p.setShader(null);
            p.setColor(PupLiveWallpaper.mix(col, 0xFFFFFFFF, .7f)); c.drawOval(x - 6, y - 9, x + 6, y + 9, p);
        }
    }
    void shooting(Canvas c, float t) {
        if (!("cosmos".equals(id) || "aurore".equals(id) || "lune".equals(id))) return;
        if (t > nextShoot) { shootT = t; nextShoot = t + 7 + rnd.nextFloat() * 9; shX = DW * (.3f + rnd.nextFloat() * .6f); shY = DH * (.04f + rnd.nextFloat() * .25f); }
        float k = (t - shootT) / .9f; if (k < 0 || k > 1) return;
        float hx = shX - DW * .45f * k, hy = shY + DH * .1f * k, al = (float) Math.sin(Math.PI * k);
        p.setStrokeWidth(3.5f); p.setStrokeCap(Paint.Cap.ROUND);
        p.setShader(new LinearGradient(hx + DW * .2f, hy - DH * .045f, hx, hy, 0, PupLiveWallpaper.a(0xFFFFFFFF, (int) (230 * al)), Shader.TileMode.CLAMP));
        c.drawLine(hx + DW * .2f, hy - DH * .045f, hx, hy, p); p.setShader(null);
        PupDraw.sparkle(c, p, hx, hy, 7, PupLiveWallpaper.a(0xFFFFFFFF, (int) (255 * al)), PupLiveWallpaper.a(acc2, (int) (160 * al)));
    }

    /** Aurore boréale (rideaux additifs). reflet = dans le lac, écrasée et pâle. */
    void aurora(Canvas c, float t, int alpha, boolean reflet) {
        JSONArray lake = fx.optJSONArray("lake");
        float ly = lake == null ? DH * .7f : (float) lake.optDouble(0);
        c.save();
        if (reflet) { c.clipRect(0, ly, DW, DH); c.scale(1, -.6f, 0, ly); } else c.clipRect(0, 0, DW, ly);
        int cols = 200; float cw = DW * 1.3f / cols, x0 = -DW * .15f;
        Rect src = new Rect(0, 0, 1, 256); RectF d = new RectF();
        float[] ph = {0, 2.1f, 4.2f}, yb = {.2f, .15f, .27f}, hb = {.17f, .12f, .1f}, st = {1f, .75f, .6f};
        for (int r = 0; r < 3; r++) for (int i = 0; i < cols; i++) {
            float k = i / (float) cols;
            float base = DH * (yb[r] + .045f * (float) Math.sin(k * 7 + t * .22f + ph[r]) + .02f * (float) Math.sin(k * 19 - t * .37f + ph[r] * 2));
            float hh = DH * hb[r] * (.55f + .45f * (float) Math.sin(k * 5 + t * .3f + ph[r]));
            float al = st[r] * (.35f + .65f * (float) Math.pow(.5 + .5 * Math.sin(k * 23 + t * .9f + ph[r] * 3), 2)) * Math.min(1f, Math.min(k, 1 - k) * 6);
            d.set(x0 + i * cw, base - hh, x0 + (i + 1) * cw + .6f, base + hh * .12f);
            addP.setAlpha((int) (alpha * al));
            c.drawBitmap(strip[r], src, d, addP);
        }
        addP.setAlpha(255);
        c.restore();
    }

    void windowRain(Canvas c, float t, float dt) {
        JSONArray w = fx.optJSONArray("window"); if (w == null) return;
        float x0 = (float) w.optDouble(0), y0 = (float) w.optDouble(1), x1 = (float) w.optDouble(2), y1 = (float) w.optDouble(3);
        c.save(); c.clipRect(x0, y0, x1, y1);
        for (float[] q : drops) {
            q[1] += q[2] * dt * (q[1] > .0f && rnd.nextFloat() < .02f ? 0 : 1);
            if (q[1] > 1.05f) { q[1] = -.05f; q[0] = rnd.nextFloat(); }
            float x = x0 + q[0] * (x1 - x0), y = y0 + q[1] * (y1 - y0), r = q[3];
            p.setColor(0x33BFD8FF); c.drawRect(x - r * .3f, y - r * 10, x + r * .3f, y, p);
            p.setShader(new RadialGradient(x - r * .3f, y - r * .3f, r * 1.3f, new int[]{0xCCFFFFFF, 0x66A8C8FF, 0x22000000}, null, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, p); p.setShader(null);
        }
        c.restore();
    }

    void hero(Canvas c, float t, float dt) {
        if (bmp[5] == null || dst[5] == null) return;
        JSONArray anc = fx.optJSONArray("fgAnchor");
        float ax = anc == null ? dst[5].centerX() : (float) anc.optDouble(0), ay = anc == null ? dst[5].centerY() : (float) anc.optDouble(1);
        c.save();
        if ("cosmos".equals(id)) {
            float dy = (float) Math.sin(t * .55) * 26, dx = (float) Math.sin(t * .23) * 14, rot = (float) Math.sin(t * .37) * 3.5f;
            c.translate(dx, dy); c.rotate(rot, ax, ay);
            thrusters(c, t, dt, true);
            c.drawBitmap(bmp[5], null, dst[5], bp);
            JSONArray an = fx.optJSONArray("antenna");
            if (an != null) {
                boolean on = (t % 1.3f) < .55f; float x = (float) an.optDouble(0), y = (float) an.optDouble(1);
                p.setShader(new RadialGradient(x, y, 46, new int[]{0xFFFFFFFF, PupLiveWallpaper.a(acc, on ? 230 : 70), 0}, new float[]{0, .25f, 1}, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, 46, p); p.setShader(null);
            }
            c.restore();
            return;
        }
        JSONArray br = fx.optJSONArray("breath");
        if (br != null) { float k = 1 + (float) br.optDouble(2) * (float) Math.sin(t * 1.5); c.scale(1 + (k - 1) * .4f, k, (float) br.optDouble(0), (float) br.optDouble(1)); }
        c.drawBitmap(bmp[5], null, dst[5], bp);
        c.restore();
        JSONArray z = fx.optJSONArray("zzz");
        if (z != null) zzz(c, t, (float) z.optDouble(0), (float) z.optDouble(1));
    }

    void thrusters(Canvas c, float t, float dt, boolean flames) {
        JSONArray th = fx.optJSONArray("thrusters"); if (th == null) return;
        for (int i = 0; i < th.length(); i++) {
            JSONArray q = th.optJSONArray(i); float x = (float) q.optDouble(0), y = (float) q.optDouble(1);
            if (rnd.nextFloat() < .8f) puffs.add(new float[]{x + (rnd.nextFloat() - .5f) * 30, y, (rnd.nextFloat() - .5f) * 40, 120 + rnd.nextFloat() * 80, 0});
            float f = .8f + rnd.nextFloat() * .4f;
            p.setShader(new RadialGradient(x, y + 40 * f, 90 * f, new int[]{0xFFFFFFFF, PupLiveWallpaper.mix(acc2, 0xFFFFFFFF, .3f), PupLiveWallpaper.a(acc2, 0)}, new float[]{0, .3f, 1}, Shader.TileMode.CLAMP));
            c.drawOval(x - 44 * f, y - 10, x + 44 * f, y + 150 * f, p); p.setShader(null);
        }
        for (int i = puffs.size() - 1; i >= 0; i--) {
            float[] q = puffs.get(i); q[4] += dt;
            if (q[4] > 1.8f || puffs.size() > 80) { puffs.remove(i); continue; }
            q[0] += q[2] * dt; q[1] += q[3] * dt;
            float al = 1 - q[4] / 1.8f;
            p.setColor(PupLiveWallpaper.a(PupLiveWallpaper.mix(acc2, 0xFFFFFFFF, .5f), (int) (90 * al)));
            c.drawCircle(q[0], q[1], 8 + q[4] * 34, p);
        }
    }

    void zzz(Canvas c, float t, float x, float y) {
        p.setTypeface(Typeface.DEFAULT_BOLD);
        for (int i = 0; i < 3; i++) {
            float k = ((t * .35f) + i / 3f) % 1f;
            p.setTextSize(30 + k * 40); p.setColor(PupLiveWallpaper.a(0xFFFFFFFF, (int) (220 * Math.sin(Math.PI * k))));
            p.setShadowLayer(14, 0, 0, acc);
            c.drawText("z", x + k * 90 + (float) Math.sin(t * 2 + i) * 10, y - k * 260, p);
        }
        p.clearShadowLayer();
    }

    /** Neige qui tombe derrière une fenêtre (coordonnées de la scène). */
    void snowBox(Canvas c, float t, float dt) {
        JSONArray w = fx.optJSONArray("snowbox"); if (w == null) return;
        float x0 = (float) w.optDouble(0), y0 = (float) w.optDouble(1), x1 = (float) w.optDouble(2), y1 = (float) w.optDouble(3);
        c.save(); c.clipRect(x0, y0, x1, y1);
        for (float[] f : snowIn) {
            f[1] += f[2] * dt * .05f; f[0] += (float) Math.sin(t * .8 + f[3]) * dt * .012f;
            if (f[1] > 1.02f) { f[1] = -.02f; f[0] = rnd.nextFloat(); }
            float r = 1.6f + f[2] * 2.6f;
            p.setColor(PupLiveWallpaper.a(0xFFFFFFFF, (int) (120 + 110 * f[2] / 1.3f))); c.drawCircle(x0 + f[0] * (x1 - x0), y0 + f[1] * (y1 - y0), r, p);
        }
        c.restore();
    }

    void snow(Canvas c, float t, float dt) {
        for (float[] f : snow) {
            f[1] += f[2] * dt * .04f; f[0] += (float) Math.sin(t * .8 + f[3]) * dt * .01f;
            if (f[1] > 1.02f) { f[1] = -.02f; f[0] = rnd.nextFloat(); }
            float r = (1.2f + f[2] * 2.2f) * W / 400f;
            p.setColor(PupLiveWallpaper.a(0xFFFFFFFF, (int) (110 + 120 * f[2] / 1.3f))); c.drawCircle(f[0] * W, f[1] * H, r, p);
        }
    }

    void rain(Canvas c, float t, float dt, float px) {
        p.setStrokeWidth(Math.max(1f, W / 600f)); p.setStrokeCap(Paint.Cap.ROUND);
        JSONArray fl = fx.optJSONArray("floor"); float floorY = fl == null ? H * .6f : ((float) fl.optDouble(0)) * s;
        for (float[] r : rain) {
            r[1] += r[2] * dt * 1.4f;
            if (r[1] > 1.05f) {
                // petit rond dans une flaque
                float rx = r[0] * W, ry = floorY + (H - floorY) * rnd.nextFloat();
                puffs.add(new float[]{rx, ry, 0, 0, -1});
                r[1] = -.1f; r[0] = rnd.nextFloat();
            }
            float x = r[0] * W - r[1] * H * .08f, y = r[1] * H, L = 40 * r[2] * W / 1080f;
            p.setColor(PupLiveWallpaper.a(r[2] > 1.1f ? acc2 : 0xFFBFD8FF, (int) (70 * r[2])));
            c.drawLine(x, y, x - L * .12f, y + L, p);
        }
        p.setStyle(Paint.Style.STROKE);
        for (int i = puffs.size() - 1; i >= 0; i--) {
            float[] q = puffs.get(i); if (q[4] > -0.5f && q[4] >= 0) continue;
            q[2] += dt; if (q[2] > .7f) { puffs.remove(i); continue; }
            float k = q[2] / .7f;
            p.setColor(PupLiveWallpaper.a(acc, (int) (120 * (1 - k)))); p.setStrokeWidth(2);
            c.drawOval(q[0] - 40 * k, q[1] - 9 * k, q[0] + 40 * k, q[1] + 9 * k, p);
        }
        p.setStyle(Paint.Style.FILL);
    }
}

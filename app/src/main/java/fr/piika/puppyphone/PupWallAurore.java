package fr.piika.puppyphone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * « Aurore des pattes » : nuit noire OLED, aurore boréale qui ondule en rideaux lumineux,
 * montagnes en silhouette, lac miroir, lucioles, et un chiot assis sur son rocher qui regarde le ciel
 * en remuant la queue. Dessiné par calcul, en pleine résolution.
 */
public class PupWallAurore extends PupLiveWallpaper {
    @Override Scene scene(Context c) { return new Aurore(c); }

    static final class Aurore implements Scene {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bp = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Paint add = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Random rnd = new Random(7);
        final int acc, acc2;
        int W, H; float u, hor;
        Bitmap[] strip = new Bitmap[3];
        int[] ribCol;
        Path[] hills = new Path[3];
        float[][] stars, flies;
        float shootT = -10, nextShoot = 5, shX, shY;

        Aurore(Context c) { acc = Pelage.acc(c); acc2 = Pelage.acc2(c); add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); }

        @Override public void size(int w, int h) {
            W = w; H = h; u = Math.min(w, h) / 400f; hor = H * .7f;
            ribCol = new int[]{0xFF3DFFB0, acc2, acc};
            for (int i = 0; i < 3; i++) strip[i] = strip(ribCol[i]);
            float[] base = {.6f, .655f, .7f}, amp = {.12f, .08f, .05f};
            for (int l = 0; l < 3; l++) hills[l] = ridge(base[l], amp[l], 3 + l);
            stars = new float[160][4];
            for (float[] s : stars) { s[0] = rnd.nextFloat() * 1.1f - .05f; s[1] = (float) Math.pow(rnd.nextFloat(), 1.4) * .62f; s[2] = rnd.nextFloat() * 6.28f; s[3] = .5f + rnd.nextFloat() * 1.3f; }
            flies = new float[38][4];
            for (float[] f : flies) { f[0] = rnd.nextFloat(); f[1] = .62f + rnd.nextFloat() * .36f; f[2] = rnd.nextFloat() * 6.28f; f[3] = .5f + rnd.nextFloat(); }
        }

        /** Bande verticale du rideau : transparent en haut → couleur → bord inférieur éclatant → rien. */
        Bitmap strip(int col) {
            Bitmap b = Bitmap.createBitmap(1, 256, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 256; y++) {
                float k = y / 255f, al;
                if (k < .78f) al = (float) Math.pow(k / .78f, 2.2) * .55f;
                else if (k < .9f) al = .55f + (k - .78f) / .12f * .45f;
                else al = Math.max(0, 1 - (k - .9f) / .1f);
                int c = k > .82f ? PupLiveWallpaper.mix(col, 0xFFFFFFFF, (k - .82f) * 2.5f) : col;
                b.setPixel(0, y, PupLiveWallpaper.a(c, (int) (al * 255)));
            }
            return b;
        }

        Path ridge(float base, float amp, int seed) {
            Random r = new Random(seed);
            int n = 64; float[] y = new float[n + 1];
            for (int i = 0; i <= n; i++) y[i] = 0;
            float a = amp;
            for (int oct = 1; oct <= 5; oct++) {
                float ph = r.nextFloat() * 6.28f;
                for (int i = 0; i <= n; i++) y[i] += (float) Math.sin(i / (float) n * Math.PI * 2 * oct * 1.3 + ph) * a;
                a *= .5f;
            }
            Path pa = new Path();
            float w = W * 1.3f, x0 = -W * .15f;
            pa.moveTo(x0, H);
            for (int i = 0; i <= n; i++) pa.lineTo(x0 + w * i / n, H * (base - Math.abs(y[i]) * .9f));
            pa.lineTo(x0 + w, H); pa.close();
            return pa;
        }

        @Override public void draw(Canvas c, float t, float dt, float xOff) {
            float par = (xOff - .5f) * W;
            // ciel : noir OLED en haut, nuit indigo, lueur d'horizon
            p.setShader(new LinearGradient(0, 0, 0, hor, new int[]{0xFF000000, 0xFF000000, 0xFF050818, PupLiveWallpaper.mix(0xFF06202A, acc2, .15f)}, new float[]{0, .35f, .75f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, hor, p); p.setShader(null);
            // étoiles
            for (float[] s : stars) {
                float tw = (float) (.55 + .45 * Math.sin(t * 1.4 + s[2]));
                p.setColor(PupLiveWallpaper.a(0xFFFFFFFF, (int) (60 + 170 * tw * (1 - s[1]))));
                c.drawCircle(s[0] * W - par * .04f, s[1] * H, s[3] * u * .7f, p);
            }
            shooting(c, t);
            // aurore (rideaux additifs) + son reflet dans le lac
            c.save(); c.clipRect(0, 0, W, hor);
            aurora(c, t, par);
            c.restore();
            // montagnes
            float[] pf = {.05f, .1f, .18f};
            int[] top = {0xFF0A0F1E, 0xFF060914, 0xFF020308};
            for (int l = 0; l < 3; l++) {
                c.save(); c.translate(-par * pf[l], 0);
                p.setShader(new LinearGradient(0, H * .45f, 0, hor, top[l], 0xFF000000, Shader.TileMode.CLAMP));
                c.drawPath(hills[l], p); p.setShader(null);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(u * (l == 0 ? 1.2f : .8f));
                p.setColor(PupLiveWallpaper.a(l == 0 ? 0xFF3DFFB0 : acc2, l == 0 ? 70 : 40)); c.drawPath(hills[l], p);
                p.setStyle(Paint.Style.FILL);
                c.restore();
            }
            // lac miroir
            p.setColor(0xFF000000); c.drawRect(0, hor, W, H, p);
            c.save(); c.clipRect(0, hor, W, H);
            c.scale(1, -.6f, 0, hor);
            add.setAlpha(70);
            aurora(c, t, par);
            add.setAlpha(255);
            c.restore();
            for (int i = 0; i < 14; i++) { // vaguelettes
                float y = hor + (H - hor) * (i + .5f) / 14f, w = W * (.2f + .5f * (float) Math.abs(Math.sin(i * 1.7 + t * .3)));
                float x = (W - w) / 2 + (float) Math.sin(t * .4 + i) * W * .1f;
                p.setColor(PupLiveWallpaper.a(i % 2 == 0 ? 0xFF3DFFB0 : acc2, 28)); c.drawRect(x, y, x + w, y + u * .8f, p);
            }
            p.setShader(new LinearGradient(0, hor - 2 * u, 0, hor + 3 * u, 0, PupLiveWallpaper.a(0xFF3DFFB0, 60), Shader.TileMode.MIRROR));
            c.drawRect(0, hor - 2 * u, W, hor + 2 * u, p); p.setShader(null);
            pup(c, t, par);
            fireflies(c, t, par);
        }

        void aurora(Canvas c, float t, float par) {
            int cols = 110;
            float cw = W * 1.3f / cols, x0 = -W * .15f - par * .07f;
            Rect src = new Rect(0, 0, 1, 256);
            RectF dst = new RectF();
            float[] ph = {0, 2.1f, 4.2f}, yb = {.33f, .27f, .4f}, hb = {.24f, .18f, .14f}, str = {1f, .75f, .6f};
            for (int r = 0; r < 3; r++) {
                for (int i = 0; i < cols; i++) {
                    float k = i / (float) cols;
                    float base = H * (yb[r] + .055f * (float) Math.sin(k * 7 + t * .22f + ph[r]) + .025f * (float) Math.sin(k * 19 - t * .37f + ph[r] * 2));
                    float hh = H * hb[r] * (.55f + .45f * (float) Math.sin(k * 5 + t * .3f + ph[r]));
                    float al = str[r] * (.35f + .65f * (float) Math.pow(.5 + .5 * Math.sin(k * 23 + t * .9f + ph[r] * 3), 2));
                    al *= Math.min(1f, Math.min(k, 1 - k) * 6);
                    dst.set(x0 + i * cw, base - hh, x0 + (i + 1) * cw, base + hh * .12f);
                    int prev = add.getAlpha();
                    add.setAlpha((int) (prev * al));
                    c.drawBitmap(strip[r], src, dst, add);
                    add.setAlpha(prev);
                }
            }
        }

        void shooting(Canvas c, float t) {
            if (t > nextShoot) { shootT = t; nextShoot = t + 8 + rnd.nextFloat() * 10; shX = W * (.3f + rnd.nextFloat() * .6f); shY = H * (.04f + rnd.nextFloat() * .18f); }
            float k = (t - shootT) / .8f; if (k < 0 || k > 1) return;
            float hx = shX - W * .4f * k, hy = shY + H * .08f * k, al = (float) Math.sin(Math.PI * k);
            p.setStrokeWidth(1.4f * u);
            p.setShader(new LinearGradient(hx + W * .15f, hy - H * .03f, hx, hy, 0, PupLiveWallpaper.a(0xFFFFFFFF, (int) (220 * al)), Shader.TileMode.CLAMP));
            c.drawLine(hx + W * .15f, hy - H * .03f, hx, hy, p); p.setShader(null);
        }

        /** Chiot assis (silhouette avec liseré de lumière d'aurore) sur un rocher au bord du lac. */
        void pup(Canvas c, float t, float par) {
            float s = 36 * u, x = W * .72f - par * .2f, y = hor + 2 * u;
            c.save(); c.translate(x, y);
            // rocher
            Path rock = new Path(); rock.moveTo(-s * 2.2f, 0); rock.cubicTo(-s * 2f, -s * .9f, -s * .8f, -s * 1.3f, s * .2f, -s * 1.15f);
            rock.cubicTo(s * 1.4f, -s * 1.1f, s * 2.1f, -s * .6f, s * 2.4f, 0); rock.close();
            p.setShader(new LinearGradient(0, -s * 1.3f, 0, 0, 0xFF0B1220, 0xFF000000, Shader.TileMode.CLAMP)); c.drawPath(rock, p); p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(u); p.setColor(PupLiveWallpaper.a(0xFF3DFFB0, 70)); c.drawPath(rock, p); p.setStyle(Paint.Style.FILL);
            c.translate(0, -s * 1.15f);
            // queue qui remue
            c.save(); c.rotate((float) Math.sin(t * 3.2) * 14, s * .55f, -s * .2f);
            Path tail = new Path(); tail.moveTo(s * .5f, -s * .15f); tail.cubicTo(s * 1.2f, -s * .2f, s * 1.3f, -s * .9f, s * 1.05f, -s * 1.15f);
            tail.cubicTo(s * 1.15f, -s * .75f, s * .95f, -s * .4f, s * .45f, -s * .38f); tail.close();
            silhouette(c, tail); c.restore();
            // corps assis vu de dos, tête ronde levée vers le ciel, oreilles tombantes
            Path body = new Path();
            body.moveTo(-s * .78f, 0); body.cubicTo(-s * .9f, -s * .7f, -s * .55f, -s * 1.35f, -s * .28f, -s * 1.75f);
            body.lineTo(s * .28f, -s * 1.75f); body.cubicTo(s * .55f, -s * 1.35f, s * .9f, -s * .7f, s * .78f, 0); body.close();
            Path head = new Path(); head.addOval(new RectF(-s * .52f, -s * 2.72f, s * .52f, -s * 1.7f), Path.Direction.CW);
            Path earL = new Path(); earL.addOval(new RectF(-s * .2f, -s * .5f, s * .2f, s * .5f), Path.Direction.CW);
            android.graphics.Matrix em = new android.graphics.Matrix(); em.setRotate(18); em.postTranslate(-s * .5f, -s * 2.05f); earL.transform(em);
            Path earR = new Path(); earR.addOval(new RectF(-s * .2f, -s * .5f, s * .2f, s * .5f), Path.Direction.CW);
            em.setRotate(-18); em.postTranslate(s * .5f, -s * 2.05f); earR.transform(em);
            body.op(head, Path.Op.UNION); body.op(earL, Path.Op.UNION); body.op(earR, Path.Op.UNION);
            silhouette(c, body);
            // collier + médaille lumineuse aux couleurs du pelage
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * .1f); p.setColor(PupLiveWallpaper.a(acc, 200));
            c.drawArc(new RectF(-s * .36f, -s * 1.95f, s * .36f, -s * 1.6f), 20, 140, false, p); p.setStyle(Paint.Style.FILL);
            float g = (float) (.7 + .3 * Math.sin(t * 2));
            p.setShader(new RadialGradient(0, -s * 1.62f, s * .5f, new int[]{PupLiveWallpaper.a(0xFFFFFFFF, (int) (255 * g)), PupLiveWallpaper.a(acc, (int) (200 * g)), 0}, new float[]{0, .15f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(0, -s * 1.62f, s * .5f, p); p.setShader(null);
            c.restore();
        }
        void silhouette(Canvas c, Path pa) {
            p.setColor(0xFF000000); c.drawPath(pa, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.3f * u); p.setColor(PupLiveWallpaper.a(0xFF3DFFB0, 150)); c.drawPath(pa, p);
            p.setStrokeWidth(3.5f * u); p.setColor(PupLiveWallpaper.a(acc2, 40)); c.drawPath(pa, p);
            p.setStyle(Paint.Style.FILL);
        }

        void fireflies(Canvas c, float t, float par) {
            for (float[] f : flies) {
                float x = f[0] * W + (float) Math.sin(t * .3 * f[3] + f[2]) * 22 * u - par * .25f;
                float y = f[1] * H + (float) Math.cos(t * .25 * f[3] + f[2] * 2) * 14 * u;
                float tw = (float) Math.max(0, Math.sin(t * 1.2 * f[3] + f[2]));
                if (tw < .05f) continue;
                p.setShader(new RadialGradient(x, y, 6 * u, PupLiveWallpaper.a(0xFFD8FF7A, (int) (120 * tw)), 0, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, 6 * u, p); p.setShader(null);
                p.setColor(PupLiveWallpaper.a(0xFFFFFFE0, (int) (230 * tw))); c.drawCircle(x, y, .9f * u, p);
            }
        }
    }
}

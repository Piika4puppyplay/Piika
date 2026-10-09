package fr.piika.puppyphone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.Random;

/**
 * « Pup dans l'espace » : noir OLED profond, nébuleuse, trois couches d'étoiles en parallaxe,
 * planète à anneaux, lune cratérisée, constellation de la Patte, satellite-os et un chiot astronaute
 * qui flotte avec son jetpack. Tout est dessiné par calcul (aucune image externe), en pleine résolution.
 */
public class PupWallCosmos extends PupLiveWallpaper {
    @Override Scene scene(Context c) { return new Cosmos(c); }

    static final class Cosmos implements Scene {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Random rnd = new Random(42);
        final int acc, acc2;
        final float dens;
        int W, H;
        float u;
        Bitmap nebula, planetBands, moon, pupOpen, pupBlink;
        float[][] mid, near, bright, consto;
        final ArrayList<float[]> puffs = new ArrayList<>();
        float shootT = -10, shootX, shootY, shootA, nextShoot = 4;

        Cosmos(Context c) { acc = Pelage.acc(c); acc2 = Pelage.acc2(c); dens = c.getResources().getDisplayMetrics().density; }

        @Override public void size(int w, int h) {
            W = w; H = h; u = Math.min(w, h) / 400f; // unité ≈ 1/400 de la largeur
            buildNebula(); buildFar(); buildPlanet(); buildMoon(); buildPup();
            mid = new float[110][4]; near = new float[34][4];
            for (float[] s : mid) { s[0] = rnd.nextFloat() * 1.2f - .1f; s[1] = rnd.nextFloat(); s[2] = rnd.nextFloat() * 6.28f; s[3] = .7f + rnd.nextFloat() * .8f; }
            for (float[] s : near) { s[0] = rnd.nextFloat() * 1.3f - .15f; s[1] = rnd.nextFloat() * .85f; s[2] = rnd.nextFloat() * 6.28f; s[3] = 1.2f + rnd.nextFloat() * 1.2f; }
            bright = new float[][]{{.12f, .14f, 0}, {.86f, .09f, 2}, {.7f, .33f, 4}, {.2f, .52f, 1}, {.9f, .62f, 3}};
            // constellation de la Patte (coussinet + 4 doigts), en haut à gauche
            float cx = .3f, cy = .2f, k = .085f;
            consto = new float[][]{{cx - k * .55f, cy + k * .55f}, {cx, cy + k * .9f}, {cx + k * .55f, cy + k * .55f}, {cx + k * .35f, cy + k * .2f}, {cx - k * .35f, cy + k * .2f},
                    {cx - k * 1.05f, cy - k * .15f}, {cx - k * .42f, cy - k * .62f}, {cx + k * .42f, cy - k * .62f}, {cx + k * 1.05f, cy - k * .15f}};
        }

        // ---------------------------------------------------------------- précalculs
        void buildNebula() {
            int nw = Math.max(64, W / 3), nh = Math.max(64, H / 3);
            nebula = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(nebula); Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            int[] cols = {acc, acc2, 0xFF6A3CFF, 0xFF1A3CFF, acc};
            for (int i = 0; i < 46; i++) {
                float k = rnd.nextFloat();                       // le long d'une bande diagonale (voie lactée)
                float x = nw * (-.1f + 1.2f * k) + (rnd.nextFloat() - .5f) * nw * .35f;
                float y = nh * (.85f - .7f * k) + (rnd.nextFloat() - .5f) * nh * .22f;
                float r = nw * (.12f + rnd.nextFloat() * .3f);
                int col = cols[rnd.nextInt(cols.length)];
                q.setShader(new RadialGradient(x, y, r, PupLiveWallpaper.a(col, 10 + rnd.nextInt(26)), 0, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, r, q);
            }
            q.setShader(null);
            for (int i = 0; i < 2600; i++) { // poussière d'étoiles
                float k = rnd.nextFloat();
                float x = nw * (-.1f + 1.2f * k) + (float) rnd.nextGaussian() * nw * .09f, y = nh * (.85f - .7f * k) + (float) rnd.nextGaussian() * nh * .05f;
                q.setColor(PupLiveWallpaper.a(0xFFFFFFFF, 18 + rnd.nextInt(60))); c.drawPoint(x, y, q);
            }
            for (int i = 0; i < 26; i++) { // filaments sombres
                float k = rnd.nextFloat(); float x = nw * k, y = nh * (.85f - .7f * k) + (rnd.nextFloat() - .5f) * nh * .06f;
                q.setShader(new RadialGradient(x, y, nw * .07f, 0x66000000, 0, Shader.TileMode.CLAMP)); c.drawCircle(x, y, nw * .07f, q);
            }
        }
        float[][] farPts = new float[4][]; float[] farSz = new float[4]; int[] farCol = new int[4];
        void buildFar() {
            // 520 étoiles lointaines en 4 lots (taille/éclat), dessinées d'un coup par le GPU
            int[] tints = {0xFFFFFFFF, 0xFFBFD8FF, 0xFFFFE2C2, 0xFFFFFFFF};
            for (int g = 0; g < 4; g++) {
                int n = 130; float[] pts = new float[n * 2];
                for (int i = 0; i < n; i++) { pts[i * 2] = rnd.nextFloat() * W * 1.12f - W * .06f; pts[i * 2 + 1] = rnd.nextFloat() * H; }
                farPts[g] = pts; farSz[g] = (.7f + g * .35f) * u; farCol[g] = PupLiveWallpaper.a(tints[g], 70 + g * 45);
            }
        }
        void buildPlanet() {
            int bw = 512, bh = 256;
            planetBands = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(planetBands); Paint q = new Paint();
            int base = PupLiveWallpaper.mix(acc2, 0xFF0A0420, .55f), hi = PupLiveWallpaper.mix(acc2, 0xFFFFFFFF, .15f), dark = PupLiveWallpaper.mix(acc, 0xFF05020A, .6f);
            c.drawColor(base);
            float y = 0;
            while (y < bh) {
                float hgt = 4 + rnd.nextFloat() * 22;
                int col = rnd.nextInt(3) == 0 ? dark : rnd.nextBoolean() ? hi : PupLiveWallpaper.mix(base, acc, rnd.nextFloat() * .4f);
                q.setColor(PupLiveWallpaper.a(col, 90 + rnd.nextInt(120)));
                Path band = new Path(); band.moveTo(0, y);
                for (int x = 0; x <= bw; x += 16) band.lineTo(x, y + (float) Math.sin(x / 40.0 + y) * 3);
                for (int x = bw; x >= 0; x -= 16) band.lineTo(x, y + hgt + (float) Math.sin(x / 33.0 + y * 2) * 3);
                band.close(); c.drawPath(band, q);
                y += hgt;
            }
            // grande tache (tempête) répétable
            q.setShader(new RadialGradient(300, 150, 34, new int[]{PupLiveWallpaper.a(0xFFFFFFFF, 140), PupLiveWallpaper.a(acc, 160), 0}, null, Shader.TileMode.CLAMP));
            c.drawOval(250, 128, 350, 172, q);
        }
        void buildMoon() {
            int s = (int) (64 * u);
            moon = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(moon); Paint q = new Paint(Paint.ANTI_ALIAS_FLAG); float r = s / 2f;
            q.setShader(new RadialGradient(r * .7f, r * .6f, r * 1.3f, new int[]{0xFFF4F0FF, 0xFFB9B2CC, 0xFF3A3448, 0xFF0A0810}, new float[]{0, .45f, .85f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(r, r, r * .96f, q);
            for (int i = 0; i < 14; i++) {
                float cx = r + (rnd.nextFloat() - .5f) * r * 1.3f, cy = r + (rnd.nextFloat() - .5f) * r * 1.3f, cr = r * (.05f + rnd.nextFloat() * .14f);
                if (Math.hypot(cx - r, cy - r) + cr > r * .92f) continue;
                q.setShader(new RadialGradient(cx - cr * .3f, cy - cr * .3f, cr, new int[]{0x55000000, 0x22000000, 0x40FFFFFF}, new float[]{0, .7f, 1}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, cr, q);
            }
        }

        /** Chiot astronaute (précalculé en deux versions : yeux ouverts / clin d'œil). */
        void buildPup() { pupOpen = pupBitmap(false); pupBlink = pupBitmap(true); }
        Bitmap pupBitmap(boolean blink) {
            float S = 84 * u; int sz = (int) (S * 2.6f);
            Bitmap b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b); Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            c.translate(sz / 2f, sz * .42f);
            // jetpack (derrière)
            q.setShader(new LinearGradient(-S * .55f, 0, S * .55f, 0, new int[]{0xFF4A4458, 0xFFE8E4F2, 0xFF6B6480}, new float[]{0, .45f, 1}, Shader.TileMode.CLAMP));
            c.drawRoundRect(-S * .62f, S * .45f, S * .62f, S * 1.35f, S * .18f, S * .18f, q);
            for (int k = -1; k <= 1; k += 2) {
                q.setShader(new LinearGradient(k * S * .4f - S * .14f, 0, k * S * .4f + S * .14f, 0, 0xFF2A2438, 0xFFB9B2CC, Shader.TileMode.MIRROR));
                c.drawRoundRect(k * S * .4f - S * .16f, S * 1.25f, k * S * .4f + S * .16f, S * 1.5f, S * .05f, S * .05f, q);
            }
            // corps (combinaison)
            q.setShader(new RadialGradient(-S * .2f, S * .7f, S * 1.1f, new int[]{0xFFFFFFFF, 0xFFE9E5F2, 0xFF9C94B2}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            c.drawOval(-S * .58f, S * .55f, S * .58f, S * 1.55f, q);
            // bras + gants (patte)
            for (int k = -1; k <= 1; k += 2) {
                c.save(); c.rotate(k * (k < 0 ? 38 : -62), k * S * .5f, S * .8f);
                q.setShader(new LinearGradient(0, S * .7f, 0, S * 1.2f, 0xFFFFFFFF, 0xFFA59DBA, Shader.TileMode.CLAMP));
                c.drawRoundRect(k * S * .5f - S * .17f, S * .7f, k * S * .5f + S * .17f, S * 1.32f, S * .17f, S * .17f, q);
                q.setShader(new RadialGradient(k * S * .5f, S * 1.38f, S * .22f, PupLiveWallpaper.mix(acc, 0xFFFFFFFF, .3f), acc, Shader.TileMode.CLAMP));
                c.drawCircle(k * S * .5f, S * 1.38f, S * .19f, q);
                q.setShader(null); q.setColor(0x99FFFFFF);
                c.drawCircle(k * S * .5f, S * 1.42f, S * .06f, q);
                for (int d = -1; d <= 1; d++) c.drawCircle(k * S * .5f + d * S * .07f, S * 1.31f, S * .03f, q);
                c.restore();
            }
            // jambes / bottes
            for (int k = -1; k <= 1; k += 2) {
                q.setShader(new LinearGradient(0, S * 1.35f, 0, S * 1.85f, 0xFFF4F2FA, 0xFF8C84A2, Shader.TileMode.CLAMP));
                c.drawRoundRect(k * S * .26f - S * .17f, S * 1.3f, k * S * .26f + S * .17f, S * 1.8f, S * .15f, S * .15f, q);
                q.setShader(new LinearGradient(0, S * 1.7f, 0, S * 1.9f, acc2, PupLiveWallpaper.mix(acc2, 0xFF000000, .5f), Shader.TileMode.CLAMP));
                c.drawRoundRect(k * S * .26f - S * .2f, S * 1.7f, k * S * .26f + S * .22f, S * 1.9f, S * .09f, S * .09f, q);
            }
            // plastron aux couleurs du pelage + voyants
            q.setShader(new LinearGradient(0, S * .8f, 0, S * 1.2f, PupLiveWallpaper.mix(acc, 0xFFFFFFFF, .25f), PupLiveWallpaper.mix(acc, 0xFF000000, .25f), Shader.TileMode.CLAMP));
            c.drawRoundRect(-S * .26f, S * .82f, S * .26f, S * 1.16f, S * .06f, S * .06f, q); q.setShader(null);
            int[] led = {acc2, 0xFFFFD34D, 0xFF3DFFB0};
            for (int i = 0; i < 3; i++) { q.setColor(led[i]); c.drawCircle(-S * .14f + i * S * .14f, S * .92f, S * .035f, q); }
            q.setColor(0xCCFFFFFF); c.drawRoundRect(-S * .18f, S * 1.02f, S * .18f, S * 1.07f, S * .02f, S * .02f, q);
            // anneau de col en métal
            q.setShader(new LinearGradient(-S * .6f, 0, S * .6f, 0, new int[]{0xFF6B6480, 0xFFFFFFFF, 0xFF6B6480}, null, Shader.TileMode.CLAMP));
            c.drawOval(-S * .62f, S * .52f, S * .62f, S * .78f, q); q.setShader(null);
            // tête dans le casque
            PupDraw.head(c, q, S * .62f, blink, acc2);
            // casque en verre : teinte, reflets, rebord chromé
            q.setShader(new RadialGradient(-S * .3f, -S * .35f, S * 1.1f, new int[]{0x22FFFFFF, 0x10BFD8FF, 0x33BFD8FF}, new float[]{0, .7f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, S * .92f, q); q.setShader(null);
            q.setStyle(Paint.Style.STROKE);
            q.setStrokeWidth(S * .07f); q.setColor(0x99FFFFFF);
            c.drawArc(new RectF(-S * .78f, -S * .78f, S * .78f, S * .78f), 200, 62, false, q);
            q.setStrokeWidth(S * .03f); q.setColor(0x66FFFFFF);
            c.drawArc(new RectF(-S * .7f, -S * .7f, S * .7f, S * .7f), 275, 22, false, q);
            q.setStrokeWidth(S * .04f); q.setColor(PupLiveWallpaper.a(acc, 120));
            c.drawArc(new RectF(-S * .82f, -S * .82f, S * .82f, S * .82f), 20, 70, false, q);
            q.setShader(new LinearGradient(-S, -S, S, S, new int[]{0xFFFFFFFF, 0xFF6B6480, 0xFFE8E4F2, 0xFF4A4458}, null, Shader.TileMode.CLAMP));
            q.setStrokeWidth(S * .08f); c.drawCircle(0, 0, S * .94f, q); q.setShader(null);
            q.setStyle(Paint.Style.FILL);
            q.setColor(0xCCFFFFFF); c.drawCircle(-S * .45f, -S * .5f, S * .07f, q);
            // antenne
            q.setColor(0xFFB9B2CC); c.drawRect(S * .5f - S * .02f, -S * 1.2f, S * .5f + S * .02f, -S * .78f, q);
            return b;
        }

        // ---------------------------------------------------------------- dessin
        @Override public void draw(Canvas c, float t, float dt, float xOff) {
            float par = (xOff - .5f) * W;
            c.drawColor(0xFF000000); // noir OLED absolu
            // nébuleuse (respire doucement)
            bp.setAlpha((int) (215 + 40 * Math.sin(t * .2)));
            c.save(); c.translate(-par * .06f, 0);
            c.drawBitmap(nebula, null, new RectF(-W * .05f, -H * .03f, W * 1.05f, H * 1.03f), bp);
            c.restore(); bp.setAlpha(255);
            // étoiles lointaines
            c.save(); c.translate(-par * .03f, 0);
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int g = 0; g < 4; g++) { p.setStrokeWidth(farSz[g]); p.setColor(farCol[g]); c.drawPoints(farPts[g], p); }
            c.restore();
            // étoiles moyennes / proches qui scintillent
            p.setShader(null);
            for (float[] s : mid) {
                float tw = (float) (.55 + .45 * Math.sin(t * 1.3 + s[2]));
                p.setColor(PupLiveWallpaper.a(0xFFFFFFFF, (int) (70 + 160 * tw)));
                c.drawCircle(s[0] * W - par * .1f, s[1] * H, s[3] * u * .8f, p);
            }
            for (float[] s : near) {
                float tw = (float) (.5 + .5 * Math.sin(t * 2 + s[2]));
                float x = s[0] * W - par * .18f, y = s[1] * H, r = s[3] * u;
                p.setShader(new RadialGradient(x, y, r * 4, PupLiveWallpaper.a(s[2] > 3 ? acc2 : 0xFFBFD8FF, (int) (60 * tw)), 0, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, r * 4, p); p.setShader(null);
                p.setColor(PupLiveWallpaper.a(0xFFFFFFFF, (int) (140 + 115 * tw))); c.drawCircle(x, y, r * .9f, p);
            }
            // étoiles brillantes avec aigrettes
            for (float[] s : bright) {
                float tw = (float) (.6 + .4 * Math.sin(t * .9 + s[2]));
                float x = s[0] * W - par * .14f, y = s[1] * H, L = (14 + 10 * tw) * u;
                p.setStrokeWidth(u * .9f);
                for (int k = 0; k < 2; k++) {
                    p.setShader(new LinearGradient(x - L, y, x + L, y, new int[]{0, PupLiveWallpaper.a(0xFFFFFFFF, (int) (200 * tw)), 0}, null, Shader.TileMode.CLAMP));
                    if (k == 0) c.drawLine(x - L, y, x + L, y, p);
                    p.setShader(new LinearGradient(x, y - L, x, y + L, new int[]{0, PupLiveWallpaper.a(0xFFFFFFFF, (int) (200 * tw)), 0}, null, Shader.TileMode.CLAMP));
                    if (k == 1) c.drawLine(x, y - L, x, y + L, p);
                }
                p.setShader(null);
                PupDraw.sparkle(c, p, x, y, 3.2f * u * tw, 0xFFFFFFFF, PupLiveWallpaper.a(acc, (int) (110 * tw)));
            }
            // constellation de la Patte
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(u * .6f);
            float cpx = -par * .08f;
            int[][] links = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 0}};
            p.setColor(PupLiveWallpaper.a(acc2, (int) (55 + 25 * Math.sin(t * .6))));
            for (int[] l : links) c.drawLine(consto[l[0]][0] * W + cpx, consto[l[0]][1] * H, consto[l[1]][0] * W + cpx, consto[l[1]][1] * H, p);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < consto.length; i++) {
                float tw = (float) (.6 + .4 * Math.sin(t * 1.7 + i));
                PupDraw.sparkle(c, p, consto[i][0] * W + cpx, consto[i][1] * H, (i >= 5 ? 3.4f : 2.4f) * u * tw, 0xFFFFFFFF, PupLiveWallpaper.a(acc2, (int) (120 * tw)));
            }
            moon(c, t, par);
            satellite(c, t, par);
            shooting(c, t, dt);
            planet(c, t, par);
            astronaut(c, t, dt, par);
        }

        void moon(Canvas c, float t, float par) {
            float x = W * .8f - par * .2f - moon.getWidth() / 2f, y = H * .2f;
            p.setShader(new RadialGradient(x + moon.getWidth() / 2f, y + moon.getHeight() / 2f, moon.getWidth() * .9f, 0x33BFD8FF, 0, Shader.TileMode.CLAMP));
            c.drawCircle(x + moon.getWidth() / 2f, y + moon.getHeight() / 2f, moon.getWidth() * .9f, p); p.setShader(null);
            c.drawBitmap(moon, x, y, bp);
        }

        void satellite(Canvas c, float t, float par) {
            float k = (t * .012f) % 1f;
            float x = W * (-.15f + 1.3f * k) - par * .22f, y = H * (.36f - .1f * k) + (float) Math.sin(t * .5) * 6 * u;
            c.save(); c.translate(x, y); c.rotate(-20 + t * 6 % 360);
            float s = 9 * u;
            // panneaux solaires
            for (int d = -1; d <= 1; d += 2) {
                p.setShader(new LinearGradient(0, -s * .5f, 0, s * .5f, 0xFF1A3C9F, 0xFF07123A, Shader.TileMode.CLAMP));
                c.drawRect(d * s * 1.4f - s * 1.1f, -s * .45f, d * s * 1.4f + s * 1.1f, s * .45f, p); p.setShader(null);
                p.setColor(0x66BFD8FF); p.setStrokeWidth(u * .4f);
                for (int g = -2; g <= 2; g++) c.drawLine(d * s * 1.4f + g * s * .44f, -s * .45f, d * s * 1.4f + g * s * .44f, s * .45f, p);
            }
            // os central
            p.setColor(0xFFF6EEDF);
            c.drawRoundRect(-s * .7f, -s * .22f, s * .7f, s * .22f, s * .2f, s * .2f, p);
            for (int d = -1; d <= 1; d += 2) for (int e = -1; e <= 1; e += 2) c.drawCircle(d * s * .7f, e * s * .2f, s * .27f, p);
            p.setColor((t % 1.4f) < .2f ? acc : PupLiveWallpaper.a(acc, 90)); c.drawCircle(0, 0, s * .12f, p);
            c.restore();
        }

        void shooting(Canvas c, float t, float dt) {
            if (t > nextShoot) { shootT = t; nextShoot = t + 6 + rnd.nextFloat() * 9; shootX = W * (.2f + rnd.nextFloat() * .7f); shootY = H * (.05f + rnd.nextFloat() * .3f); shootA = (float) Math.toRadians(150 + rnd.nextFloat() * 20); }
            float k = (t - shootT) / .9f;
            if (k < 0 || k > 1) return;
            float len = W * .45f, hx = shootX + (float) Math.cos(shootA) * len * k, hy = shootY + (float) Math.sin(shootA) * len * k;
            float tx = hx - (float) Math.cos(shootA) * W * .18f, ty = hy - (float) Math.sin(shootA) * W * .18f;
            float al = (float) Math.sin(Math.PI * k);
            p.setStrokeWidth(1.6f * u); p.setStrokeCap(Paint.Cap.ROUND);
            p.setShader(new LinearGradient(tx, ty, hx, hy, 0, PupLiveWallpaper.a(0xFFFFFFFF, (int) (230 * al)), Shader.TileMode.CLAMP));
            c.drawLine(tx, ty, hx, hy, p); p.setShader(null);
            PupDraw.sparkle(c, p, hx, hy, 3 * u, PupLiveWallpaper.a(0xFFFFFFFF, (int) (255 * al)), PupLiveWallpaper.a(acc2, (int) (160 * al)));
        }

        void planet(Canvas c, float t, float par) {
            float R = W * .62f, cx = W * .08f - par * .25f, cy = H * .96f;
            RectF ring = new RectF(cx - R * 1.75f, cy - R * .42f, cx + R * 1.75f, cy + R * .42f);
            // halo d'atmosphère
            p.setShader(new RadialGradient(cx, cy, R * 1.25f, new int[]{0, 0, PupLiveWallpaper.a(acc2, 90), 0}, new float[]{0, .78f, .82f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, R * 1.25f, p); p.setShader(null);
            // anneaux (moitié arrière)
            c.save(); c.rotate(-16, cx, cy); c.clipRect(cx - R * 2, cy - R, cx + R * 2, cy); rings(c, ring); c.restore();
            // surface avec bandes qui tournent
            BitmapShader bs = new BitmapShader(planetBands, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
            Matrix m = new Matrix(); float sc = R * 2 / planetBands.getHeight();
            m.setScale(sc, sc); m.postRotate(-16); m.postTranslate(cx - R + (t * 6 * u) % (planetBands.getWidth() * sc), cy - R);
            bs.setLocalMatrix(m);
            p.setShader(bs); c.drawCircle(cx, cy, R, p);
            // ombre (terminateur) + reflet du soleil
            p.setShader(new RadialGradient(cx + R * .45f, cy - R * .5f, R * 1.6f, new int[]{0x22FFFFFF, 0x00000000, 0xCC000000, 0xF5000000}, new float[]{0, .35f, .7f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, R, p);
            p.setShader(new RadialGradient(cx, cy, R, new int[]{0, 0, PupLiveWallpaper.a(acc2, 150)}, new float[]{0, .9f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, R, p); p.setShader(null);
            // anneaux (moitié avant)
            c.save(); c.rotate(-16, cx, cy); c.clipRect(cx - R * 2, cy, cx + R * 2, cy + R); rings(c, ring); c.restore();
        }
        void rings(Canvas c, RectF r) {
            p.setStyle(Paint.Style.STROKE);
            float[][] rs = {{1f, 7, 110}, {.93f, 3, 60}, {.88f, 9, 140}, {.8f, 4, 70}, {.75f, 2, 120}};
            for (float[] q : rs) {
                RectF rr = new RectF(r.centerX() - r.width() / 2 * q[0], r.centerY() - r.height() / 2 * q[0], r.centerX() + r.width() / 2 * q[0], r.centerY() + r.height() / 2 * q[0]);
                p.setStrokeWidth(q[1] * u);
                p.setShader(new LinearGradient(rr.left, 0, rr.right, 0, new int[]{PupLiveWallpaper.a(acc, (int) q[2]), PupLiveWallpaper.a(0xFFFFFFFF, (int) q[2]), PupLiveWallpaper.a(acc2, (int) q[2] / 2)}, null, Shader.TileMode.CLAMP));
                c.drawOval(rr, p);
            }
            p.setShader(null); p.setStyle(Paint.Style.FILL);
        }

        void astronaut(Canvas c, float t, float dt, float par) {
            float x = W * .58f + (float) Math.sin(t * .11) * W * .1f - par * .12f, y = H * .5f + (float) Math.sin(t * .17) * H * .035f;
            float rot = (float) Math.sin(t * .23) * 9;
            Bitmap b = (t % 5.2f) > 5.0f ? pupBlink : pupOpen;
            float S = 84 * u;
            // fumée du jetpack (particules)
            double ang = Math.toRadians(rot + 90);
            for (int k = -1; k <= 1; k += 2) {
                float nx = x + (float) (Math.cos(Math.toRadians(rot)) * k * S * .4f - Math.sin(Math.toRadians(rot)) * S * 1.5f);
                float ny = y + (float) (Math.sin(Math.toRadians(rot)) * k * S * .4f + Math.cos(Math.toRadians(rot)) * S * 1.5f);
                if (rnd.nextFloat() < .7f) puffs.add(new float[]{nx, ny, (float) Math.cos(ang) * 40 * u, (float) Math.sin(ang) * 40 * u, 0});
                // flamme
                float fl = .8f + rnd.nextFloat() * .4f;
                p.setShader(new RadialGradient(nx, ny + S * .08f, S * .28f * fl, new int[]{0xFFFFFFFF, acc2, 0}, new float[]{0, .35f, 1}, Shader.TileMode.CLAMP));
                c.drawCircle(nx, ny + S * .08f, S * .28f * fl, p); p.setShader(null);
            }
            for (int i = puffs.size() - 1; i >= 0; i--) {
                float[] q = puffs.get(i); q[4] += dt;
                if (q[4] > 1.6f || puffs.size() > 70) { puffs.remove(i); continue; }
                q[0] += q[2] * dt; q[1] += q[3] * dt;
                float al = 1 - q[4] / 1.6f;
                p.setColor(PupLiveWallpaper.a(PupLiveWallpaper.mix(acc2, 0xFFFFFFFF, .4f), (int) (110 * al)));
                c.drawCircle(q[0], q[1], (2 + q[4] * 7) * u, p);
            }
            // lueur autour du chiot
            p.setShader(new RadialGradient(x, y, S * 1.6f, PupLiveWallpaper.a(acc, 45), 0, Shader.TileMode.CLAMP)); c.drawCircle(x, y, S * 1.6f, p); p.setShader(null);
            c.save(); c.translate(x, y); c.rotate(rot);
            c.drawBitmap(b, -b.getWidth() / 2f, -b.getHeight() * .42f, bp);
            // voyant de l'antenne
            boolean on = (t % 1.2f) < .5f;
            p.setShader(new RadialGradient(S * .5f, -S * 1.24f, S * .2f, new int[]{0xFFFFFFFF, on ? acc : PupLiveWallpaper.a(acc, 80), 0}, new float[]{0, .3f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(S * .5f, -S * 1.24f, S * .2f, p); p.setShader(null);
            c.restore();
        }
    }
}

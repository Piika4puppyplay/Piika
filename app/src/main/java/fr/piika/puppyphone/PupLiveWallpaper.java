package fr.piika.puppyphone;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.util.Random;

/** « Nuit néon » : fond d'écran animé puppyplay (pattes lumineuses, faisceaux, sol néon rétro), aux couleurs du pelage. */
public class PupLiveWallpaper extends WallpaperService {
    @Override public Engine onCreateEngine() { return new NeonEngine(); }

    class NeonEngine extends Engine {
        final Handler h = new Handler(Looper.getMainLooper());
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Random rnd = new Random();
        boolean visible;
        int W, H;
        float xOff = .5f;
        float[][] paws; // x, y, taille, vitesse, phase, couleur(0/1), rotation
        float[][] stars;
        int acc, acc2, bg;
        long t0 = SystemClock.uptimeMillis();
        final Runnable frame = this::draw;

        @Override public void onCreate(SurfaceHolder sh) { super.onCreate(sh); setOffsetNotificationsEnabled(true); colors(); }
        void colors() { acc = Pelage.acc(PupLiveWallpaper.this); acc2 = Pelage.acc2(PupLiveWallpaper.this); bg = Pelage.bg(PupLiveWallpaper.this); }
        @Override public void onVisibilityChanged(boolean v) { visible = v; h.removeCallbacks(frame); if (v) { colors(); draw(); } }
        @Override public void onSurfaceChanged(SurfaceHolder sh, int f, int w, int hh) { super.onSurfaceChanged(sh, f, w, hh); W = w; H = hh; seed(); draw(); }
        @Override public void onSurfaceDestroyed(SurfaceHolder sh) { visible = false; h.removeCallbacks(frame); super.onSurfaceDestroyed(sh); }
        @Override public void onDestroy() { h.removeCallbacks(frame); super.onDestroy(); }
        @Override public void onOffsetsChanged(float xo, float yo, float xs, float ys, int xp, int yp) { xOff = xo; if (!visible) return; }

        void seed() {
            int n = 22;
            paws = new float[n][7];
            for (int i = 0; i < n; i++) newPaw(paws[i], true);
            stars = new float[70][3];
            for (float[] s : stars) { s[0] = rnd.nextFloat(); s[1] = rnd.nextFloat() * .6f; s[2] = rnd.nextFloat() * 6.28f; }
        }
        void newPaw(float[] q, boolean anywhere) {
            q[0] = rnd.nextFloat() * W;
            q[1] = anywhere ? rnd.nextFloat() * H : H + 40;
            q[2] = (10 + rnd.nextFloat() * 22) * getResources().getDisplayMetrics().density / 2.2f;
            q[3] = 12 + rnd.nextFloat() * 26;   // px/s
            q[4] = rnd.nextFloat() * 6.28f;
            q[5] = rnd.nextBoolean() ? 1 : 0;
            q[6] = -25 + rnd.nextFloat() * 50;
        }

        void draw() {
            if (!visible) return;
            SurfaceHolder sh = getSurfaceHolder();
            Canvas c = null;
            try {
                c = sh.lockCanvas();
                if (c != null) paint(c);
            } catch (Exception ignored) {
            } finally { if (c != null) try { sh.unlockCanvasAndPost(c); } catch (Exception ignored) { } }
            h.removeCallbacks(frame);
            if (visible) h.postDelayed(frame, 33); // ~30 images/s, en pause quand caché
        }

        int a(int col, int alpha) { return (col & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24); }

        void paint(Canvas c) {
            if (W == 0) { W = c.getWidth(); H = c.getHeight(); seed(); }
            float t = (SystemClock.uptimeMillis() - t0) / 1000f;
            float par = (xOff - .5f) * W * .12f; // parallaxe
            // ciel nocturne
            p.reset(); p.setAntiAlias(true);
            p.setShader(new LinearGradient(0, 0, 0, H, new int[]{mix(bg, acc, .18f), bg, mix(bg, acc2, .14f)}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, p);
            // halos
            p.setShader(new RadialGradient(W * .15f - par * .3f, H * .05f, W * .8f, a(acc, 90), 0, Shader.TileMode.CLAMP)); c.drawRect(0, 0, W, H, p);
            p.setShader(new RadialGradient(W * .95f - par * .3f, H * .45f, W * .7f, a(acc2, 60), 0, Shader.TileMode.CLAMP)); c.drawRect(0, 0, W, H, p);
            p.setShader(null);
            // étoiles qui scintillent
            for (float[] s : stars) {
                float tw = (float) (.5 + .5 * Math.sin(t * 1.6 + s[2]));
                p.setColor(a(0xFFFFFFFF, (int) (40 + 140 * tw)));
                c.drawCircle(s[0] * W - par * .2f, s[1] * H, 1.2f + tw * 1.3f, p);
            }
            // faisceaux qui balaient
            for (int i = 0; i < 3; i++) {
                float ang = (float) Math.sin(t * (.18 + i * .07) + i * 2) * 28;
                float bx = W * (.2f + i * .3f) - par * .5f;
                c.save(); c.rotate(ang, bx, -40);
                int col = i == 1 ? acc2 : acc;
                p.setShader(new LinearGradient(bx, 0, bx, H * .9f, a(col, 120), a(col, 0), Shader.TileMode.CLAMP));
                c.drawRect(bx - 2.5f, -40, bx + 2.5f, H * .9f, p);
                p.setShader(new LinearGradient(bx, 0, bx, H * .7f, a(col, 28), a(col, 0), Shader.TileMode.CLAMP));
                c.drawRect(bx - 26, -40, bx + 26, H * .7f, p);
                c.restore();
            }
            p.setShader(null);
            // sol néon rétro (grille en perspective)
            float hor = H * .7f;
            p.setShader(new LinearGradient(0, hor, 0, H, a(acc, 0), a(acc, 70), Shader.TileMode.CLAMP));
            c.drawRect(0, hor, W, H, p); p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f);
            float scroll = (t * .35f) % 1f;
            for (int i = 0; i < 12; i++) {
                float k = (i + scroll) / 12f, y = hor + (H - hor) * k * k;
                p.setColor(a(acc, (int) (30 + 150 * k))); c.drawLine(0, y, W, y, p);
            }
            for (int i = -10; i <= 10; i++) {
                float xb = W / 2f + i * W * .16f - par;
                p.setColor(a(acc2, 70)); c.drawLine(W / 2f - par * .2f + i * 6, hor, xb, H, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(a(acc, 160)); c.drawRect(0, hor - 1.5f, W, hor + 1.5f, p);
            // pattes lumineuses qui montent
            float dt = 1 / 30f;
            for (float[] q : paws) {
                q[1] -= q[3] * dt;
                if (q[1] < -60) newPaw(q, false);
                float sway = (float) Math.sin(t * .9 + q[4]) * 10;
                float fade = Math.min(1f, Math.max(0f, q[1] / (H * .25f)));
                paw(c, q[0] + sway - par * (.3f + q[2] / 40f), q[1], q[2], q[6], q[5] == 1 ? acc2 : acc, (int) (200 * fade));
            }
        }

        void paw(Canvas c, float x, float y, float r, float rot, int col, int alpha) {
            if (alpha <= 2) return;
            c.save(); c.translate(x, y); c.rotate(rot);
            p.setShader(new RadialGradient(0, 0, r * 3.2f, a(col, alpha / 3), 0, Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, r * 3.2f, p);
            p.setShader(new RadialGradient(-r * .3f, -r * .3f, r * 1.4f, new int[]{a(0xFFFFFFFF, alpha), a(col, alpha), a(mix(col, 0xFF000000, .4f), alpha)}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            c.drawOval(-r, -r * .35f, r, r * 1.2f, p);
            float tr = r * .42f;
            c.drawCircle(-r * 1.08f, -r * .62f, tr, p); c.drawCircle(-r * .4f, -r * 1.22f, tr, p);
            c.drawCircle(r * .4f, -r * 1.22f, tr, p); c.drawCircle(r * 1.08f, -r * .62f, tr, p);
            p.setShader(null);
            c.restore();
        }

        int mix(int a, int b, float t) {
            int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, br = (b >> 16) & 255, bgc = (b >> 8) & 255, bb = b & 255;
            return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bgc - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
        }
    }
}

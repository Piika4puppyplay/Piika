package fr.piika.puppyphone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.util.Random;

/**
 * Moteur des fonds d'écran animés PuppyPhone. Chaque fond est une « scène » dessinée sur le GPU
 * (lockHardwareCanvas), en pleine résolution de l'écran, en pause dès qu'il n'est plus visible,
 * ralentie à 15 images/s en mode économie d'énergie.
 * Cette classe = « Nuit néon » ; PupWallCosmos et PupWallAurore en sont les variantes.
 */
public class PupLiveWallpaper extends WallpaperService {

    interface Scene {
        void size(int w, int h);
        void draw(Canvas c, float t, float dt, float xOff);
    }

    Scene scene(Context c) { return new NeonScene(c); }

    @Override public Engine onCreateEngine() { return new PupEngine(); }

    class PupEngine extends Engine {
        final Handler h = new Handler(Looper.getMainLooper());
        Scene sc;
        boolean visible;
        int W, H;
        float xOff = .5f;
        long t0 = SystemClock.uptimeMillis(), last;
        long pelVer = -1;
        final Runnable frame = this::draw;

        @Override public void onCreate(SurfaceHolder sh) { super.onCreate(sh); setOffsetNotificationsEnabled(true); }
        void ensureScene() {
            long v = Pelage.sp(PupLiveWallpaper.this).getLong("ver", 0);
            if (sc == null || v != pelVer) { pelVer = v; sc = scene(PupLiveWallpaper.this); if (W > 0) sc.size(W, H); }
        }
        @Override public void onVisibilityChanged(boolean v) { visible = v; h.removeCallbacks(frame); if (v) { ensureScene(); last = 0; draw(); } }
        @Override public void onSurfaceChanged(SurfaceHolder sh, int f, int w, int hh) { super.onSurfaceChanged(sh, f, w, hh); W = w; H = hh; ensureScene(); sc.size(w, hh); draw(); }
        @Override public void onSurfaceDestroyed(SurfaceHolder sh) { visible = false; h.removeCallbacks(frame); super.onSurfaceDestroyed(sh); }
        @Override public void onDestroy() { h.removeCallbacks(frame); super.onDestroy(); }
        @Override public void onOffsetsChanged(float xo, float yo, float xs, float ys, int xp, int yp) { xOff = xo; }

        void draw() {
            if (!visible || sc == null || W == 0) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 30f : Math.min(.1f, (now - last) / 1000f);
            last = now;
            SurfaceHolder sh = getSurfaceHolder();
            Canvas c = null;
            try {
                c = sh.lockHardwareCanvas();
                if (c != null) sc.draw(c, (now - t0) / 1000f, dt, xOff);
            } catch (Exception e) {
                try { if (c == null) { c = sh.lockCanvas(); if (c != null) sc.draw(c, (now - t0) / 1000f, dt, xOff); } } catch (Exception ignored) { }
            } finally { if (c != null) try { sh.unlockCanvasAndPost(c); } catch (Exception ignored) { } }
            h.removeCallbacks(frame);
            boolean eco = false;
            try { eco = getSystemService(PowerManager.class).isPowerSaveMode(); } catch (Exception ignored) { }
            if (visible) h.postDelayed(frame, eco ? 66 : 33);
        }
    }

    static int a(int col, int alpha) { return (col & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24); }
    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, br = (b >> 16) & 255, bgc = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bgc - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    /** « Nuit néon » : pattes lumineuses, faisceaux, sol néon rétro. */
    static class NeonScene implements Scene {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Random rnd = new Random();
        final float dens;
        int W, H, acc, acc2, bg;
        float[][] paws, stars;
        NeonScene(Context c) { acc = Pelage.acc(c); acc2 = Pelage.acc2(c); bg = Pelage.bg(c); dens = c.getResources().getDisplayMetrics().density; }

        @Override public void size(int w, int h) {
            W = w; H = h;
            paws = new float[22][7];
            for (float[] q : paws) newPaw(q, true);
            stars = new float[70][3];
            for (float[] s : stars) { s[0] = rnd.nextFloat(); s[1] = rnd.nextFloat() * .6f; s[2] = rnd.nextFloat() * 6.28f; }
        }
        void newPaw(float[] q, boolean anywhere) {
            q[0] = rnd.nextFloat() * W; q[1] = anywhere ? rnd.nextFloat() * H : H + 40;
            q[2] = (10 + rnd.nextFloat() * 22) * dens / 2.2f; q[3] = (12 + rnd.nextFloat() * 26) * dens / 2.2f;
            q[4] = rnd.nextFloat() * 6.28f; q[5] = rnd.nextBoolean() ? 1 : 0; q[6] = -25 + rnd.nextFloat() * 50;
        }

        @Override public void draw(Canvas c, float t, float dt, float xOff) {
            float par = (xOff - .5f) * W * .12f, u = dens / 2.6f;
            p.reset(); p.setAntiAlias(true);
            p.setShader(new LinearGradient(0, 0, 0, H, new int[]{mix(bg, acc, .18f), bg, mix(bg, acc2, .14f)}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, p);
            p.setShader(new RadialGradient(W * .15f - par * .3f, H * .05f, W * .8f, a(acc, 90), 0, Shader.TileMode.CLAMP)); c.drawRect(0, 0, W, H, p);
            p.setShader(new RadialGradient(W * .95f - par * .3f, H * .45f, W * .7f, a(acc2, 60), 0, Shader.TileMode.CLAMP)); c.drawRect(0, 0, W, H, p);
            p.setShader(null);
            for (float[] s : stars) {
                float tw = (float) (.5 + .5 * Math.sin(t * 1.6 + s[2]));
                p.setColor(a(0xFFFFFFFF, (int) (40 + 140 * tw)));
                c.drawCircle(s[0] * W - par * .2f, s[1] * H, (1.2f + tw * 1.3f) * u * 1.4f, p);
            }
            for (int i = 0; i < 3; i++) {
                float ang = (float) Math.sin(t * (.18 + i * .07) + i * 2) * 28;
                float bx = W * (.2f + i * .3f) - par * .5f;
                c.save(); c.rotate(ang, bx, -40);
                int col = i == 1 ? acc2 : acc;
                p.setShader(new LinearGradient(bx, 0, bx, H * .9f, a(col, 120), a(col, 0), Shader.TileMode.CLAMP));
                c.drawRect(bx - 2.5f * u, -40, bx + 2.5f * u, H * .9f, p);
                p.setShader(new LinearGradient(bx, 0, bx, H * .7f, a(col, 28), a(col, 0), Shader.TileMode.CLAMP));
                c.drawRect(bx - 26 * u, -40, bx + 26 * u, H * .7f, p);
                c.restore();
            }
            p.setShader(null);
            float hor = H * .7f;
            p.setShader(new LinearGradient(0, hor, 0, H, a(acc, 0), a(acc, 70), Shader.TileMode.CLAMP));
            c.drawRect(0, hor, W, H, p); p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f * u);
            float scroll = (t * .35f) % 1f;
            for (int i = 0; i < 12; i++) { float k = (i + scroll) / 12f, y = hor + (H - hor) * k * k; p.setColor(a(acc, (int) (30 + 150 * k))); c.drawLine(0, y, W, y, p); }
            for (int i = -10; i <= 10; i++) { float xb = W / 2f + i * W * .16f - par; p.setColor(a(acc2, 70)); c.drawLine(W / 2f - par * .2f + i * 6 * u, hor, xb, H, p); }
            p.setStyle(Paint.Style.FILL);
            p.setColor(a(acc, 160)); c.drawRect(0, hor - 1.5f * u, W, hor + 1.5f * u, p);
            for (float[] q : paws) {
                q[1] -= q[3] * dt * 2.2f;
                if (q[1] < -60) newPaw(q, false);
                float sway = (float) Math.sin(t * .9 + q[4]) * 10 * u;
                float fade = Math.min(1f, Math.max(0f, q[1] / (H * .25f)));
                PupDraw.paw(c, p, q[0] + sway - par * (.3f + q[2] / 40f), q[1], q[2], q[6], q[5] == 1 ? acc2 : acc, (int) (200 * fade));
            }
        }
    }
}

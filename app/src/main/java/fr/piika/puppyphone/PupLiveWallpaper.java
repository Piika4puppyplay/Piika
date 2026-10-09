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
 * Cette classe = « Boulevard néon » ; PupWallCosmos, PupWallAurore et PupWallNiche en sont les variantes.
 */
public class PupLiveWallpaper extends WallpaperService {

    interface Scene {
        void size(int w, int h);
        void draw(Canvas c, float t, float dt, float xOff);
    }

    Scene scene(Context c) { return new LayeredScene(c, "neon"); }

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
}

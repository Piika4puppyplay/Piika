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
 * Cette classe = « Boulevard néon » ; PupWallCosmos, PupWallAurore, PupWallNiche… en sont les variantes.
 *
 * Fond différent sur le verrouillage : Android n'accepte qu'UN fond animé à la fois. L'astuce : le même moteur
 * s'affiche derrière le verrouillage One UI (image du verrou retirée) et change lui-même de scène quand le téléphone
 * est verrouillé (réglage « wallLock »), avec un fondu au déverrouillage. Le verrouillage lui-même n'est pas touché.
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
        Scene sc, lockSc;
        String lockId = "";
        boolean locked;
        long fadeStart;            // fondu verrou → accueil au déverrouillage
        static final long FADE = 550;
        final android.content.BroadcastReceiver rx = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                boolean was = locked; checkLocked();
                if (was && !locked) fadeStart = SystemClock.uptimeMillis();
                if (visible) draw();
            }
        };
        void checkLocked() {
            try { locked = !isPreview() && getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(); } catch (Exception e) { locked = false; }
        }
        /** Scène du verrouillage si elle est différente de celle de l'accueil. */
        void ensureLock() {
            String want = Pelage.sp(PupLiveWallpaper.this).getString("wallLock", "");
            String home = sc instanceof LayeredScene ? ((LayeredScene) sc).id : "";
            if (want.equals(home)) want = "";
            if (!want.equals(lockId) || (lockSc == null && !want.isEmpty())) {
                lockId = want;
                lockSc = want.isEmpty() ? null : new LayeredScene(PupLiveWallpaper.this, want);
                if (lockSc != null && W > 0) lockSc.size(W, H);
            }
        }
        boolean visible;
        int W, H;
        float xOff = .5f;
        long t0 = SystemClock.uptimeMillis(), last;
        long pelVer = -1;
        final Runnable frame = this::draw;

        @Override public void onCreate(SurfaceHolder sh) {
            super.onCreate(sh); setOffsetNotificationsEnabled(true);
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_SCREEN_ON); f.addAction(android.content.Intent.ACTION_SCREEN_OFF); f.addAction(android.content.Intent.ACTION_USER_PRESENT);
            try { registerReceiver(rx, f); } catch (Exception ignored) { }
            checkLocked();
        }
        void ensureScene() {
            long v = Pelage.sp(PupLiveWallpaper.this).getLong("ver", 0);
            if (sc == null || v != pelVer) { pelVer = v; sc = scene(PupLiveWallpaper.this); if (W > 0) sc.size(W, H); lockSc = null; lockId = ""; }
            ensureLock();
        }
        @Override public void onVisibilityChanged(boolean v) { visible = v; h.removeCallbacks(frame); if (v) { checkLocked(); ensureScene(); last = 0; draw(); } }
        @Override public void onSurfaceChanged(SurfaceHolder sh, int f, int w, int hh) { super.onSurfaceChanged(sh, f, w, hh); W = w; H = hh; ensureScene(); sc.size(w, hh); if (lockSc != null) lockSc.size(w, hh); draw(); }
        @Override public void onSurfaceDestroyed(SurfaceHolder sh) { visible = false; h.removeCallbacks(frame); super.onSurfaceDestroyed(sh); }
        @Override public void onDestroy() { h.removeCallbacks(frame); try { unregisterReceiver(rx); } catch (Exception ignored) { } super.onDestroy(); }
        @Override public void onOffsetsChanged(float xo, float yo, float xs, float ys, int xp, int yp) { xOff = xo; }

        long battAt; boolean battChg; int battLv = 100;
        /** Cadence adaptative : 30/20/15 i/s selon le réglage, ralentie si batterie faible ou mode éco système. */
        long frameDelay(long now) {
            if (now - battAt > 8000) {
                battAt = now;
                try {
                    android.content.Intent bi = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                    if (bi != null) {
                        int st = bi.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                        battChg = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL;
                        battLv = bi.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 50) * 100 / Math.max(1, bi.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100));
                    }
                    battEco = getSystemService(PowerManager.class).isPowerSaveMode();
                } catch (Exception ignored) { }
            }
            // en charge : toujours fluide. Sinon, on suit le réglage, et on ralentit si batterie basse ou mode éco.
            int fps = Pelage.sp(PupLiveWallpaper.this).getInt("wallFps", 24);
            if (!battChg) {
                if (battEco || battLv <= 15) fps = Math.min(fps, 12);
                else if (battLv <= 30) fps = Math.min(fps, 18);
            }
            fps = Math.max(10, Math.min(30, fps));
            return 1000L / fps;
        }
        boolean battEco;

        /** Accueil, verrouillage, ou le fondu entre les deux juste après le déverrouillage. */
        void paint(Canvas c, float t, float dt, long now) {
            if (lockSc == null) { sc.draw(c, t, dt, xOff); return; }
            if (locked) { lockSc.draw(c, t, dt, .5f); return; }
            long k = now - fadeStart;
            sc.draw(c, t, dt, xOff);
            if (k >= 0 && k < FADE) {
                int a = (int) (255 * (1 - k / (float) FADE));
                int sv = c.saveLayerAlpha(0, 0, W, H, a);
                lockSc.draw(c, t, dt, .5f);
                c.restoreToCount(sv);
            }
        }

        void draw() {
            if (!visible || sc == null || W == 0) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 30f : Math.min(.1f, (now - last) / 1000f);
            last = now;
            SurfaceHolder sh = getSurfaceHolder();
            Canvas c = null;
            try {
                c = sh.lockHardwareCanvas();
                if (c != null) paint(c, (now - t0) / 1000f, dt, now);
            } catch (Exception e) {
                try { if (c == null) { c = sh.lockCanvas(); if (c != null) paint(c, (now - t0) / 1000f, dt, now); } } catch (Exception ignored) { }
            } finally { if (c != null) try { sh.unlockCanvasAndPost(c); } catch (Exception ignored) { } }
            h.removeCallbacks(frame);
            if (visible) h.postDelayed(frame, frameDelay(now));
        }
    }

    static int a(int col, int alpha) { return (col & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24); }
    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, br = (b >> 16) & 255, bgc = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bgc - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}

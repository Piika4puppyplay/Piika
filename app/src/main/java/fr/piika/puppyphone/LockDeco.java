package fr.piika.puppyphone;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Matrix;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.Random;

/**
 * Décorations légères posées PAR-DESSUS l'écran de verrouillage One UI (qui reste intact : code, empreinte,
 * raccourcis, notifications). Fenêtre d'accessibilité qui ne capte aucun toucher.
 * Désactivable dans « Ta niche » : la fenêtre est alors retirée et tout redevient comme avant.
 * Héberge aussi les sons de charge et de déverrouillage de la boîte à couinements.
 */
final class LockDeco {
    final Context ctx;
    final WindowManager wm;
    final KeyguardManager km;
    final PowerManager pm;
    final Handler h = new Handler(Looper.getMainLooper());
    DecoView view;
    boolean added, dreaming, charging, registered;
    long previewUntil;
    int lastPct = -1;
    boolean fullSaid, lowSaid;

    LockDeco(Context c) {
        ctx = c;
        wm = c.getSystemService(WindowManager.class);
        km = c.getSystemService(KeyguardManager.class);
        pm = c.getSystemService(PowerManager.class);
    }

    static SharedPreferences sp(Context c) { return Pelage.sp(c); }
    boolean enabled() { return sp(ctx).getBoolean("verrou", false); }

    final BroadcastReceiver rcv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction(); if (a == null) return;
            switch (a) {
                case Intent.ACTION_SCREEN_OFF: hide(); PupVeille.onScreenOff(ctx, charging); break;
                case Intent.ACTION_USER_PRESENT: hide(); PupVeille.onUnlock(ctx); PupSons.play(ctx, "sonsVerrou", "jappe"); break;
                case Intent.ACTION_DREAMING_STARTED: dreaming = true; hide(); break;
                case Intent.ACTION_DREAMING_STOPPED: dreaming = false; check(); break;
                case Intent.ACTION_POWER_CONNECTED: charging = true; PupSons.play(ctx, "sonsCharge", "halete"); check(); break;
                case Intent.ACTION_POWER_DISCONNECTED: PupVeille.onUnplug(); charging = false; fullSaid = false; PupSons.play(ctx, "sonsCharge", "pouic"); check(); break;
                case Intent.ACTION_BATTERY_CHANGED: battery(i); break;
                default: check();
            }
        }
    };

    void battery(Intent i) {
        int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (l < 0 || s <= 0) return;
        int pct = Math.round(l * 100f / s);
        if (lastPct >= 0) {
            if (charging && pct >= 100 && !fullSaid) { fullSaid = true; PupSons.play(ctx, "sonsCharge", "wouf2"); }
            if (!charging && pct <= 15 && lastPct > 15 && !lowSaid) { lowSaid = true; PupSons.play(ctx, "sonsCharge", "couine"); }
        }
        if (pct > 20) lowSaid = false;
        if (st == BatteryManager.BATTERY_STATUS_FULL) fullSaid = true;
        lastPct = pct;
    }

    void start() {
        if (registered) return;
        IntentFilter f = new IntentFilter();
        for (String a : new String[]{Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT, Intent.ACTION_DREAMING_STARTED,
                Intent.ACTION_DREAMING_STOPPED, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED, Intent.ACTION_BATTERY_CHANGED}) f.addAction(a);
        try { ctx.registerReceiver(rcv, f); registered = true; } catch (Exception ignored) { }
        check();
    }
    void stop() {
        if (registered) try { ctx.unregisterReceiver(rcv); } catch (Exception ignored) { }
        registered = false; hide();
    }

    final Runnable poll = this::check;

    /** Montre la déco seulement sur l'écran de verrouillage allumé (jamais en veille, pendant la sieste ni une fois déverrouillé). */
    void check() {
        h.removeCallbacks(poll);
        boolean preview = SystemClock.uptimeMillis() < previewUntil;
        boolean want;
        if (preview) want = true;
        else {
            boolean locked = false, on = false;
            try { locked = km.isKeyguardLocked(); on = pm.isInteractive(); } catch (Exception ignored) { }
            want = enabled() && on && locked && !dreaming && !PupVeille.showing && !(charging && sp(ctx).getBoolean("verrouCharge", true));
        }
        if (want) show(); else hide();
        if (added) h.postDelayed(poll, preview ? 300 : 500);
    }

    /** Aperçu de quelques secondes par-dessus l'écran actuel (pour tester sans verrouiller). */
    void preview(long ms) { previewUntil = SystemClock.uptimeMillis() + ms; if (added) hide(); check(); }

    void refresh() { if (added) { hide(); } check(); }

    void show() {
        if (added) return;
        view = new DecoView(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (android.os.Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.setTitle("PupVerrou");
        try { wm.addView(view, lp); added = true; } catch (Exception e) { view = null; }
    }
    void hide() {
        if (!added) return;
        try { wm.removeViewImmediate(view); } catch (Exception ignored) { }
        added = false; view = null;
    }

    /** Le dessin : cadre néon, traces de pattes, chiot qui pointe le museau, étoiles. */
    final class DecoView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float dp = getResources().getDisplayMetrics().density;
        final int acc = Pelage.acc(ctx), acc2 = Pelage.acc2(ctx);
        final SharedPreferences s = sp(ctx);
        final boolean cadre = s.getBoolean("verrouCadre", true), pattes = s.getBoolean("verrouPattes", true),
                chiot = s.getBoolean("verrouChiot", true), etoiles = s.getBoolean("verrouEtoiles", true);
        final float force = new float[]{.42f, .7f, 1f}[Math.max(0, Math.min(2, s.getInt("verrouForce", 1)))];
        final Random rnd = new Random();
        final float ox, oy; // décalage anti-marquage, différent à chaque allumage
        final long t0 = SystemClock.uptimeMillis();
        final float[][] stars = new float[7][3];

        DecoView(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_HARDWARE, null);
            ox = (rnd.nextFloat() - .5f) * 8 * dp; oy = (rnd.nextFloat() - .5f) * 14 * dp;
            for (float[] st : stars) { st[0] = rnd.nextFloat(); st[1] = .02f + rnd.nextFloat() * .14f; st[2] = rnd.nextFloat() * 6.28f; }
        }

        int a(int col, float al) { return (col & 0x00FFFFFF) | (Math.max(0, Math.min(255, (int) (al * 255 * force))) << 24); }

        @Override protected void onDraw(Canvas c) {
            float t = (SystemClock.uptimeMillis() - t0) / 1000f, W = getWidth(), H = getHeight();
            float appear = Math.min(1f, t / .6f); // fondu d'entrée
            c.save(); c.translate(ox, oy);
            if (cadre) frame(c, W, H, t, appear);
            if (etoiles) stars(c, W, H, t, appear);
            if (pattes) paws(c, W, H, t, appear);
            if (chiot) pup(c, W, H, t, appear);
            c.restore();
            postInvalidateDelayed(50); // ~20 images/s, seulement tant que l'écran de verrouillage est affiché
        }

        void frame(Canvas c, float W, float H, float t, float ap) {
            float in = 3 * dp, r = 30 * dp;
            RectF rf = new RectF(in - ox, in - oy, W - in - ox, H - in - oy);
            SweepGradient sg = new SweepGradient(W / 2, H / 2, new int[]{acc, acc2, acc, acc2, acc}, null);
            Matrix m = new Matrix(); m.setRotate(t * 18, W / 2, H / 2); sg.setLocalMatrix(m);
            p.setStyle(Paint.Style.STROKE); p.setShader(sg);
            float breathe = .75f + .25f * (float) Math.sin(t * 1.4);
            float[][] layers = {{14, .07f}, {9, .12f}, {5, .22f}, {2.2f, .9f}};
            for (float[] L : layers) { p.setStrokeWidth(L[0] * dp); p.setAlpha((int) (255 * L[1] * breathe * force * ap)); c.drawRoundRect(rf, r, r, p); }
            p.setShader(null); p.setStrokeWidth(.8f * dp); p.setColor(a(0xFFFFFFFF, .55f * ap)); c.drawRoundRect(rf, r, r, p); // reflet chrome
            p.setStyle(Paint.Style.FILL); p.setAlpha(255);
        }

        void stars(Canvas c, float W, float H, float t, float ap) {
            for (float[] s : stars) {
                float tw = .5f + .5f * (float) Math.sin(t * 2 + s[2]);
                float x = s[0] < .5f ? s[0] * W * .5f + 14 * dp : W - (1 - s[0]) * W * .5f - 14 * dp, y = s[1] * H + 40 * dp;
                if (x > W * .3f && x < W * .7f) continue; // on laisse l'horloge tranquille
                star(c, x, y, (3 + 4 * tw) * dp, a(0xFFFFFFFF, (.25f + .6f * tw) * ap), a(s[2] > 3 ? acc : acc2, .5f * tw * ap));
            }
        }
        void star(Canvas c, float x, float y, float r, int col, int glow) {
            p.setShader(new RadialGradient(x, y, r * 2.6f, glow, 0, Shader.TileMode.CLAMP)); c.drawCircle(x, y, r * 2.6f, p); p.setShader(null);
            Path s = new Path(); s.moveTo(x, y - r); s.quadTo(x, y, x + r, y); s.quadTo(x, y, x, y + r); s.quadTo(x, y, x - r, y); s.quadTo(x, y, x, y - r); s.close();
            p.setColor(col); c.drawPath(s, p);
        }

        /** Traces de pattes qui marchent le long des bords (loin de l'horloge, de l'empreinte et des raccourcis). */
        void paws(Canvas c, float W, float H, float t, float ap) {
            int n = 5; float cycle = 6f, step = (t % cycle) / cycle * (n + 2);
            for (int side = 0; side < 2; side++) {
                for (int i = 0; i < n; i++) {
                    float vis = step - i; // apparition pas à pas puis effacement
                    float al = vis < 0 ? 0 : vis < 1 ? vis : vis > n + 1 ? Math.max(0, n + 2 - vis) : 1;
                    if (al <= 0) continue;
                    float k = i / (float) (n - 1);
                    float y = side == 0 ? H * (.74f - k * .3f) : H * (.44f + k * .3f);
                    float x = (side == 0 ? 20 * dp : W - 20 * dp) + (i % 2 == 0 ? -1 : 1) * 6 * dp;
                    paw(c, x, y, 7.5f * dp, side == 0 ? -8 : 188, side == 0 ? acc : acc2, al * ap * .9f);
                }
            }
        }
        void paw(Canvas c, float x, float y, float r, float rot, int col, float al) {
            c.save(); c.translate(x, y); c.rotate(rot);
            p.setShader(new RadialGradient(0, 0, r * 3, a(col, .35f * al), 0, Shader.TileMode.CLAMP)); c.drawCircle(0, 0, r * 3, p);
            p.setShader(new RadialGradient(-r * .3f, -r * .3f, r * 1.5f, new int[]{a(0xFFFFFFFF, al), a(col, al), a(col, al * .8f)}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP));
            c.drawOval(-r, -r * .3f, r, r * 1.2f, p);
            float tr = r * .42f;
            c.drawCircle(-r * 1.05f, -r * .62f, tr, p); c.drawCircle(-r * .38f, -r * 1.2f, tr, p);
            c.drawCircle(r * .38f, -r * 1.2f, tr, p); c.drawCircle(r * 1.05f, -r * .62f, tr, p);
            p.setShader(null); c.restore();
        }

        /** Un chiot (dessin original) qui pointe le museau depuis le bord droit, pattes accrochées au cadre. */
        void pup(Canvas c, float W, float H, float t, float ap) {
            float sz = 34 * dp, peek = Math.min(1f, t / 1.2f);
            float bob = (float) Math.sin(t * 1.3) * 2 * dp;
            float cx = W + sz * .55f - peek * sz * 1.05f, cy = H * .56f + bob;
            int alpha = (int) (255 * Math.min(1f, force + .2f) * ap);
            c.saveLayerAlpha(cx - sz * 2, cy - sz * 2, W + 10, cy + sz * 2, alpha);
            c.translate(cx, cy); c.rotate(-12);
            // lueur
            p.setShader(new RadialGradient(0, 0, sz * 1.9f, (acc & 0x00FFFFFF) | 0x55000000, 0, Shader.TileMode.CLAMP)); c.drawCircle(0, 0, sz * 1.9f, p);
            // oreilles
            p.setShader(new LinearGradient(0, -sz, 0, sz * .6f, 0xFFD98C4A, 0xFF5A2C0C, Shader.TileMode.CLAMP));
            c.save(); c.rotate(28, -sz * .7f, -sz * .5f); c.drawOval(-sz * 1.05f, -sz * .9f, -sz * .45f, sz * .45f, p); c.restore();
            c.save(); c.rotate(-28, sz * .7f, -sz * .5f); c.drawOval(sz * .45f, -sz * .9f, sz * 1.05f, sz * .45f, p); c.restore();
            // tête
            p.setShader(new RadialGradient(-sz * .3f, -sz * .4f, sz * 1.3f, new int[]{0xFFFFF6E8, 0xFFF6CF9A, 0xFFD9934F, 0xFF9C5A24}, new float[]{0, .4f, .8f, 1}, Shader.TileMode.CLAMP));
            c.drawOval(-sz * .8f, -sz * .75f, sz * .8f, sz * .7f, p);
            // museau
            p.setShader(new RadialGradient(0, sz * .2f, sz * .5f, 0xFFFFFFFF, 0xFFF3E2CC, Shader.TileMode.CLAMP));
            c.drawOval(-sz * .42f, -sz * .02f, sz * .42f, sz * .55f, p); p.setShader(null);
            // yeux (clignent toutes les ~4 s)
            boolean blink = (t % 4.2f) > 4.0f;
            if (blink) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f * dp); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(0xFF1E0E36);
                c.drawLine(-sz * .42f, -sz * .17f, -sz * .18f, -sz * .17f, p); c.drawLine(sz * .18f, -sz * .17f, sz * .42f, -sz * .17f, p);
                p.setStyle(Paint.Style.FILL);
            } else {
                p.setColor(0xFF1E0E36); c.drawOval(-sz * .42f, -sz * .36f, -sz * .18f, sz * .0f, p); c.drawOval(sz * .18f, -sz * .36f, sz * .42f, sz * .0f, p);
                p.setColor(0xFFFFFFFF); c.drawCircle(-sz * .26f, -sz * .26f, sz * .07f, p); c.drawCircle(sz * .34f, -sz * .26f, sz * .07f, p);
                p.setColor(a(acc2, .9f)); c.drawCircle(-sz * .34f, -sz * .1f, sz * .035f, p); c.drawCircle(sz * .26f, -sz * .1f, sz * .035f, p);
            }
            // truffe + reflet
            p.setShader(new RadialGradient(-sz * .05f, sz * .1f, sz * .2f, 0xFF4A2A5E, 0xFF12061E, Shader.TileMode.CLAMP));
            c.drawOval(-sz * .15f, sz * .06f, sz * .15f, sz * .27f, p); p.setShader(null);
            p.setColor(0xB3FFFFFF); c.drawOval(-sz * .09f, sz * .09f, sz * .0f, sz * .14f, p);
            // bouche + langue
            p.setColor(0xFFFF5E9E); c.drawOval(-sz * .08f, sz * .36f, sz * .08f, sz * .54f, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f * dp); p.setColor(0xFF3A1A0C);
            Path m = new Path(); m.moveTo(0, sz * .27f); m.lineTo(0, sz * .35f); m.quadTo(-sz * .12f, sz * .45f, -sz * .22f, sz * .34f); m.moveTo(0, sz * .35f); m.quadTo(sz * .12f, sz * .45f, sz * .22f, sz * .34f);
            c.drawPath(m, p); p.setStyle(Paint.Style.FILL);
            // joues
            p.setColor(0x80FF7AB8); c.drawOval(-sz * .62f, sz * .05f, -sz * .38f, sz * .2f, p); c.drawOval(sz * .38f, sz * .05f, sz * .62f, sz * .2f, p);
            // pattes accrochées au bord
            for (int k = -1; k <= 1; k += 2) {
                float px = k * sz * .55f, py = sz * .78f;
                p.setShader(new RadialGradient(px - sz * .05f, py - sz * .08f, sz * .3f, 0xFFFFF6EA, 0xFFE8C9A0, Shader.TileMode.CLAMP));
                c.drawOval(px - sz * .26f, py - sz * .17f, px + sz * .26f, py + sz * .17f, p); p.setShader(null);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f * dp); p.setColor(0x995A2C0C);
                c.drawLine(px - sz * .08f, py + sz * .02f, px - sz * .08f, py + sz * .15f, p); c.drawLine(px + sz * .08f, py + sz * .02f, px + sz * .08f, py + sz * .15f, p);
                p.setStyle(Paint.Style.FILL);
            }
            // collier cuir + médaille aux couleurs du pelage
            p.setColor(0xFF2A1020); c.drawRoundRect(-sz * .55f, sz * .58f, sz * .55f, sz * .72f, sz * .07f, sz * .07f, p);
            p.setColor(0xFFDDDDDD); for (int k = -2; k <= 2; k++) c.drawCircle(k * sz * .2f, sz * .65f, sz * .03f, p);
            p.setShader(new RadialGradient(-sz * .04f, sz * .76f, sz * .14f, new int[]{0xFFFFFFFF, acc, acc2}, null, Shader.TileMode.CLAMP));
            c.drawCircle(0, sz * .8f, sz * .12f, p); p.setShader(null);
            c.restore();
        }
    }
}

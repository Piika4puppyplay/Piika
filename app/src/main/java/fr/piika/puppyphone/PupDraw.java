package fr.piika.puppyphone;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/** Petits dessins puppy réutilisables (originaux) pour les fonds animés. */
final class PupDraw {
    private PupDraw() { }

    static int a(int col, float al) { return (col & 0x00FFFFFF) | (Math.max(0, Math.min(255, (int) (al * 255))) << 24); }

    /** Patte lumineuse (coussinet + 4 doigts) avec halo. */
    static void paw(Canvas c, Paint p, float x, float y, float r, float rot, int col, int alpha) {
        if (alpha <= 2) return;
        c.save(); c.translate(x, y); c.rotate(rot);
        p.setShader(new RadialGradient(0, 0, r * 3.2f, PupLiveWallpaper.a(col, alpha / 3), 0, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, r * 3.2f, p);
        p.setShader(new RadialGradient(-r * .3f, -r * .3f, r * 1.4f, new int[]{PupLiveWallpaper.a(0xFFFFFFFF, alpha), PupLiveWallpaper.a(col, alpha), PupLiveWallpaper.a(PupLiveWallpaper.mix(col, 0xFF000000, .4f), alpha)}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        c.drawOval(-r, -r * .35f, r, r * 1.2f, p);
        float tr = r * .42f;
        c.drawCircle(-r * 1.08f, -r * .62f, tr, p); c.drawCircle(-r * .4f, -r * 1.22f, tr, p);
        c.drawCircle(r * .4f, -r * 1.22f, tr, p); c.drawCircle(r * 1.08f, -r * .62f, tr, p);
        p.setShader(null);
        c.restore();
    }

    /** Tête de chiot (oreilles tombantes, museau, truffe brillante, joues) centrée en (0,0), largeur ≈ 2·sz. */
    static void head(Canvas c, Paint p, float sz, boolean blink, int acc2) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, -sz, 0, sz * .6f, 0xFFD98C4A, 0xFF5A2C0C, Shader.TileMode.CLAMP));
        c.save(); c.rotate(24, -sz * .7f, -sz * .5f); c.drawOval(-sz * 1.08f, -sz * .9f, -sz * .45f, sz * .5f, p); c.restore();
        c.save(); c.rotate(-24, sz * .7f, -sz * .5f); c.drawOval(sz * .45f, -sz * .9f, sz * 1.08f, sz * .5f, p); c.restore();
        p.setShader(new RadialGradient(-sz * .3f, -sz * .4f, sz * 1.3f, new int[]{0xFFFFF6E8, 0xFFF6CF9A, 0xFFD9934F, 0xFF9C5A24}, new float[]{0, .4f, .8f, 1}, Shader.TileMode.CLAMP));
        c.drawOval(-sz * .8f, -sz * .75f, sz * .8f, sz * .7f, p);
        p.setShader(new RadialGradient(0, sz * .2f, sz * .5f, 0xFFFFFFFF, 0xFFF3E2CC, Shader.TileMode.CLAMP));
        c.drawOval(-sz * .42f, -sz * .02f, sz * .42f, sz * .55f, p); p.setShader(null);
        if (blink) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sz * .07f); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(0xFF1E0E36);
            Path e = new Path(); e.moveTo(-sz * .44f, -sz * .2f); e.quadTo(-sz * .3f, -sz * .1f, -sz * .16f, -sz * .2f);
            e.moveTo(sz * .16f, -sz * .2f); e.quadTo(sz * .3f, -sz * .1f, sz * .44f, -sz * .2f); c.drawPath(e, p);
            p.setStyle(Paint.Style.FILL);
        } else {
            p.setColor(0xFF1E0E36); c.drawOval(-sz * .43f, -sz * .38f, -sz * .17f, sz * .0f, p); c.drawOval(sz * .17f, -sz * .38f, sz * .43f, sz * .0f, p);
            p.setColor(0xFFFFFFFF); c.drawCircle(-sz * .26f, -sz * .27f, sz * .075f, p); c.drawCircle(sz * .34f, -sz * .27f, sz * .075f, p);
            p.setColor(a(acc2, .9f)); c.drawCircle(-sz * .35f, -sz * .1f, sz * .035f, p); c.drawCircle(sz * .25f, -sz * .1f, sz * .035f, p);
        }
        p.setShader(new RadialGradient(-sz * .05f, sz * .1f, sz * .2f, 0xFF4A2A5E, 0xFF12061E, Shader.TileMode.CLAMP));
        c.drawOval(-sz * .15f, sz * .06f, sz * .15f, sz * .27f, p); p.setShader(null);
        p.setColor(0xB3FFFFFF); c.drawOval(-sz * .09f, sz * .09f, sz * .0f, sz * .14f, p);
        p.setColor(0xFFFF5E9E); c.drawOval(-sz * .08f, sz * .36f, sz * .08f, sz * .54f, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sz * .05f); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(0xFF3A1A0C);
        Path m = new Path(); m.moveTo(0, sz * .27f); m.lineTo(0, sz * .35f); m.quadTo(-sz * .12f, sz * .45f, -sz * .22f, sz * .34f); m.moveTo(0, sz * .35f); m.quadTo(sz * .12f, sz * .45f, sz * .22f, sz * .34f);
        c.drawPath(m, p); p.setStyle(Paint.Style.FILL);
        p.setColor(0x80FF7AB8); c.drawOval(-sz * .64f, sz * .05f, -sz * .38f, sz * .2f, p); c.drawOval(sz * .38f, sz * .05f, sz * .64f, sz * .2f, p);
    }

    /** Étoile à 4 branches avec halo. */
    static void sparkle(Canvas c, Paint p, float x, float y, float r, int col, int glow) {
        p.setShader(new RadialGradient(x, y, r * 2.6f, glow, 0, Shader.TileMode.CLAMP)); c.drawCircle(x, y, r * 2.6f, p); p.setShader(null);
        Path s = new Path(); s.moveTo(x, y - r); s.quadTo(x, y, x + r, y); s.quadTo(x, y, x, y + r); s.quadTo(x, y, x - r, y); s.quadTo(x, y, x, y - r); s.close();
        p.setColor(col); c.drawPath(s, p);
    }
}

package fr.piika.puppyphone;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * PupNavigationBar — barre de navigation puppyplay superposée (cuir, rivets, néon magenta).
 * Hébergée par le service d'accessibilité (mode complet : Retour fonctionne partout)
 * ou par le service overlay (SYSTEM_ALERT_WINDOW) si l'accessibilité n'est pas activée.
 * Styles : « float » (pilule flottante déplaçable) ou « full » (barre pleine largeur en bas).
 * + une poignée sur le bord de l'écran : glisser vers l'intérieur = PupTasks.
 */
public class PupNav {
    interface Host { boolean back(); boolean home(); }

    static final List<PupNav> LIVE = new ArrayList<>();
    final Context ctx;
    final WindowManager wm;
    final int type;
    final Host host;
    final Handler h = new Handler(Looper.getMainLooper());
    final float dp;
    NavView bar;
    EdgeView edge;
    WindowManager.LayoutParams barLp, edgeLp;
    boolean imeVisible, hiddenForApp;

    PupNav(Context ctx, int type, Host host) {
        this.ctx = ctx; this.type = type; this.host = host;
        wm = ctx.getSystemService(WindowManager.class);
        dp = ctx.getResources().getDisplayMetrics().density;
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("puptasks", Context.MODE_PRIVATE); }

    static boolean canOverlay(Context c) { return Settings.canDrawOverlays(c); }

    static boolean a11yOn(Context c) {
        if (PupNavA11y.I != null) return true;
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName() + "/");
    }

    /** Démarre/arrête le bon hôte selon les réglages. Appelée par le lanceur et PupTasks. */
    static void ensure(Context c) {
        SharedPreferences p = prefs(c);
        boolean want = !"off".equals(p.getString("style", "off")) || p.getBoolean("edge", false);
        if (PupNavA11y.I != null) { refreshAll(); stopOverlay(c); return; }
        if (want && canOverlay(c)) {
            try {
                Intent i = new Intent(c, PupOverlayService.class);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            } catch (Exception ignored) { }
        } else stopOverlay(c);
        refreshAll();
    }
    static void stopOverlay(Context c) { try { c.stopService(new Intent(c, PupOverlayService.class)); } catch (Exception ignored) { } }
    static void refreshAll() { for (PupNav n : new ArrayList<>(LIVE)) n.h.post(n::refresh); }

    static void openTasks(Context c) {
        try {
            c.startActivity(new Intent(c, TasksActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) { Toast.makeText(c, "PupTasks n'a pas pu s'ouvrir", Toast.LENGTH_SHORT).show(); }
    }
    static void goHome(Context c) {
        try { c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }

    void attach() { LIVE.add(this); refresh(); }
    void detach() {
        LIVE.remove(this);
        removeBar(); removeEdge();
    }
    void removeBar() { if (bar != null) { try { wm.removeView(bar); } catch (Exception ignored) { } bar = null; } }
    void removeEdge() { if (edge != null) { try { wm.removeView(edge); } catch (Exception ignored) { } edge = null; } }

    void setImeVisible(boolean v) { if (v == imeVisible) return; imeVisible = v; applyVisibility(); }
    void setHiddenForApp(boolean v) { if (v == hiddenForApp) return; hiddenForApp = v; applyVisibility(); }
    void applyVisibility() {
        if (bar != null) bar.setVisibility(imeVisible || hiddenForApp ? View.GONE : View.VISIBLE);
        if (edge != null) edge.setVisibility(imeVisible ? View.GONE : View.VISIBLE);
    }

    void refresh() {
        SharedPreferences p = prefs(ctx);
        String style = p.getString("style", "off");
        removeBar(); removeEdge();
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (!"off".equals(style)) {
            bar = new NavView(ctx, "float".equals(style));
            int hgt = (int) (p.getInt("height", 54) * dp);
            if ("float".equals(style)) {
                barLp = new WindowManager.LayoutParams((int) (230 * dp), hgt + (int) (8 * dp), type, flags, PixelFormat.TRANSLUCENT);
                barLp.gravity = Gravity.TOP | Gravity.START;
                int sw = ctx.getResources().getDisplayMetrics().widthPixels, sh = ctx.getResources().getDisplayMetrics().heightPixels;
                barLp.x = p.getInt("fx", (sw - barLp.width) / 2);
                barLp.y = p.getInt("fy", sh - hgt - (int) (90 * dp));
            } else {
                barLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, hgt, type, flags, PixelFormat.TRANSLUCENT);
                barLp.gravity = Gravity.BOTTOM;
            }
            if (Build.VERSION.SDK_INT >= 28) barLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            barLp.alpha = Math.max(.3f, Math.min(1f, p.getInt("alpha", 100) / 100f));
            try { wm.addView(bar, barLp); } catch (Exception e) { bar = null; }
        }
        String edgeSide = p.getBoolean("edge", false) ? p.getString("edgeSide", "right") : "off";
        if (!"off".equals(edgeSide)) {
            edge = new EdgeView(ctx, "left".equals(edgeSide));
            edgeLp = new WindowManager.LayoutParams((int) (18 * dp), (int) (150 * dp), type, flags, PixelFormat.TRANSLUCENT);
            edgeLp.gravity = ("left".equals(edgeSide) ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
            edgeLp.y = (int) (p.getInt("edgeY", -60) * dp);
            try { wm.addView(edge, edgeLp); } catch (Exception e) { edge = null; }
        }
        applyVisibility();
    }

    void act(String what) {
        switch (what) {
            case "back": if (!host.back()) Toast.makeText(ctx, "Retour 🐾 : active « PupNav » dans l'accessibilité (voir PupTasks → Réglages)", Toast.LENGTH_LONG).show(); break;
            case "home": if (!host.home()) goHome(ctx); break;
            case "tasks": openTasks(ctx); break;
        }
    }

    // ================================================================== la barre
    class NavView extends View {
        final boolean floating;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap leather, iBack, iHome, iTasks;
        String[] order;
        int pressed = -1;
        float downX, downY, startX, startY;
        boolean dragging;
        final Runnable longPress = new Runnable() { @Override public void run() { onLong(); } };
        boolean longFired;

        NavView(Context c, boolean floating) {
            super(c);
            this.floating = floating;
            setLayerType(LAYER_TYPE_SOFTWARE, null); // pour les lueurs néon (setShadowLayer)
            leather = BitmapFactory.decodeResource(getResources(), R.drawable.nav_leather);
            iBack = BitmapFactory.decodeResource(getResources(), R.drawable.nav_back);
            iHome = BitmapFactory.decodeResource(getResources(), R.drawable.nav_home);
            iTasks = BitmapFactory.decodeResource(getResources(), R.drawable.nav_tasks);
            order = "samsung".equals(prefs(c).getString("order", "samsung")) ? new String[]{"tasks", "home", "back"} : new String[]{"back", "home", "tasks"};
        }

        RectF body() {
            float in = floating ? 4 * dp : 0;
            return new RectF(in, in + (floating ? 0 : 2 * dp), getWidth() - in, getHeight() - in);
        }

        @Override protected void onDraw(Canvas c) {
            RectF r = body();
            float rad = floating ? r.height() / 2 : 0;
            // ombre portée
            if (floating) { p.reset(); p.setAntiAlias(true); p.setColor(0x99000000); p.setShadowLayer(8 * dp, 0, 3 * dp, 0xCC000000); c.drawRoundRect(r, rad, rad, p); p.clearShadowLayer(); }
            // cuir
            p.reset(); p.setAntiAlias(true); p.setFilterBitmap(true);
            BitmapShader bs = new BitmapShader(leather, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            p.setShader(bs);
            c.drawRoundRect(r, rad, rad, p);
            // reflet verre (Vista)
            p.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{0x55FFFFFF, 0x14FFFFFF, 0x00000000, 0x33000000}, new float[]{0, .45f, .5f, 1}, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            // couture pointillée
            Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
            st.setStyle(Paint.Style.STROKE); st.setStrokeWidth(1.4f * dp); st.setColor(0x88FF9AD5);
            st.setPathEffect(new android.graphics.DashPathEffect(new float[]{4 * dp, 3 * dp}, 0));
            RectF s = new RectF(r); s.inset(5 * dp, 5 * dp);
            c.drawRoundRect(s, Math.max(0, rad - 5 * dp), Math.max(0, rad - 5 * dp), st);
            // liseré néon magenta
            Paint ne = new Paint(Paint.ANTI_ALIAS_FLAG);
            ne.setStyle(Paint.Style.STROKE); ne.setStrokeWidth(2 * dp); ne.setColor(0xFFFF3FA4);
            ne.setShadowLayer(7 * dp, 0, 0, 0xFFFF3FA4);
            if (floating) c.drawRoundRect(r, rad, rad, ne);
            else c.drawLine(0, r.top + 1 * dp, getWidth(), r.top + 1 * dp, ne);
            // rivets
            float cy = r.centerY();
            float seg = r.width() / 3f;
            for (int i = 1; i < 3; i++) rivet(c, r.left + seg * i, cy);
            if (floating) { rivet(c, r.left + rad * .55f, cy); rivet(c, r.right - rad * .55f, cy); }
            else { rivet(c, r.left + 10 * dp, cy); rivet(c, r.right - 10 * dp, cy); }
            // boutons
            float isz = Math.min(r.height() * .78f, seg * .6f);
            for (int i = 0; i < 3; i++) {
                float cx = r.left + seg * i + seg / 2;
                if (pressed == i) {
                    Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
                    g.setShader(new RadialGradient(cx, cy, isz * .9f, new int[]{0x88FF3FA4, 0x33FF3FA4, 0x00FF3FA4}, new float[]{0, .6f, 1}, Shader.TileMode.CLAMP));
                    c.drawCircle(cx, cy, isz * .9f, g);
                }
                Bitmap b = "back".equals(order[i]) ? iBack : "home".equals(order[i]) ? iHome : iTasks;
                float k = pressed == i ? .88f : 1f;
                RectF d = new RectF(cx - isz / 2 * k, cy - isz / 2 * k, cx + isz / 2 * k, cy + isz / 2 * k);
                p.reset(); p.setFilterBitmap(true); p.setAntiAlias(true);
                c.drawBitmap(b, null, d, p);
            }
        }

        void rivet(Canvas c, float x, float y) {
            float rr = 3.4f * dp;
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            q.setColor(0xAA000000); c.drawCircle(x + .6f * dp, y + .8f * dp, rr, q);
            q.setShader(new RadialGradient(x - rr * .35f, y - rr * .35f, rr * 1.4f, new int[]{0xFFFFFFFF, 0xFFC9C2D8, 0xFF5A5368}, new float[]{0, .35f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, rr, q);
        }

        int hit(float x) { float w = getWidth() / 3f; return Math.max(0, Math.min(2, (int) (x / w))); }

        void onLong() {
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (floating) { dragging = true; pressed = -1; invalidate(); return; }
            if (pressed >= 0 && "home".equals(order[pressed])) openTasks(ctx);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = hit(e.getX()); longFired = false; dragging = false;
                    downX = e.getRawX(); downY = e.getRawY(); startX = barLp.x; startY = barLp.y;
                    h.postDelayed(longPress, 450);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging && floating) {
                        barLp.x = (int) (startX + e.getRawX() - downX); barLp.y = (int) (startY + e.getRawY() - downY);
                        try { wm.updateViewLayout(this, barLp); } catch (Exception ignored) { }
                    } else if (Math.abs(e.getRawX() - downX) > 20 * dp || Math.abs(e.getRawY() - downY) > 20 * dp) { h.removeCallbacks(longPress); if (!floating) { pressed = -1; invalidate(); } }
                    return true;
                case MotionEvent.ACTION_UP:
                    h.removeCallbacks(longPress);
                    if (dragging) { prefs(ctx).edit().putInt("fx", barLp.x).putInt("fy", barLp.y).apply(); dragging = false; }
                    else if (!longFired && pressed >= 0) act(order[pressed]);
                    pressed = -1; invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(longPress); pressed = -1; dragging = false; invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }

    // ================================================================== la poignée de bord
    class EdgeView extends View {
        final boolean left;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float x0, y0; boolean fired;
        EdgeView(Context c, boolean left) { super(c); this.left = left; setLayerType(LAYER_TYPE_SOFTWARE, null); }
        @Override protected void onDraw(Canvas c) {
            float x = left ? 3 * dp : getWidth() - 3 * dp;
            p.setStrokeWidth(3 * dp); p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xCCFF3FA4); p.setShadowLayer(6 * dp, 0, 0, 0xFFFF3FA4);
            c.drawLine(x, getHeight() * .2f, x, getHeight() * .8f, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: x0 = e.getRawX(); y0 = e.getRawY(); fired = false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - x0;
                    if (!fired && (left ? dx : -dx) > 36 * dp) { fired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); openTasks(ctx); }
                    return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: return true;
            }
            return super.onTouchEvent(e);
        }
    }
}

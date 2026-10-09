package fr.piika.puppyphone;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

/** Écran de la Veille puppy : horloge + batterie puppy sur noir OLED, par-dessus le verrouillage. Toucher = quitter. */
public class VeilleActivity extends Activity {
    Sieste s;
    final Handler h = new Handler(Looper.getMainLooper());
    boolean preview, ended;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        preview = getIntent().getBooleanExtra("preview", false);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        s = new Sieste(this, "/veille.html");
        View v = s.create();
        v.setBackgroundColor(0xFF000000);
        setContentView(v);
        Sieste.dim(getWindow(), preview ? 2 : Pelage.sp(this).getInt("veilleLum", 0));
        v.setOnTouchListener((x, e) -> { if (e.getAction() == android.view.MotionEvent.ACTION_UP) { ended = true; PupVeille.showing = false; finish(); } return true; });
        int sec = Pelage.sp(this).getInt("veilleDuree", 30);
        if (preview) sec = 10;
        h.postDelayed(() -> { if (s != null) s.emit("dodo", ""); }, Math.max(0, sec * 1000L - 2000));
        h.postDelayed(this::timeUp, sec * 1000L);
    }
    void timeUp() {
        ended = true; PupVeille.showing = false;
        if (!preview) PupVeille.sleepNow();
        finish();
    }
    @Override protected void onResume() { super.onResume(); PupVeille.showing = !preview; s.start(); h.postDelayed(() -> Sieste.dim(getWindow(), preview ? 2 : Pelage.sp(this).getInt("veilleLum", 0)), 400); }
    @Override protected void onPause() {
        h.removeCallbacksAndMessages(null);
        if (s != null) s.stop();
        super.onPause();
        if (!ended) h.postDelayed(() -> PupVeille.showing = false, 1500);
        finish();
    }
}

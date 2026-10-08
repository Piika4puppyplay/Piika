package fr.piika.puppyphone;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/** Horloge de nuit : la sieste du chiot en plein écran (touche l'écran pour quitter). */
public class SiesteActivity extends Activity {
    Sieste s;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        s = new Sieste(this);
        View v = s.create();
        setContentView(v);
        v.setOnTouchListener((x, e) -> { if (e.getAction() == android.view.MotionEvent.ACTION_UP) finish(); return true; });
    }
    @Override protected void onResume() { super.onResume(); s.start(); }
    @Override protected void onPause() { s.stop(); super.onPause(); finish(); }
}

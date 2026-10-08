package fr.piika.puppyphone;

import android.service.dreams.DreamService;

/** Économiseur d'écran Android : la sieste du chiot pendant la charge. */
public class PupDream extends DreamService {
    Sieste s;
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(Pelage.sp(this).getBoolean("siesteBright", false));
        s = new Sieste(this);
        setContentView(s.create());
    }
    @Override public void onDreamingStarted() { super.onDreamingStarted(); if (s != null) s.start(); }
    @Override public void onDreamingStopped() { if (s != null) s.stop(); s = null; super.onDreamingStopped(); }
    @Override public void onDetachedFromWindow() { if (s != null) s.stop(); s = null; super.onDetachedFromWindow(); }
}

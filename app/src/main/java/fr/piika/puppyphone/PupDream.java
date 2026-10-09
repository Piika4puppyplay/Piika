package fr.piika.puppyphone;

import android.service.dreams.DreamService;

/** Économiseur d'écran Android : la sieste du chiot pendant la charge. */
public class PupDream extends DreamService {
    Sieste s;
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        boolean bright = Pelage.sp(this).getBoolean("siesteBright", false);
        setScreenBright(bright);
        s = new Sieste(this);
        setContentView(s.create());
        Sieste.dim(getWindow(), bright); // One UI ignore parfois setScreenBright : on force la luminosité de la fenêtre
    }
    @Override public void onDreamingStarted() { super.onDreamingStarted(); if (s != null) s.start(); }
    @Override public void onDreamingStopped() { if (s != null) s.stop(); s = null; super.onDreamingStopped(); }
    @Override public void onDetachedFromWindow() { if (s != null) s.stop(); s = null; super.onDetachedFromWindow(); }
}

package fr.piika.puppyphone;

import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;

/** Économiseur d'écran Android : la sieste du chiot pendant la charge, tamisée, qui s'éteint toute seule. */
public class PupDream extends DreamService {
    Sieste s;
    final Handler h = new Handler(Looper.getMainLooper());
    int lum;
    final Runnable redim = () -> Sieste.dim(getWindow(), lum);
    final Runnable fade = () -> { if (s != null) s.emit("dodo", ""); };
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        lum = Sieste.lum(this);
        setScreenBright(lum == 2);
        s = new Sieste(this);
        setContentView(s.create());
        Sieste.dim(getWindow(), lum); // One UI ignore parfois setScreenBright : on force la luminosité de la fenêtre
    }
    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (s != null) s.start();
        h.postDelayed(redim, 300); h.postDelayed(redim, 1500); // certains Samsung remettent la luminosité au démarrage
        int sec = Sieste.duree(this);
        if (sec > 0) {
            h.postDelayed(fade, Math.max(0, sec * 1000L - 2500));
            h.postDelayed(() -> { if (!PupVeille.sleepNow()) { /* sans PupNav : on reste en noir total (OLED éteint) pour éviter la boucle */ } }, sec * 1000L);
        }
    }
    @Override public void onDreamingStopped() { h.removeCallbacksAndMessages(null); if (s != null) s.stop(); s = null; super.onDreamingStopped(); }
    @Override public void onDetachedFromWindow() { h.removeCallbacksAndMessages(null); if (s != null) s.stop(); s = null; super.onDetachedFromWindow(); }
}

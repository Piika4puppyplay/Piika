package fr.piika.puppyphone;

import android.content.Context;

/** « Chalet sous la neige ❄️ » — fond animé peint en couches. */
public class PupWallChalet extends PupLiveWallpaper {
    @Override Scene scene(Context c) { return new LayeredScene(c, "chalet"); }
}

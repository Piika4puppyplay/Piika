package fr.piika.puppyphone;

import android.content.Context;

/** « Pup Arcade sous la pluie ☔ » — fond animé peint en couches. */
public class PupWallArcade extends PupLiveWallpaper {
    @Override Scene scene(Context c) { return new LayeredScene(c, "arcade"); }
}

package fr.piika.puppyphone;

import android.content.Context;

/** « Base lunaire des chiots 🌕 » — fond animé peint en couches. */
public class PupWallLune extends PupLiveWallpaper {
    @Override Scene scene(Context c) { return new LayeredScene(c, "lune"); }
}

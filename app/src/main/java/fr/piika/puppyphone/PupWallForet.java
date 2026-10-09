package fr.piika.puppyphone;

import android.content.Context;

/** « Forêt enchantée 🌲 » — fond animé peint en couches. */
public class PupWallForet extends PupLiveWallpaper {
    @Override Scene scene(Context c) { return new LayeredScene(c, "foret"); }
}

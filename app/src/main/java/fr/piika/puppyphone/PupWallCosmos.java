package fr.piika.puppyphone;

import android.content.Context;

/** « Pup dans l'espace » : chiot astronaute, nébuleuse, planète à anneaux (catégorie Espace). */
public class PupWallCosmos extends PupLiveWallpaper {
    @Override Scene scene(Context c) { return new LayeredScene(c, "cosmos"); }
}

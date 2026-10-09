package fr.piika.puppyphone;

import android.content.Context;

/** « Aurore des pattes » : aurore boréale, montagnes enneigées, lac gelé (catégorie Nature). */
public class PupWallAurore extends PupLiveWallpaper {
    @Override Scene scene(Context c) { return new LayeredScene(c, "aurore"); }
}

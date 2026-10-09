package fr.piika.puppyphone;

import android.content.Context;

/** « La niche douillette » : chiot qui dort dans son panier capitonné, guirlande et néon (catégorie Cocooning). */
public class PupWallNiche extends PupLiveWallpaper {
    @Override Scene scene(Context c) { return new LayeredScene(c, "niche"); }
}

package fr.piika.puppyphone;

import android.app.Application;

/** Application PuppyPhone : déterre l'os (restauration de sauvegarde) avant que la moindre page ne s'ouvre. */
public class PupApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        String proc = "";
        try { proc = android.os.Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : ""; } catch (Throwable ignored) { }
        if (proc.endsWith(":phoenix")) return; // le phénix déterre l'os lui-même, après avoir fermé les autres processus
        try { NicheBackup.applyPending(this); } catch (Throwable ignored) { }
    }
}

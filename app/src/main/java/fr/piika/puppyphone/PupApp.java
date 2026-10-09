package fr.piika.puppyphone;

import android.app.Application;

/** Application PuppyPhone : déterre l'os (restauration de sauvegarde) avant que la moindre page ne s'ouvre. */
public class PupApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        String proc = "";
        try { proc = android.os.Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : ""; } catch (Throwable ignored) { }
        try { CrashGuard.installHandler(this); } catch (Throwable ignored) { }
        // WebView utilisé dans plusieurs processus : chaque process annexe a besoin de son propre dossier (sinon crash)
        if (android.os.Build.VERSION.SDK_INT >= 28 && !proc.isEmpty() && proc.contains(":")) {
            try { android.webkit.WebView.setDataDirectorySuffix(proc.substring(proc.indexOf(":") + 1)); } catch (Throwable ignored) { }
        }
        if (proc.endsWith(":phoenix") || proc.endsWith(":recovery")) return;
        try { NicheBackup.applyPending(this); } catch (Throwable ignored) { }
    }
}

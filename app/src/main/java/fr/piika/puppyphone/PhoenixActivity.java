package fr.piika.puppyphone;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/** Redémarrage propre de la niche (processus séparé) : ferme PuppyPhone et le clavier, déterre l'os, relance l'accueil. */
public class PhoenixActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            ActivityManager am = getSystemService(ActivityManager.class);
            for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses())
                if (p.pid != Process.myPid() && p.uid == Process.myUid()) Process.killProcess(p.pid);
        } catch (Exception ignored) { }
        try { Thread.sleep(400); } catch (Exception ignored) { }
        NicheBackup.applyPending(this);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        Runtime.getRuntime().exit(0);
    }
}

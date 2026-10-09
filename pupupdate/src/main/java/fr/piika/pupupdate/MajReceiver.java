package fr.piika.pupupdate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Retour de l'installateur Android. PuppyPhone installé → on te ramène sur son accueil. */
public class MajReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String pkg = i.getStringExtra("pkg");
        boolean puppy = Maj.PUPPY.equals(pkg);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); } catch (Exception e) { Toast.makeText(c, "Ouvre PupUpdate pour confirmer l'installation", Toast.LENGTH_LONG).show(); }
            }
            MajActivity.status("confirm", "");
            return;
        }
        if (st == PackageInstaller.STATUS_SUCCESS) {
            if (puppy) {
                MajActivity.status("done", "");
                Maj.notifyDone(c, "✅ PuppyPhone est à jour !", "v1.0." + Maj.version(c, Maj.PUPPY) + " · touche pour revenir sur l'accueil 🐾");
                if (Maj.sp(c).getBoolean("retour", true)) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (Maj.openPuppy(c)) MajActivity.finishAll();
                    }, 900);
                }
            } else MajActivity.status("selfdone", "");
            return;
        }
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String why = st == PackageInstaller.STATUS_FAILURE_ABORTED ? "Installation annulée"
                : st == PackageInstaller.STATUS_FAILURE_CONFLICT ? "Conflit de signature (l'APK ne vient pas de ton GitHub ?)"
                : st == PackageInstaller.STATUS_FAILURE_STORAGE ? "Pas assez de place dans la gamelle"
                : st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ? "Version incompatible"
                : "Échec de l'installation" + (msg == null ? "" : " : " + msg);
        MajActivity.status("error", why);
        Toast.makeText(c, "PupUpdate : " + why, Toast.LENGTH_LONG).show();
    }
}

package fr.piika.puppyphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Retour de l'installateur Android pendant une mise à jour PupUpdate. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); } catch (Exception e) { Toast.makeText(c, "Ouvre PupUpdate pour confirmer l'installation", Toast.LENGTH_LONG).show(); }
            }
            UpdateActivity.status("confirm", "");
            return;
        }
        if (st == PackageInstaller.STATUS_SUCCESS) { UpdateActivity.status("done", ""); return; }
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String why = st == PackageInstaller.STATUS_FAILURE_ABORTED ? "Installation annulée"
                : st == PackageInstaller.STATUS_FAILURE_CONFLICT ? "Conflit de signature (l'APK ne vient pas de ton GitHub ?)"
                : st == PackageInstaller.STATUS_FAILURE_STORAGE ? "Pas assez de place dans la gamelle"
                : st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ? "Version incompatible"
                : "Échec de l'installation" + (msg == null ? "" : " : " + msg);
        UpdateActivity.status("error", why);
        Toast.makeText(c, "PupUpdate : " + why, Toast.LENGTH_LONG).show();
    }
}

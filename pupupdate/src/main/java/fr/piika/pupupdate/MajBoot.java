package fr.piika.pupupdate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Redémarrage du téléphone / mise à jour de PupUpdate : on reprogramme la vérification automatique.
 *  Et juste après que PupUpdate vient de se mettre à jour (MY_PACKAGE_REPLACED), on atterrit quelque part sans planter. */
public class MajBoot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Maj.schedule(c);
        if (i != null && Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            // PupUpdate vient de finir SA mise à jour → on ouvre PuppyPhone, sinon la cascade (Réglages → verrouillage → gestionnaire root).
            try { Maj.landSafely(c); } catch (Exception ignored) { }
        }
    }
}

package fr.piika.pupupdate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Redémarrage du téléphone / mise à jour de PupUpdate : on reprogramme la vérification automatique. */
public class MajBoot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) { Maj.schedule(c); }
}

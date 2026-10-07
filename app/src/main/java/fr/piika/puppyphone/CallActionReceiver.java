package fr.piika.puppyphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boutons Répondre / Raccrocher des notifications d'appel. */
public class CallActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if ("answer".equals(i.getAction())) {
            PupInCallService.answer();
            c.startActivity(new Intent(c, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } else if ("hangup".equals(i.getAction())) PupInCallService.hangup();
    }
}

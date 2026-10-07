package fr.piika.puppyphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Avis de MMS (WAP push) : on lance le téléchargement. */
public class MmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        byte[] pdu = i.getByteArrayExtra("data");
        if (pdu == null) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try { SmsCore.downloadMms(c, pdu); } finally { pr.finish(); }
        }, "pupsms-mms").start();
    }
}

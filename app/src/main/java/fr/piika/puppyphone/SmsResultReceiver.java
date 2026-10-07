package fr.piika.puppyphone;

import android.app.Activity;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Résultats d'envoi / téléchargement, et réponse rapide depuis la notification. */
public class SmsResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        int code = getResultCode();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                switch (a) {
                    case SmsCore.ACT_SMS_SENT:
                        if (i.getBooleanExtra("last", true) || code != Activity.RESULT_OK) SmsCore.markSms(c, i.getData(), code == Activity.RESULT_OK);
                        break;
                    case SmsCore.ACT_MMS_SENT: {
                        SmsCore.markMms(c, i.getStringExtra("mms"), code == Activity.RESULT_OK);
                        String f = i.getStringExtra("file");
                        if (f != null) SmsCore.pduPath(c, Uri.parse(f)).delete();
                        if (code != Activity.RESULT_OK) SmsCore.notifyIncoming(c, null, "⚠️ MMS non envoyé (code " + code + ")", null);
                        break;
                    }
                    case SmsCore.ACT_MMS_DOWNLOADED:
                        if (code == Activity.RESULT_OK) SmsCore.onMmsDownloaded(c, Uri.parse(i.getStringExtra("file")), i.getStringExtra("from"));
                        break;
                    case SmsCore.ACT_REPLY: {
                        Bundle r = RemoteInput.getResultsFromIntent(i);
                        CharSequence txt = r == null ? null : r.getCharSequence(SmsCore.KEY_REPLY);
                        String to = i.getStringExtra("addr");
                        if (txt != null && to != null) {
                            SmsCore.sendSms(c, to, txt.toString());
                            SmsCore.markThreadRead(c, to);
                        }
                        break;
                    }
                    case SmsCore.ACT_READ:
                        SmsCore.markThreadRead(c, i.getStringExtra("addr"));
                        break;
                }
            } catch (Exception ignored) {
            } finally { pr.finish(); }
        }, "pupsms-result").start();
    }
}

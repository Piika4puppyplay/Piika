package fr.piika.puppyphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/** SMS reçu (PupSMS appli par défaut) : on l'enregistre et on prévient. */
public class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(i.getAction())) return;
        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(i);
        if (msgs == null || msgs.length == 0) return;
        StringBuilder body = new StringBuilder();
        String from = msgs[0].getDisplayOriginatingAddress();
        long sent = msgs[0].getTimestampMillis();
        for (SmsMessage m : msgs) if (m != null && m.getDisplayMessageBody() != null) body.append(m.getDisplayMessageBody());
        int sub = i.getIntExtra("subscription", -1);
        android.net.Uri u = SmsCore.storeIncomingSms(c, from, body.toString(), sent, sub);
        SmsCore.notifyIncoming(c, from, body.toString(), u);
        SmsCore.changed();
    }
}

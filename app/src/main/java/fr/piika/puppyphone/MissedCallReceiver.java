package fr.piika.puppyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telecom.TelecomManager;

/** Appels manqués (Android confie cette notification à l'appli Téléphone par défaut). */
public class MissedCallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        int count = i.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0);
        String num = i.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (count <= 0) { nm.cancel(4343); return; }
        nm.createNotificationChannel(new NotificationChannel("pupphone_missed", "Appels manqués", NotificationManager.IMPORTANCE_DEFAULT));
        String who = num == null || num.isEmpty() ? "Numéro masqué" : SmsCore.nameOr(c, num);
        PendingIntent open = PendingIntent.getActivity(c, 10, new Intent(c, DialerActivity.class).putExtra("tab", "recents").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.graphics.drawable.Icon ic = android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_paw);
        Notification.Builder b = new Notification.Builder(c, "pupphone_missed")
                .setSmallIcon(R.drawable.ic_paw).setColor(0xFFFF4D5E)
                .setContentTitle(count > 1 ? count + " appels manqués 🐾" : "Appel manqué 🐾")
                .setContentText(count > 1 ? "Dernier : " + who : who)
                .setAutoCancel(true).setContentIntent(open);
        if (num != null && !num.isEmpty()) {
            PendingIntent back = PendingIntent.getActivity(c, 11, new Intent(c, DialerActivity.class).putExtra("call", num).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent sms = PendingIntent.getActivity(c, 12, new Intent(c, SmsActivity.class).putExtra("addr", num).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(ic, "Rappeler", back).build());
            b.addAction(new Notification.Action.Builder(ic, "SMS", sms).build());
        }
        nm.notify(4343, b.build());
    }
}

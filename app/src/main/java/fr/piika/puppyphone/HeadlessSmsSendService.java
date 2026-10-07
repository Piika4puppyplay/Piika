package fr.piika.puppyphone;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;

/** « Répondre par message » (ex. refuser un appel avec un SMS). */
public class HeadlessSmsSendService extends Service {
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i, int flags, int id) {
        try {
            if (i != null && i.getData() != null) {
                String to = Uri.decode(i.getData().getSchemeSpecificPart()).replaceAll("[^0-9+]", "");
                String txt = i.getStringExtra(Intent.EXTRA_TEXT);
                if (!to.isEmpty() && txt != null && !txt.isEmpty()) new Thread(() -> SmsCore.sendSms(this, to, txt)).start();
            }
        } catch (Exception ignored) { }
        stopSelf(id);
        return START_NOT_STICKY;
    }
}

package fr.piika.puppyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.telecom.VideoProfile;

import java.util.ArrayList;
import java.util.List;

/** Service d'appel de PupPhone (quand PupPhone est l'appli Téléphone par défaut). */
public class PupInCallService extends InCallService {
    static final String CH_IN = "pupphone_in", CH_ON = "pupphone_on";
    static final int NID = 4242;
    static PupInCallService inst;
    static final List<Call> calls = new ArrayList<>();
    static volatile CallAudioState audio;
    static volatile Runnable listener;

    static void changed() { Runnable l = listener; if (l != null) l.run(); }

    final Call.Callback cb = new Call.Callback() {
        @Override public void onStateChanged(Call call, int state) { updateNotif(); changed(); }
        @Override public void onDetailsChanged(Call call, Call.Details d) { changed(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        inst = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel a = new NotificationChannel(CH_IN, "Appels entrants", NotificationManager.IMPORTANCE_HIGH);
        a.setSound(null, null); // la sonnerie est jouée par le système
        nm.createNotificationChannel(a);
        nm.createNotificationChannel(new NotificationChannel(CH_ON, "Appel en cours", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public void onDestroy() { if (inst == this) inst = null; super.onDestroy(); }

    @Override public void onCallAdded(Call call) {
        synchronized (calls) { calls.add(call); }
        call.registerCallback(cb);
        if (call.getState() != Call.STATE_RINGING) openCallScreen();
        updateNotif();
        changed();
    }

    @Override public void onCallRemoved(Call call) {
        call.unregisterCallback(cb);
        synchronized (calls) { calls.remove(call); }
        updateNotif();
        changed();
    }

    @Override public void onCallAudioStateChanged(CallAudioState s) { audio = s; changed(); }

    void openCallScreen() {
        try { startActivity(new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); } catch (Exception ignored) { }
    }

    static Call ringing() { synchronized (calls) { for (Call c : calls) if (c.getState() == Call.STATE_RINGING) return c; } return null; }
    static Call current() {
        synchronized (calls) {
            for (Call c : calls) if (c.getState() == Call.STATE_ACTIVE || c.getState() == Call.STATE_DIALING || c.getState() == Call.STATE_CONNECTING) return c;
            return calls.isEmpty() ? null : calls.get(0);
        }
    }

    static String number(Call c) {
        Uri h = c.getDetails().getHandle();
        return h == null ? "" : h.getSchemeSpecificPart();
    }

    void updateNotif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Call r = ringing();
        Call cur = current();
        if (r == null && cur == null) { nm.cancel(NID); return; }
        Intent open = new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent po = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent hang = PendingIntent.getBroadcast(this, 2, new Intent(this, CallActionReceiver.class).setAction("hangup"), PendingIntent.FLAG_IMMUTABLE);
        android.graphics.drawable.Icon ic = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_paw);
        Notification n;
        if (r != null) {
            String who = SmsCore.nameOr(this, number(r));
            PendingIntent ans = PendingIntent.getBroadcast(this, 3, new Intent(this, CallActionReceiver.class).setAction("answer"), PendingIntent.FLAG_IMMUTABLE);
            n = new Notification.Builder(this, CH_IN)
                    .setSmallIcon(R.drawable.ic_paw).setColor(0xFF3DFFB0)
                    .setContentTitle("📞 " + who).setContentText("Appel entrant · PupPhone")
                    .setCategory(Notification.CATEGORY_CALL).setOngoing(true)
                    .setFullScreenIntent(po, true).setContentIntent(po)
                    .addAction(new Notification.Action.Builder(ic, "Refuser", hang).build())
                    .addAction(new Notification.Action.Builder(ic, "Répondre", ans).build())
                    .build();
            nm.notify(NID, n);
            // téléphone déverrouillé : on ouvre aussi l'écran directement
            openCallScreen();
        } else {
            String who = SmsCore.nameOr(this, number(cur));
            String st = cur.getState() == Call.STATE_ACTIVE ? "Appel en cours" : cur.getState() == Call.STATE_HOLDING ? "En attente" : "Appel…";
            Notification.Builder b = new Notification.Builder(this, CH_ON)
                    .setSmallIcon(R.drawable.ic_paw).setColor(0xFF3DFFB0)
                    .setContentTitle(who).setContentText(st + " · PupPhone")
                    .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setContentIntent(po)
                    .addAction(new Notification.Action.Builder(ic, "Raccrocher", hang).build());
            if (cur.getState() == Call.STATE_ACTIVE && cur.getDetails().getConnectTimeMillis() > 0) b.setUsesChronometer(true).setWhen(cur.getDetails().getConnectTimeMillis()).setShowWhen(true);
            nm.notify(NID, b.build());
        }
    }

    // actions utilisées par l'écran d'appel
    static void answer() { Call r = ringing(); if (r != null) r.answer(VideoProfile.STATE_AUDIO_ONLY); }
    static void hangup() {
        Call r = ringing();
        if (r != null) { r.reject(false, null); return; }
        Call c = current();
        if (c != null) c.disconnect();
    }
    static void setMute(boolean m) { if (inst != null) inst.setMuted(m); }
    static void route(int r) { if (inst != null) inst.setAudioRoute(r); }
}

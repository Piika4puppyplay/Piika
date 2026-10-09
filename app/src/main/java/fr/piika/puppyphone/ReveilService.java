package fr.piika.puppyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

/** PupRéveil : fait sonner le réveil (son de chiot qui monte doucement, vibreur) et ouvre l'écran de réveil. */
public class ReveilService extends Service {
    static final String CH = "pupreveil_ring", ACT_STOP = "stop", ACT_SNOOZE = "snooze";
    static final int NID = 4300;
    static volatile int ringingId = -1;
    static volatile boolean awaitUnlock;
    final Handler h = new Handler(Looper.getMainLooper());
    MediaPlayer mp; Vibrator vib; PowerManager.WakeLock wl;
    float vol = .1f; boolean crescendo;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int startId) {
        String act = i == null ? null : i.getAction();
        if (ACT_STOP.equals(act)) {
            if (i.getBooleanExtra("bravo", false) && ringingId != -1) android.widget.Toast.makeText(this, "🐶 Bravo, tu es bien réveillé ! Bonne journée 🐾", android.widget.Toast.LENGTH_LONG).show();
            stopAll(); return START_NOT_STICKY;
        }
        if (ACT_SNOOZE.equals(act)) { Reveil.snooze(this, ringingId); stopAll(); return START_NOT_STICKY; }
        int id = i == null ? -1 : i.getIntExtra("id", -1);
        JSONObject a = Reveil.find(this, id);
        if (a == null) a = new JSONObject();
        ringingId = id; awaitUnlock = false;
        startForegroundNow(a);
        ring(a);
        h.postDelayed(this::stopAll, 10 * 60_000L); // au bout de 10 min on laisse le chiot se rendormir
        return START_NOT_STICKY;
    }

    void startForegroundNow(JSONObject a) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "PupRéveil — sonnerie", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null); ch.setBypassDnd(true); ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
        Intent full = new Intent(this, ReveilRingActivity.class).putExtra("id", ringingId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPI = PendingIntent.getActivity(this, 4301, full, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 4302, new Intent(this, ReveilService.class).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent snz = PendingIntent.getService(this, 4303, new Intent(this, ReveilService.class).setAction(ACT_SNOOZE), PendingIntent.FLAG_IMMUTABLE);
        String label = a.optString("label", "");
        Notification n = new Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_paw)
                .setContentTitle("⏰ Debout, petit chiot ! " + String.format(java.util.Locale.FRANCE, "%02d:%02d", a.optInt("h"), a.optInt("m")))
                .setContentText(label.isEmpty() ? "Le PupRéveil sonne 🐾" : label)
                .setCategory(Notification.CATEGORY_ALARM).setColor(0xFFFF3FA4).setOngoing(true)
                .setContentIntent(fullPI)
                .addAction(new Notification.Action.Builder(null, "😴 Encore " + a.optInt("snooze", 5) + " min", snz).build())
                .addAction(new Notification.Action.Builder(null, "🐾 Je me lève", stop).build())
                .build();
        boolean overlay = PupVeille.showRing(this); // par-dessus le verrouillage via PupNav (ne dépend pas de One UI)
        if (!overlay) n = new Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_paw)
                .setContentTitle("⏰ Debout, petit chiot !").setContentText(label.isEmpty() ? "Le PupRéveil sonne 🐾" : label)
                .setCategory(Notification.CATEGORY_ALARM).setColor(0xFFFF3FA4).setOngoing(true)
                .setFullScreenIntent(fullPI, true).setContentIntent(fullPI)
                .addAction(new Notification.Action.Builder(null, "😴 Encore " + a.optInt("snooze", 5) + " min", snz).build())
                .addAction(new Notification.Action.Builder(null, "🐾 Je me lève", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NID, n);
        if (!overlay) try { startActivity(full); } catch (Exception ignored) { }
    }

    void ring(JSONObject a) {
        try {
            wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "puppyphone:reveil");
            wl.acquire(11 * 60_000L);
        } catch (Exception ignored) { }
        String son = a.optString("sound", "wouf");
        crescendo = a.optBoolean("douceur", true);
        vol = crescendo ? .08f : 1f;
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            if ("systeme".equals(son)) {
                Uri u = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                mp.setDataSource(this, u);
            } else {
                try (AssetFileDescriptor fd = getAssets().openFd("www/sons/reveil_" + son + ".wav")) { mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength()); }
            }
            mp.setLooping(true);
            mp.setVolume(vol, vol);
            mp.prepare(); mp.start();
        } catch (Exception e) {
            try { mp = MediaPlayer.create(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)); if (mp != null) { mp.setLooping(true); mp.start(); } } catch (Exception ignored) { }
        }
        if (crescendo) h.post(new Runnable() { @Override public void run() { if (mp == null) return; vol = Math.min(1f, vol + .03f); try { mp.setVolume(vol, vol); } catch (Exception ignored) { } if (vol < 1f) h.postDelayed(this, 1000); } });
        if (a.optBoolean("vib", true)) {
            vib = getSystemService(Vibrator.class);
            try { vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 300, 400, 1200}, 0), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()); } catch (Exception ignored) { }
        }
    }

    void stopAll() {
        h.removeCallbacksAndMessages(null);
        try { if (mp != null) { mp.stop(); mp.release(); } } catch (Exception ignored) { }
        mp = null;
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) { }
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        ringingId = -1;
        ReveilRingActivity.closeAll();
        PupVeille.hideRing();
        awaitUnlock = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        Reveil.schedule(this);
    }

    @Override public void onDestroy() { h.removeCallbacksAndMessages(null); try { if (mp != null) mp.release(); } catch (Exception ignored) { } try { if (vib != null) vib.cancel(); } catch (Exception ignored) { } super.onDestroy(); }
}

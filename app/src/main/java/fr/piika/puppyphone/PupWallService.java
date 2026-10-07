package fr.piika.puppyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Surface;

/** Joue le fond vidéo MP4 : l'image va sur l'écran d'accueil, le son continue même écran éteint. */
public class PupWallService extends Service implements AudioManager.OnAudioFocusChangeListener {
    static final String PREFS = "pup_wall";
    static final String ACT_START = "fr.piika.puppyphone.START";
    static final String ACT_TOGGLE = "fr.piika.puppyphone.TOGGLE";
    static final String ACT_STOP = "fr.piika.puppyphone.STOP";
    static final String CH = "pup_wall";
    static final int NID = 7;

    static PupWallService running;

    MediaPlayer mp;
    String loadedUri;
    Surface surface;
    volatile int videoW, videoH;
    boolean prepared, hasFocus, focusPaused;
    Runnable listener;
    AudioManager am;
    AudioFocusRequest afr;
    final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();

    class LocalBinder extends Binder { PupWallService get() { return PupWallService.this; } }
    final IBinder binder = new LocalBinder();

    @Override public IBinder onBind(Intent i) { return binder; }

    @Override public void onCreate() {
        super.onCreate();
        running = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "Fond vidéo PuppyPhone", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Contrôle du son du fond d'écran vidéo");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    SharedPreferences p() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    boolean muted() { return p().getBoolean("wall_muted", false); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startFg();
        String a = intent == null || intent.getAction() == null ? ACT_START : intent.getAction();
        if (ACT_STOP.equals(a)) { shutdown(); return START_NOT_STICKY; }
        if (ACT_TOGGLE.equals(a)) p().edit().putBoolean("wall_muted", !muted()).commit();
        if (!"video".equals(p().getString("wall_type", ""))) { shutdown(); return START_NOT_STICKY; }
        load();
        applyState();
        return START_STICKY;
    }

    void startFg() {
        Notification n = buildNotif();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NID, n);
    }

    Notification buildNotif() {
        boolean m = muted();
        Intent t = new Intent(this, PupWallService.class).setAction(ACT_TOGGLE);
        PendingIntent pt = PendingIntent.getForegroundService(this, 1, t, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent s = new Intent(this, PupWallService.class).setAction(ACT_STOP);
        PendingIntent ps = PendingIntent.getService(this, 2, s, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Action toggle = new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_paw),
                m ? "Remettre le son 🔊" : "Couper le son 🔇", pt).build();
        Notification.Action stop = new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_paw),
                "Arrêter le fond", ps).build();
        String name = p().getString("wall_name", "fond vidéo");
        return new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_paw)
                .setContentTitle("PuppyPhone · " + (m ? "son coupé 🔇" : "son en lecture 🔊"))
                .setContentText(name)
                .setColor(0xFFFF3FA4)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(toggle)
                .addAction(stop)
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0))
                .build();
    }

    void updateNotif() {
        try { getSystemService(NotificationManager.class).notify(NID, buildNotif()); } catch (Exception ignored) { }
    }

    void load() {
        String u = p().getString("wall_uri", null);
        if (u == null) return;
        if (u.equals(loadedUri) && mp != null) return;
        release();
        loadedUri = u;
        prepared = false;
        mp = new MediaPlayer();
        mp.setAudioAttributes(attrs);
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mp.setLooping(true);
        try {
            mp.setDataSource(this, Uri.parse(u));
        } catch (Exception e) {
            release();
            return;
        }
        if (surface != null) try { mp.setSurface(surface); } catch (Exception ignored) { }
        mp.setOnVideoSizeChangedListener((m, w, h) -> { videoW = w; videoH = h; notifyL(); });
        mp.setOnPreparedListener(m -> {
            prepared = true;
            videoW = m.getVideoWidth();
            videoH = m.getVideoHeight();
            applyState();
            notifyL();
        });
        mp.setOnErrorListener((m, w, x) -> { release(); notifyL(); return true; });
        mp.prepareAsync();
    }

    void notifyL() { Runnable l = listener; if (l != null) l.run(); }

    boolean isPlaying() {
        try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    void setSurface(Surface s) {
        surface = s;
        if (mp != null) try { mp.setSurface(s); } catch (Exception ignored) { }
        applyState();
    }

    /** Applique volume / muet / lecture-pause selon l'état. */
    void applyState() {
        updateNotif();
        if (mp == null || !prepared) return;
        boolean m = muted();
        if (!m && !hasFocus) requestFocus();
        if (m && hasFocus) abandonFocus();
        float v = p().getFloat("wall_vol", 1f);
        boolean audible = !m && !focusPaused;
        try { mp.setVolume(audible ? v : 0f, audible ? v : 0f); } catch (Exception ignored) { }
        boolean shouldPlay = audible || surface != null;
        try {
            if (shouldPlay && !mp.isPlaying()) mp.start();
            else if (!shouldPlay && mp.isPlaying()) mp.pause();
        } catch (Exception ignored) { }
        notifyL();
    }

    void requestFocus() {
        if (afr == null) afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs).setOnAudioFocusChangeListener(this).setWillPauseWhenDucked(false).build();
        hasFocus = am.requestAudioFocus(afr) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        focusPaused = false;
    }

    void abandonFocus() {
        if (afr != null) am.abandonAudioFocusRequest(afr);
        hasFocus = false;
    }

    @Override public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // une autre appli (musique, vidéo…) prend le son : on se met en sourdine
                hasFocus = false;
                p().edit().putBoolean("wall_muted", true).commit();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                focusPaused = true; // appel, GPS, notification…
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                focusPaused = false;
                break;
        }
        applyState();
    }

    void release() {
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
        }
        mp = null;
        prepared = false;
        loadedUri = null;
    }

    void shutdown() {
        release();
        abandonFocus();
        stopForeground(true);
        stopSelf();
        if (running == this) running = null;
        notifyL();
    }

    @Override public void onDestroy() {
        release();
        abandonFocus();
        if (running == this) running = null;
        super.onDestroy();
    }
}

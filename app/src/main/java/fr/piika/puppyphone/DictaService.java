package fr.piika.puppyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** PupDicta : enregistrement au micro en service de premier plan (continue écran éteint ou appli fermée). */
public class DictaService extends Service {
    static final String ACT_START = "fr.piika.puppyphone.DICTA_START", ACT_PAUSE = "fr.piika.puppyphone.DICTA_PAUSE", ACT_RESUME = "fr.piika.puppyphone.DICTA_RESUME",
            ACT_STOP = "fr.piika.puppyphone.DICTA_STOP", ACT_MARK = "fr.piika.puppyphone.DICTA_MARK";
    static final int NID = 4600;
    static final String CH = "pupdicta";

    static volatile boolean rec, paused;
    static volatile int level;               // 0..255, dernier niveau mesuré
    static volatile File cur;
    static volatile String lastSaved = "", error = "";
    static long accMs, segStart;
    static final ArrayList<Long> marks = new ArrayList<>();
    static final ArrayList<Integer> wave = new ArrayList<>();
    static Runnable onChange;

    MediaRecorder mr;
    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable poll = new Runnable() { @Override public void run() {
        if (mr != null && rec && !paused) {
            int a = 0; try { a = mr.getMaxAmplitude(); } catch (Exception ignored) { }
            // échelle logarithmique façon VU : 0..255
            double db = a <= 0 ? -60 : 20 * Math.log10(a / 32767.0);
            int v = (int) Math.max(0, Math.min(255, (db + 60) / 60 * 255));
            level = v; synchronized (wave) { wave.add(v); }
        } else level = 0;
        h.postDelayed(this, 100);
    } };

    static File dir(Context c) { File d = new File(c.getExternalFilesDir(null), "PupDicta"); d.mkdirs(); return d; }
    static long elapsed() { return accMs + (rec && !paused ? SystemClock.elapsedRealtime() - segStart : 0); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? null : i.getAction();
        if (ACT_START.equals(a)) start(i.getStringExtra("q"));
        else if (ACT_PAUSE.equals(a)) pause();
        else if (ACT_RESUME.equals(a)) resume();
        else if (ACT_MARK.equals(a)) mark();
        else if (ACT_STOP.equals(a)) stop();
        else if (!rec) stopSelf();
        return START_NOT_STICKY;
    }

    void start(String q) {
        if (rec) return;
        error = "";
        try {
            startFg();
            String name = "Dicta " + new SimpleDateFormat("yyyy-MM-dd HH'h'mm", Locale.FRANCE).format(new Date());
            File f = new File(dir(this), name + ".m4a"); int n = 2;
            while (f.exists()) f = new File(dir(this), name + " (" + n++ + ").m4a");
            mr = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            if ("eco".equals(q)) { mr.setAudioSamplingRate(22050); mr.setAudioEncodingBitRate(40000); mr.setAudioChannels(1); }
            else if ("hifi".equals(q)) { mr.setAudioSamplingRate(48000); mr.setAudioEncodingBitRate(192000); mr.setAudioChannels(2); }
            else { mr.setAudioSamplingRate(44100); mr.setAudioEncodingBitRate(96000); mr.setAudioChannels(1); }
            mr.setOutputFile(f.getAbsolutePath());
            mr.prepare(); mr.start();
            cur = f; rec = true; paused = false; accMs = 0; segStart = SystemClock.elapsedRealtime();
            synchronized (marks) { marks.clear(); } synchronized (wave) { wave.clear(); }
            h.removeCallbacks(poll); h.post(poll);
            notifyFg(); changed();
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            release(); rec = false; cur = null;
            stopForeground(true); stopSelf(); changed();
        }
    }
    void pause() {
        if (!rec || paused || mr == null) return;
        try { mr.pause(); accMs += SystemClock.elapsedRealtime() - segStart; paused = true; } catch (Exception ignored) { }
        notifyFg(); changed();
    }
    void resume() {
        if (!rec || !paused || mr == null) return;
        try { mr.resume(); segStart = SystemClock.elapsedRealtime(); paused = false; } catch (Exception ignored) { }
        notifyFg(); changed();
    }
    void mark() { if (rec) { synchronized (marks) { marks.add(elapsed()); } changed(); } }
    void stop() {
        if (!rec) { stopSelf(); return; }
        long total = elapsed();
        boolean ok = true;
        try { mr.stop(); } catch (Exception e) { ok = false; }
        release(); rec = false; paused = false; h.removeCallbacks(poll); level = 0;
        File f = cur; cur = null;
        if (f != null) {
            if (!ok || f.length() < 1024) { f.delete(); lastSaved = ""; error = "Enregistrement trop court"; }
            else {
                try {
                    JSONObject m = new JSONObject(); JSONArray mk = new JSONArray(), wv = new JSONArray();
                    synchronized (marks) { for (long x : marks) mk.put(x); }
                    synchronized (wave) { for (int x : wave) wv.put(x); }
                    m.put("ms", total).put("marks", mk).put("wave", wv);
                    try (FileOutputStream o = new FileOutputStream(new File(f.getParentFile(), f.getName() + ".json"))) { o.write(m.toString().getBytes("UTF-8")); }
                } catch (Exception ignored) { }
                lastSaved = f.getName();
            }
        }
        stopForeground(true); stopSelf(); changed();
    }
    void release() { if (mr != null) { try { mr.release(); } catch (Exception ignored) { } mr = null; } }
    static void changed() { Runnable r = onChange; if (r != null) new Handler(Looper.getMainLooper()).post(r); }

    // ------------------------------------------------------------ notification
    void startFg() {
        Notification n = notif();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NID, n);
    }
    void notifyFg() { NotificationManager nm = getSystemService(NotificationManager.class); if (nm != null && rec) nm.notify(NID, notif()); }
    PendingIntent act(String a, int rc) { return PendingIntent.getService(this, rc, new Intent(this, DictaService.class).setAction(a), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
    Notification notif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "PupDicta — enregistrement", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Le magnétophone tourne"); ch.setShowBadge(false); nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 4601, new Intent(this, DictaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_paw)
                .setContentTitle(paused ? "⏸️ PupDicta en pause" : "🎙️ PupDicta enregistre…")
                .setContentText(cur == null ? "" : cur.getName().replace(".m4a", ""))
                .setOngoing(true).setContentIntent(open).setCategory(Notification.CATEGORY_SERVICE)
                .setColor(0xFFFF3FA4);
        if (!paused) { b.setUsesChronometer(true); b.setWhen(System.currentTimeMillis() - elapsed()); }
        b.addAction(new Notification.Action.Builder(null, paused ? "▶ Reprendre" : "⏸ Pause", act(paused ? ACT_RESUME : ACT_PAUSE, 4602)).build());
        b.addAction(new Notification.Action.Builder(null, "🐾 Marque", act(ACT_MARK, 4603)).build());
        b.addAction(new Notification.Action.Builder(null, "■ Stop", act(ACT_STOP, 4604)).build());
        return b.build();
    }
    @Override public void onDestroy() { h.removeCallbacks(poll); if (rec) { try { mr.stop(); } catch (Exception ignored) { } release(); rec = false; } super.onDestroy(); }
}

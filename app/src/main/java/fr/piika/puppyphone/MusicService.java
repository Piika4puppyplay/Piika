package fr.piika.puppyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.Visualizer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Moteur de lecture PupMusic : file d'attente, enchaînement sans blanc / fondu, effets, MediaSession, notification. */
public class MusicService extends Service implements AudioManager.OnAudioFocusChangeListener {
    static MusicService I;
    static Runnable listener;
    static final int NID = 4545;
    static final String CH = "pupmusic";

    final Handler h = new Handler(Looper.getMainLooper());
    SharedPreferences sp;
    AudioManager am;
    AudioFocusRequest afr;
    int session;
    MediaPlayer cur, nxt, old;
    boolean chained;
    final List<MusicLib.Track> queue = new ArrayList<>();
    List<MusicLib.Track> orig;
    int idx = -1, qv = 0;
    boolean shuffle, wantPlay, focusLostTransient;
    int repeat; // 0 non, 1 tout, 2 un
    int cfMs = 0;
    boolean gapless = true;
    float speed = 1f, pitch = 1f, balance = 0f, duck = 1f;
    long fadeStart;
    final MusicFx fx = new MusicFx();
    JSONObject fxCfg = new JSONObject();
    Visualizer vis;
    byte[] fftBuf;
    MediaSession ms;
    long sleepAt = 0;
    boolean sleepEnd;
    float sleepFade = 1f;
    int failStreak = 0;
    boolean foreground, everPlayed;
    Bitmap artBm;
    long artFor = Long.MIN_VALUE;
    JSONObject stats;
    String info = "";

    // ------------------------------------------------------------------ cycle de vie
    @Override public void onCreate() {
        super.onCreate();
        I = this;
        sp = getSharedPreferences("pupmusic", MODE_PRIVATE);
        am = getSystemService(AudioManager.class);
        session = am.generateAudioSessionId();
        cfMs = sp.getInt("cf", 0);
        gapless = sp.getBoolean("gapless", true);
        speed = sp.getFloat("speed", 1f);
        pitch = sp.getFloat("pitch", 1f);
        balance = sp.getFloat("bal", 0f);
        repeat = sp.getInt("repeat", 0);
        try { fxCfg = new JSONObject(sp.getString("fx", "{}")); } catch (Exception e) { fxCfg = new JSONObject(); }
        try { stats = new JSONObject(sp.getString("stats", "{}")); } catch (Exception e) { stats = new JSONObject(); }
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "PupMusic — lecture", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        ms = new MediaSession(this, "PupMusic");
        ms.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long p) { seek((int) p); }
            @Override public void onStop() { pause(); }
        });
        ms.setSessionActivity(PendingIntent.getActivity(this, 0, new Intent(this, MusicActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        ms.setActive(true);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        restore();
        h.postDelayed(tick, 200);
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        String a = i == null ? null : i.getAction();
        if (a != null) switch (a) {
            case "toggle": toggle(); break;
            case "next": next(); break;
            case "prev": prev(); break;
            case "stop": pause(); stopForegroundCompat(true); break;
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onTaskRemoved(Intent root) {
        if (!isPlaying()) { saveState(); stopSelf(); }
        super.onTaskRemoved(root);
    }

    @Override public void onDestroy() {
        saveState();
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        relAll();
        visOff();
        fx.release();
        ms.release();
        abandonFocus();
        I = null;
        super.onDestroy();
    }

    final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (isPlaying() && sp.getBoolean("noisyPause", true)) pause(); }
    };

    // ------------------------------------------------------------------ lecteurs
    boolean isPlaying() { try { return cur != null && cur.isPlaying(); } catch (Exception e) { return false; } }
    MusicLib.Track curTrack() { return idx >= 0 && idx < queue.size() ? queue.get(idx) : null; }

    MediaPlayer make(MusicLib.Track t) throws Exception {
        MediaPlayer p = new MediaPlayer();
        try {
            p.setAudioSessionId(session);
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            p.setDataSource(this, Uri.parse(t.uri));
            p.prepare();
        } catch (Exception e) { try { p.release(); } catch (Exception ignored) { } throw e; }
        p.setOnCompletionListener(this::onDone);
        p.setOnErrorListener((mp, w, x) -> { onError(mp); return true; });
        fx.attach(session);
        return p;
    }

    void rel(MediaPlayer p) { if (p == null) return; try { p.setNextMediaPlayer(null); } catch (Exception ignored) { } try { p.release(); } catch (Exception ignored) { } }
    void relAll() { rel(cur); rel(nxt); rel(old); cur = nxt = old = null; chained = false; }

    void vol(MediaPlayer p, float f) {
        if (p == null) return;
        float v = f * duck * sleepFade;
        float l = v * (balance > 0 ? 1 - balance : 1), r = v * (balance < 0 ? 1 + balance : 1);
        try { p.setVolume(l, r); } catch (Exception ignored) { }
    }

    void params(MediaPlayer p) {
        if (p == null || (Math.abs(speed - 1) < .001 && Math.abs(pitch - 1) < .001 && !sp.getBoolean("paramsTouched", false))) return;
        try { p.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(pitch)); } catch (Exception ignored) { }
    }

    void startCur() {
        if (cur == null) return;
        if (!requestFocus()) return;
        try { cur.start(); } catch (Exception e) { onError(cur); return; }
        params(cur);
        vol(cur, old != null ? 0 : 1);
        wantPlay = true;
        everPlayed = true;
        bump();
    }

    /** Lance le titre n° i de la file. */
    void playAt(int i, boolean play) {
        if (queue.isEmpty()) { relAll(); idx = -1; bump(); return; }
        i = Math.max(0, Math.min(queue.size() - 1, i));
        relAll();
        idx = i;
        MusicLib.Track t = queue.get(i);
        try { cur = make(t); failStreak = 0; }
        catch (Exception e) {
            cur = null;
            if (++failStreak < Math.min(queue.size(), 8)) { h.post(() -> playAt((idx + 1) % queue.size(), play)); }
            else { failStreak = 0; wantPlay = false; bump(); }
            return;
        }
        cur.setLooping(repeat == 2);
        applyFx();
        if (play) { startCur(); countPlay(t); } else { wantPlay = false; }
        prepareNext();
        loadInfo();
        bump();
        saveState();
    }

    int nextIndex() {
        if (queue.isEmpty()) return -1;
        if (idx + 1 < queue.size()) return idx + 1;
        return repeat == 1 ? 0 : -1;
    }

    void prepareNext() {
        if (cur == null) return;
        try { cur.setNextMediaPlayer(null); } catch (Exception ignored) { }
        rel(nxt); nxt = null; chained = false;
        if (repeat == 2) return;
        int j = nextIndex();
        if (j < 0) return;
        try { nxt = make(queue.get(j)); } catch (Exception e) { nxt = null; return; }
        if (cfMs == 0 && gapless) {
            try { cur.setNextMediaPlayer(nxt); chained = true; } catch (Exception e) { chained = false; }
        }
    }

    void onDone(MediaPlayer mp) {
        if (mp == old) { rel(old); old = null; vol(cur, 1); return; }
        if (mp != cur) return;
        if (sleepEnd) { sleepEnd = false; sleepAt = 0; advance(false); pause(); return; }
        advance(true);
    }

    /** Passe au titre suivant préparé (enchaînement natif ou non). */
    void advance(boolean play) {
        int j = nextIndex();
        if (j < 0 || nxt == null) {
            // fin de file
            wantPlay = false;
            if (j >= 0) { playAt(j, play); return; }
            try { cur.seekTo(0); } catch (Exception ignored) { }
            bump(); saveState(); updateNotif();
            return;
        }
        MediaPlayer was = cur;
        boolean wasChained = chained;
        cur = nxt; nxt = null; chained = false; idx = j;
        rel(was);
        cur.setLooping(repeat == 2);
        if (play) {
            if (!wasChained) { try { cur.start(); } catch (Exception e) { onError(cur); return; } }
            params(cur); vol(cur, 1); wantPlay = true;
        }
        countPlay(curTrack());
        prepareNext();
        loadInfo();
        bump(); saveState();
    }

    void onError(MediaPlayer mp) {
        if (mp == old) { rel(old); old = null; return; }
        if (mp == nxt) { rel(nxt); nxt = null; chained = false; return; }
        if (mp == cur) {
            boolean p = wantPlay;
            if (++failStreak < Math.min(Math.max(queue.size(), 1), 8) && queue.size() > 1) h.post(() -> playAt((idx + 1) % queue.size(), p));
            else { relAll(); wantPlay = false; failStreak = 0; bump(); }
        }
    }

    // ------------------------------------------------------------------ commandes
    void play() {
        if (cur == null) { if (!queue.isEmpty()) playAt(Math.max(idx, 0), true); return; }
        startCur();
    }
    void pause() {
        wantPlay = false;
        try { if (cur != null && cur.isPlaying()) cur.pause(); } catch (Exception ignored) { }
        if (old != null) { rel(old); old = null; vol(cur, 1); }
        saveState();
        bump();
    }
    void toggle() { if (isPlaying()) pause(); else play(); }
    void next() {
        if (queue.isEmpty()) return;
        boolean p = isPlaying() || wantPlay;
        int j = idx + 1 >= queue.size() ? 0 : idx + 1;
        playAt(j, p);
    }
    void prev() {
        if (queue.isEmpty()) return;
        boolean p = isPlaying() || wantPlay;
        if (pos() > 3000) { seek(0); return; }
        playAt(idx - 1 < 0 ? queue.size() - 1 : idx - 1, p);
    }
    void seek(int ms) {
        try { if (cur != null) cur.seekTo(Math.max(0, ms)); } catch (Exception ignored) { }
        if (old != null) { rel(old); old = null; vol(cur, 1); }
        bump();
    }
    int pos() { try { return cur == null ? 0 : cur.getCurrentPosition(); } catch (Exception e) { return 0; } }
    int dur() { try { return cur == null ? 0 : cur.getDuration(); } catch (Exception e) { return 0; } }

    void setQueue(List<MusicLib.Track> list, int start, boolean shuf) {
        queue.clear(); queue.addAll(list); orig = null;
        if (queue.isEmpty()) { relAll(); idx = -1; qv++; bump(); return; }
        if (start < 0) start = shuf ? new Random().nextInt(queue.size()) : 0;
        start = Math.min(start, queue.size() - 1);
        shuffle = shuf || shuffle;
        if (shuffle) {
            orig = new ArrayList<>(queue);
            MusicLib.Track first = queue.remove(start);
            Collections.shuffle(queue);
            queue.add(0, first);
            start = 0;
        }
        qv++;
        playAt(start, true);
    }

    void setShuffle(boolean on) {
        if (on == shuffle) return;
        shuffle = on;
        MusicLib.Track t = curTrack();
        if (on) {
            orig = new ArrayList<>(queue);
            if (t != null) { queue.remove(idx); Collections.shuffle(queue); queue.add(0, t); idx = 0; }
            else Collections.shuffle(queue);
        } else if (orig != null) {
            queue.clear(); queue.addAll(orig); orig = null;
            idx = t == null ? 0 : Math.max(0, queue.indexOf(t));
        }
        qv++;
        prepareNext();
        bump(); saveState();
    }

    void setRepeat(int r) {
        repeat = Math.max(0, Math.min(2, r));
        sp.edit().putInt("repeat", repeat).apply();
        if (cur != null) cur.setLooping(repeat == 2);
        prepareNext();
        bump();
    }

    void enqueue(List<MusicLib.Track> list, boolean playNext) {
        if (list.isEmpty()) return;
        if (queue.isEmpty()) { setQueue(list, 0, false); return; }
        int at = playNext ? idx + 1 : queue.size();
        queue.addAll(at, list);
        if (orig != null) orig.addAll(list);
        qv++;
        if (at == idx + 1) prepareNext();
        bump(); saveState();
    }

    void removeAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        MusicLib.Track t = queue.remove(i);
        if (orig != null) orig.remove(t);
        qv++;
        if (i == idx) { if (queue.isEmpty()) { relAll(); idx = -1; wantPlay = false; bump(); } else playAt(Math.min(i, queue.size() - 1), isPlaying()); return; }
        if (i < idx) idx--;
        if (i == idx + 1) prepareNext();
        bump(); saveState();
    }

    void move(int a, int b) {
        if (a < 0 || b < 0 || a >= queue.size() || b >= queue.size() || a == b) return;
        MusicLib.Track t = queue.remove(a);
        queue.add(b, t);
        if (idx == a) idx = b;
        else if (a < idx && b >= idx) idx--;
        else if (a > idx && b <= idx) idx++;
        qv++;
        prepareNext();
        bump(); saveState();
    }

    void clearQueue() {
        MusicLib.Track t = curTrack();
        queue.clear(); orig = null;
        if (t != null) { queue.add(t); idx = 0; } else idx = -1;
        qv++;
        prepareNext();
        bump(); saveState();
    }

    void jump(int i) { playAt(i, true); }

    void setOpts(JSONObject o) {
        SharedPreferences.Editor e = sp.edit();
        if (o.has("cf")) { cfMs = Math.max(0, Math.min(12000, o.optInt("cf"))); e.putInt("cf", cfMs); }
        if (o.has("gapless")) { gapless = o.optBoolean("gapless"); e.putBoolean("gapless", gapless); }
        if (o.has("speed") || o.has("pitch")) {
            speed = (float) Math.max(.25, Math.min(4, o.optDouble("speed", speed)));
            pitch = (float) Math.max(.5, Math.min(2, o.optDouble("pitch", pitch)));
            e.putFloat("speed", speed).putFloat("pitch", pitch).putBoolean("paramsTouched", true);
            if (isPlaying()) params(cur);
        }
        if (o.has("bal")) { balance = (float) Math.max(-1, Math.min(1, o.optDouble("bal"))); e.putFloat("bal", balance); vol(cur, old != null ? 0 : 1); }
        if (o.has("noisyPause")) e.putBoolean("noisyPause", o.optBoolean("noisyPause"));
        if (o.has("resumeFocus")) e.putBoolean("resumeFocus", o.optBoolean("resumeFocus"));
        e.apply();
        if (o.has("cf") || o.has("gapless")) prepareNext();
        bump();
    }

    void setFx(JSONObject o) {
        fxCfg = o;
        sp.edit().putString("fx", o.toString()).apply();
        applyFx();
    }
    void applyFx() { fx.attach(session); fx.apply(fxCfg); }

    void sleep(int min) {
        sleepEnd = false; sleepFade = 1f;
        if (min == 0) sleepAt = 0;
        else if (min < 0) { sleepEnd = true; sleepAt = -1; }
        else sleepAt = System.currentTimeMillis() + min * 60000L;
        vol(cur, 1);
        bump();
    }

    // ------------------------------------------------------------------ boucle (fondu, minuterie, sauvegarde)
    long lastSave = 0;
    final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                boolean playing = isPlaying();
                // fondu enchaîné
                if (playing && cfMs > 0 && old == null && nxt != null && repeat != 2) {
                    int d = dur(), p = pos();
                    if (d > cfMs * 2 && d - p <= cfMs) {
                        old = cur; cur = nxt; nxt = null; chained = false;
                        idx = nextIndex() < 0 ? 0 : nextIndex();
                        fadeStart = SystemClock.uptimeMillis();
                        vol(cur, 0);
                        try { cur.start(); params(cur); } catch (Exception e) { onError(cur); }
                        countPlay(curTrack());
                        prepareNext(); loadInfo(); bump(); saveState();
                    }
                }
                if (old != null) {
                    float k = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) Math.max(1, cfMs));
                    float in = (float) Math.sin(k * Math.PI / 2), out = (float) Math.cos(k * Math.PI / 2);
                    vol(cur, in); vol(old, out);
                    if (k >= 1f) { rel(old); old = null; vol(cur, 1); }
                }
                // minuterie de sommeil
                if (sleepAt > 0 && playing) {
                    long left = sleepAt - System.currentTimeMillis();
                    if (left <= 0) { sleepAt = 0; sleepFade = 1f; pause(); vol(cur, 1); }
                    else if (left < 15000) { sleepFade = left / 15000f; vol(cur, 1); }
                }
                if (playing && SystemClock.uptimeMillis() - lastSave > 10000) { lastSave = SystemClock.uptimeMillis(); saveState(); }
            } catch (Exception ignored) { }
            h.postDelayed(this, old != null ? 50 : 250);
        }
    };

    // ------------------------------------------------------------------ focus audio
    boolean requestFocus() {
        if (afr == null) afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(this, h).setWillPauseWhenDucked(false).build();
        return am.requestAudioFocus(afr) != AudioManager.AUDIOFOCUS_REQUEST_FAILED;
    }
    void abandonFocus() { if (afr != null) am.abandonAudioFocusRequest(afr); }

    @Override public void onAudioFocusChange(int f) {
        switch (f) {
            case AudioManager.AUDIOFOCUS_LOSS: focusLostTransient = false; pause(); break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: if (isPlaying()) { focusLostTransient = true; pause(); } break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: duck = .3f; vol(cur, 1); break;
            case AudioManager.AUDIOFOCUS_GAIN:
                duck = 1f; vol(cur, 1);
                if (focusLostTransient && sp.getBoolean("resumeFocus", true)) play();
                focusLostTransient = false;
                break;
        }
    }

    // ------------------------------------------------------------------ visualiseur
    boolean visOn() {
        if (vis != null) return true;
        try {
            vis = new Visualizer(session);
            vis.setEnabled(false);
            int[] r = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(1024, r[1]));
            vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            vis.setEnabled(true);
            fftBuf = new byte[vis.getCaptureSize()];
            return true;
        } catch (Throwable e) { vis = null; return false; }
    }
    void visOff() { try { if (vis != null) { vis.setEnabled(false); vis.release(); } } catch (Throwable ignored) { } vis = null; }

    /** 48 bandes log, 0..255, séparées par des virgules. */
    String fft(int n) {
        if (vis == null || !isPlaying()) return "";
        try {
            if (vis.getFft(fftBuf) != Visualizer.SUCCESS) return "";
            int bins = fftBuf.length / 2;
            StringBuilder sb = new StringBuilder();
            double lo = Math.log(2), hi = Math.log(bins - 1);
            for (int b = 0; b < n; b++) {
                int a = (int) Math.exp(lo + (hi - lo) * b / n), z = Math.max(a + 1, (int) Math.exp(lo + (hi - lo) * (b + 1) / n));
                double m = 0;
                for (int k = a; k < z && k < bins; k++) {
                    double re = fftBuf[2 * k], im = fftBuf[2 * k + 1];
                    m = Math.max(m, Math.hypot(re, im));
                }
                int v = (int) Math.max(0, Math.min(255, (20 * Math.log10(m + 1e-3) + 4) * 6.5));
                if (b > 0) sb.append(',');
                sb.append(v);
            }
            return sb.toString();
        } catch (Throwable e) { return ""; }
    }

    // ------------------------------------------------------------------ infos format
    void loadInfo() {
        final MusicLib.Track t = curTrack();
        info = "";
        if (t == null) return;
        new Thread(() -> {
            JSONObject o = new JSONObject();
            try {
                android.media.MediaExtractor ex = new android.media.MediaExtractor();
                ex.setDataSource(this, Uri.parse(t.uri), null);
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    android.media.MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                    if (mime == null || !mime.startsWith("audio/")) continue;
                    o.put("codec", mime.substring(6).replace("mp4a-latm", "aac").replace("mpeg", "mp3").replace("vorbis", "ogg").replace("raw", "wav").toUpperCase());
                    if (f.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) o.put("rate", f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE));
                    if (f.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) o.put("ch", f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT));
                    if (f.containsKey("bits-per-sample")) o.put("bits", f.getInteger("bits-per-sample"));
                    if (f.containsKey(android.media.MediaFormat.KEY_BIT_RATE)) o.put("kbps", f.getInteger(android.media.MediaFormat.KEY_BIT_RATE) / 1000);
                    break;
                }
                ex.release();
            } catch (Exception ignored) { }
            if (!o.has("kbps")) {
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                try {
                    r.setDataSource(this, Uri.parse(t.uri));
                    String b = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE);
                    if (b != null) o.put("kbps", Integer.parseInt(b) / 1000);
                } catch (Exception ignored) { } finally { try { r.release(); } catch (Exception ignored) { } }
            }
            h.post(() -> { if (t == curTrack()) { info = o.toString(); bump(); } });
        }).start();
    }

    // ------------------------------------------------------------------ stats
    void countPlay(MusicLib.Track t) {
        if (t == null || t.id < 0) return;
        try {
            String k = String.valueOf(t.id);
            JSONArray a = stats.optJSONArray(k);
            int n = a == null ? 0 : a.optInt(0);
            stats.put(k, new JSONArray().put(n + 1).put(System.currentTimeMillis()));
            sp.edit().putString("stats", stats.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ état
    void saveState() {
        try {
            JSONArray q = new JSONArray();
            for (MusicLib.Track t : queue) q.put(t.key());
            JSONArray o = new JSONArray();
            if (orig != null) for (MusicLib.Track t : orig) o.put(t.key());
            sp.edit().putString("q", q.toString()).putString("orig", orig == null ? "" : o.toString()).putInt("qi", idx).putInt("qp", pos()).putBoolean("shuffle", shuffle).apply();
        } catch (Exception ignored) { }
    }

    List<MusicLib.Track> tracksOf(JSONArray a) {
        List<MusicLib.Track> l = new ArrayList<>();
        if (a == null) return l;
        for (int i = 0; i < a.length(); i++) {
            String k = a.optString(i);
            MusicLib.Track t = null;
            if (k.startsWith("u:")) t = MusicLib.external(this, Uri.parse(k.substring(2)));
            else try { t = MusicLib.get(this, Long.parseLong(k)); } catch (Exception ignored) { }
            if (t != null) l.add(t);
        }
        return l;
    }

    void restore() {
        final String q = sp.getString("q", "[]"), o = sp.getString("orig", "");
        final int qi = sp.getInt("qi", 0), qp = sp.getInt("qp", 0);
        final boolean sh = sp.getBoolean("shuffle", false);
        new Thread(() -> {
            List<MusicLib.Track> l, lo = null;
            try {
                l = tracksOf(new JSONArray(q));
                if (!o.isEmpty()) lo = tracksOf(new JSONArray(o));
            } catch (Exception e) { return; }
            final List<MusicLib.Track> fl = l, flo = lo;
            h.post(() -> {
                if (!queue.isEmpty() || fl.isEmpty()) return;
                queue.addAll(fl);
                orig = flo; shuffle = sh;
                qv++;
                playAt(Math.min(qi, queue.size() - 1), false);
                if (qp > 0) seek(qp);
            });
        }).start();
    }

    String stateJson() {
        try {
            JSONObject o = new JSONObject();
            MusicLib.Track t = curTrack();
            o.put("idx", idx).put("qv", qv).put("qn", queue.size()).put("playing", isPlaying()).put("pos", pos()).put("dur", dur())
                    .put("shuffle", shuffle).put("repeat", repeat).put("cf", cfMs).put("gapless", gapless).put("speed", speed).put("pitch", pitch).put("bal", balance)
                    .put("sleep", sleepAt == -1 ? -1 : sleepAt > 0 ? sleepAt - System.currentTimeMillis() : 0)
                    .put("fx", fx.backend).put("fxerr", fx.err).put("info", info.isEmpty() ? new JSONObject() : new JSONObject(info))
                    .put("noisyPause", sp.getBoolean("noisyPause", true)).put("resumeFocus", sp.getBoolean("resumeFocus", true));
            if (t != null) o.put("t", trackJson(t));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    static JSONObject trackJson(MusicLib.Track t) throws Exception {
        return new JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album).put("albumId", t.albumId).put("dur", t.dur).put("uri", t.uri).put("path", t.path);
    }

    String queueJson() {
        JSONArray a = new JSONArray();
        try { for (MusicLib.Track t : queue) a.put(trackJson(t)); } catch (Exception ignored) { }
        return a.toString();
    }

    // ------------------------------------------------------------------ notification / MediaSession
    void bump() {
        updateSession();
        updateNotif();
        Runnable l = listener;
        if (l != null) l.run();
    }

    void updateSession() {
        MusicLib.Track t = curTrack();
        boolean p = isPlaying() || (wantPlay && old != null);
        ms.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(t == null ? PlaybackState.STATE_NONE : p ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos(), p ? speed : 0f).build());
        if (t != null) {
            if (artFor != t.id) {
                artFor = t.id; artBm = null;
                final MusicLib.Track tt = t;
                new Thread(() -> { Bitmap b = MusicLib.artBitmap(this, tt.albumId, tt.uri, 512); h.post(() -> { if (curTrack() == tt) { artBm = b; updateSession(); updateNotif(); } }); }).start();
            }
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title).putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album).putLong(MediaMetadata.METADATA_KEY_DURATION, dur() > 0 ? dur() : t.dur);
            if (artBm != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artBm);
            ms.setMetadata(mb.build());
        }
    }

    PendingIntent pi(String a, int rc) { return PendingIntent.getService(this, rc, new Intent(this, MusicService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT); }

    void updateNotif() {
        MusicLib.Track t = curTrack();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (t == null) { stopForegroundCompat(true); return; }
        boolean p = isPlaying() || (wantPlay && old != null);
        if (!p && !everPlayed) return;
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_paw)
                .setContentTitle(t.title).setContentText(t.artist.isEmpty() ? "PupMusic" : t.artist).setSubText(t.album)
                .setContentIntent(PendingIntent.getActivity(this, 1, new Intent(this, MusicActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(pi("stop", 13))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(0xFFFF3FA4)
                .setOngoing(p)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Précédent", pi("prev", 10)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, p ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play), p ? "Pause" : "Lecture", pi("toggle", 11)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), "Suivant", pi("next", 12)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(ms.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (artBm != null) b.setLargeIcon(artBm);
        Notification n = b.build();
        if (p) {
            try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(NID, n);
                foreground = true;
            } catch (Exception e) { nm.notify(NID, n); }
        } else {
            if (foreground) { stopForegroundCompat(false); }
            try { nm.notify(NID, n); } catch (Exception ignored) { }
        }
    }

    void stopForegroundCompat(boolean remove) {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
            else stopForeground(remove);
        } catch (Exception ignored) { }
        foreground = false;
        if (remove) try { getSystemService(NotificationManager.class).cancel(NID); } catch (Exception ignored) { }
    }
}

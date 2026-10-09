package fr.piika.puppyphone;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;

/** PupDicta : magnétophone à cassette. Enregistrement dans DictaService, lecture ici. */
public class DictaActivity extends PupWebActivity {
    @Override String host() { return "pupdicta.local"; }
    @Override String page() { return "dicta.html"; }
    @Override String uiName() { return "DictaUI"; }
    @Override Object bridge() { return new Bridge(); }

    MediaPlayer mp; String playing = ""; float speed = 1f; String wantQ = null;

    @Override protected void onResume() { super.onResume(); DictaService.onChange = () -> emit("rec", ""); }
    @Override protected void onPause() { DictaService.onChange = null; super.onPause(); }
    @Override protected void onDestroy() { stopP(); super.onDestroy(); }

    void stopP() { if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; } playing = ""; }
    File file(String name) { File f = new File(DictaService.dir(this), new File(name).getName()); return f; }
    void svc(String act, String q) {
        Intent i = new Intent(this, DictaService.class).setAction(act); if (q != null) i.putExtra("q", q);
        if (DictaService.ACT_START.equals(act)) startForegroundService(i); else startService(i);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 90) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            if (ok && wantQ != null) svc(DictaService.ACT_START, wantQ);
            wantQ = null; emit("perm", ok ? "ok" : "no"); return;
        }
        super.onRequestPermissionsResult(code, perms, res);
    }

    long duration(File f) {
        SharedPreferences p = getSharedPreferences("pupdicta", MODE_PRIVATE);
        String k = f.getName() + "|" + f.length();
        long d = p.getLong(k, -1); if (d >= 0) return d;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            d = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception e) { d = 0; }
        try { r.release(); } catch (Exception ignored) { }
        p.edit().putLong(k, d).apply(); return d;
    }
    static String readAll(File f) { try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[(int) f.length()]; int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; } return new String(b, 0, n, "UTF-8"); } catch (Exception e) { return ""; } }

    class Bridge extends Common {
        @JavascriptInterface public String state() {
            JSONObject o = new JSONObject();
            try {
                o.put("rec", DictaService.rec).put("paused", DictaService.paused).put("ms", DictaService.elapsed()).put("level", DictaService.level)
                        .put("file", DictaService.cur == null ? "" : DictaService.cur.getName()).put("saved", DictaService.lastSaved).put("error", DictaService.error)
                        .put("perm", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
                JSONArray m = new JSONArray(); synchronized (DictaService.marks) { for (long x : DictaService.marks) m.put(x); } o.put("marks", m);
            } catch (Exception ignored) { }
            return o.toString();
        }
        @JavascriptInterface public void clearSaved() { DictaService.lastSaved = ""; DictaService.error = ""; }
        @JavascriptInterface public void start(String q) {
            ui.post(() -> {
                stopP();
                java.util.ArrayList<String> need = new java.util.ArrayList<>();
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
                if (need.contains(Manifest.permission.RECORD_AUDIO)) { wantQ = q; requestPermissions(need.toArray(new String[0]), 90); return; }
                if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 91);
                svc(DictaService.ACT_START, q);
            });
        }
        @JavascriptInterface public void pause() { ui.post(() -> svc(DictaService.ACT_PAUSE, null)); }
        @JavascriptInterface public void resume() { ui.post(() -> svc(DictaService.ACT_RESUME, null)); }
        @JavascriptInterface public void stop() { ui.post(() -> svc(DictaService.ACT_STOP, null)); }
        @JavascriptInterface public void mark() { ui.post(() -> svc(DictaService.ACT_MARK, null)); }

        @JavascriptInterface public String list() {
            JSONArray a = new JSONArray();
            File[] fs = DictaService.dir(DictaActivity.this).listFiles((d, n) -> n.endsWith(".m4a"));
            if (fs == null) return "[]";
            Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
            for (File f : fs) {
                if (DictaService.cur != null && f.equals(DictaService.cur)) continue;
                try {
                    JSONObject o = new JSONObject().put("name", f.getName()).put("size", f.length()).put("at", f.lastModified()).put("ms", duration(f));
                    File meta = new File(f.getParentFile(), f.getName() + ".json");
                    if (meta.exists()) { JSONObject m = new JSONObject(readAll(meta)); o.put("marks", m.optJSONArray("marks")); o.put("col", m.optString("col", "")); }
                    a.put(o);
                } catch (Exception ignored) { }
            }
            return a.toString();
        }
        @JavascriptInterface public String wave(String name) {
            File meta = new File(DictaService.dir(DictaActivity.this), new File(name).getName() + ".json");
            return meta.exists() ? readAll(meta) : "{}";
        }
        @JavascriptInterface public void setColor(String name, String col) {
            try {
                File meta = new File(DictaService.dir(DictaActivity.this), new File(name).getName() + ".json");
                JSONObject m = meta.exists() ? new JSONObject(readAll(meta)) : new JSONObject();
                m.put("col", col);
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(meta)) { o.write(m.toString().getBytes("UTF-8")); }
            } catch (Exception ignored) { }
        }
        @JavascriptInterface public String rename(String name, String to) {
            String clean = to == null ? "" : to.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
            if (clean.isEmpty()) return "";
            if (clean.length() > 60) clean = clean.substring(0, 60);
            File f = file(name), t = new File(f.getParentFile(), clean + ".m4a");
            if (t.exists() && !t.equals(f)) return "";
            if (name.equals(playing)) stopP();
            if (!f.renameTo(t)) return "";
            File m = new File(f.getParentFile(), f.getName() + ".json"); if (m.exists()) m.renameTo(new File(f.getParentFile(), t.getName() + ".json"));
            return t.getName();
        }
        @JavascriptInterface public boolean del(String name) {
            if (name.equals(playing)) ui.post(DictaActivity.this::stopP);
            File f = file(name); new File(f.getParentFile(), f.getName() + ".json").delete();
            return f.delete();
        }
        @JavascriptInterface public void shareRec(String name) {
            File f = file(name);
            ui.post(() -> PupSave.share(DictaActivity.this, PupFileProvider.uriFor(f), "audio/mp4", f.getName()));
        }
        /** Copie dans Musique/PupDicta (visible dans PupMusic et les autres applis). */
        @JavascriptInterface public String export(String name) {
            File f = file(name);
            try {
                Uri u = PupSave.save(DictaActivity.this, "audio", "PupDicta", f.getName(), "audio/mp4", o -> {
                    try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); }
                });
                return u == null ? "" : "ok";
            } catch (Exception e) { return ""; }
        }

        // ------------------------------------------------------------ lecteur
        @JavascriptInterface public void play(String name, int fromMs) {
            ui.post(() -> {
                try {
                    if (!name.equals(playing) || mp == null) {
                        stopP();
                        mp = new MediaPlayer();
                        mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                        mp.setDataSource(file(name).getAbsolutePath());
                        mp.prepare(); playing = name;
                        mp.setOnCompletionListener(m -> emit("pend", name));
                    }
                    if (fromMs >= 0) mp.seekTo(fromMs);
                    try { mp.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f)); } catch (Exception ignored) { }
                    mp.start();
                } catch (Exception e) { stopP(); emit("perr", String.valueOf(e.getMessage())); }
            });
        }
        @JavascriptInterface public void pauseP() { ui.post(() -> { if (mp != null && mp.isPlaying()) mp.pause(); }); }
        @JavascriptInterface public void stopPlay() { ui.post(DictaActivity.this::stopP); }
        @JavascriptInterface public void seek(int ms) { ui.post(() -> { if (mp != null) mp.seekTo(ms); }); }
        @JavascriptInterface public void setSpeed(float s) {
            speed = s;
            ui.post(() -> { if (mp != null && mp.isPlaying()) { try { mp.setPlaybackParams(new PlaybackParams().setSpeed(s).setPitch(1f)); } catch (Exception ignored) { } } });
        }
        @JavascriptInterface public String pstate() {
            JSONObject o = new JSONObject();
            try {
                MediaPlayer m = mp;
                o.put("name", playing).put("playing", m != null && m.isPlaying()).put("pos", m == null ? 0 : m.getCurrentPosition()).put("dur", m == null ? 0 : m.getDuration());
            } catch (Exception ignored) { }
            return o.toString();
        }
    }
}

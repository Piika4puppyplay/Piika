package fr.piika.puppyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** PupUpdate : vérifie les nouvelles versions de PuppyPhone sur GitHub et les installe (avec confirmation Android). */
public final class PupUpdate {
    private PupUpdate() { }
    static final String API = "https://api.github.com/repos/Piika4puppyplay/Piika/releases?per_page=15";
    static final String PAGE = "https://github.com/Piika4puppyplay/Piika/releases";
    static final String CH = "pupupdate";
    static final int NID = 4747;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("pupupdate", Context.MODE_PRIVATE); }

    static long current(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "PuppyPhone-PupUpdate");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception(code == 403 ? "GitHub demande de patienter un peu (limite atteinte)" : "GitHub a répondu " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    /** Interroge GitHub. Renvoie {current, latest, url, size, notes:[{v, date, body}], err}. */
    static JSONObject check(Context c) {
        JSONObject r = new JSONObject();
        try {
            long cur = current(c);
            r.put("current", cur);
            JSONArray rel = new JSONArray(get(API));
            long latest = 0; String url = ""; long size = 0;
            long prev = 0; String prevUrl = ""; long prevSize = 0;
            String updUrl = ""; long updSize = 0;
            JSONArray notes = new JSONArray();
            for (int i = 0; i < rel.length(); i++) {
                JSONObject o = rel.getJSONObject(i);
                String tag = o.optString("tag_name");
                if (!tag.startsWith("puppyphone-v") || o.optBoolean("draft") || o.optBoolean("prerelease")) continue;
                long v;
                try { v = Long.parseLong(tag.substring("puppyphone-v".length())); } catch (Exception e) { continue; }
                String apk = ""; long sz = 0;
                JSONArray as = o.optJSONArray("assets");
                if (as != null) for (int k = 0; k < as.length(); k++) {
                    JSONObject a = as.getJSONObject(k); String n = a.optString("name");
                    if (n.startsWith("PupUpdate-v") && n.endsWith(".apk")) { if (updUrl.isEmpty()) { updUrl = a.optString("browser_download_url"); updSize = a.optLong("size"); } continue; }
                    if (n.endsWith(".apk") && apk.isEmpty()) { apk = a.optString("browser_download_url"); sz = a.optLong("size"); }
                }
                if (apk.isEmpty()) continue;
                if (v > latest) { latest = v; url = apk; size = sz; }
                if (v < cur && v > prev) { prev = v; prevUrl = apk; prevSize = sz; }
                if (v > cur) notes.put(new JSONObject().put("v", v).put("date", o.optString("published_at")).put("body", o.optString("body")));
            }
            r.put("latest", latest).put("url", url).put("size", size).put("notes", notes).put("page", PAGE).put("updUrl", updUrl).put("updSize", updSize).put("prev", prev).put("prevUrl", prevUrl).put("prevSize", prevSize);
            sp(c).edit().putString("updUrl", updUrl).putLong("updSize", updSize).putLong("lastCheck", System.currentTimeMillis()).putLong("latest", latest).putString("url", url).putLong("size", size).putString("notes", notes.toString()).apply();
        } catch (Exception e) {
            try { r.put("err", e.getMessage() == null ? e.toString() : e.getMessage()); } catch (Exception ignored) { }
        }
        return r;
    }

    /** Vérification automatique (lanceur) : au plus toutes les 6 h ; notification si nouvelle version. */
    static void autoCheck(Context ctx) {
        if (standalone(ctx)) return; // l'appli PupUpdate séparée s'en occupe
        SharedPreferences p = sp(ctx);
        if (!p.getBoolean("auto", true)) return;
        if (System.currentTimeMillis() - p.getLong("lastCheck", 0) < 6L * 3600 * 1000) return;
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            JSONObject r = check(c);
            long latest = r.optLong("latest"), cur = r.optLong("current");
            if (latest > cur && latest != sp(c).getLong("notified", 0) && sp(c).getBoolean("notify", true)) {
                sp(c).edit().putLong("notified", latest).apply();
                notifyNew(c, latest, r.optJSONArray("notes"));
            }
        }).start();
    }

    static String firstLine(JSONArray notes) {
        if (notes == null || notes.length() == 0) return "";
        String b = notes.optJSONObject(0).optString("body");
        for (String l : b.split("\n")) { l = l.trim(); if (!l.isEmpty() && !l.startsWith("---") && !l.startsWith("Télécharge")) return l; }
        return "";
    }

    static void notifyNew(Context c, long v, JSONArray notes) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "PupUpdate — nouvelles versions", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(ch);
        String fl = firstLine(notes);
        Notification n = new Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_paw)
                .setContentTitle("🦴 Nouvelle version de PuppyPhone : v1.0." + v)
                .setContentText(fl.isEmpty() ? "Touche pour voir les nouveautés et installer" : fl)
                .setStyle(new Notification.BigTextStyle().bigText((fl.isEmpty() ? "" : fl + "\n") + "Touche pour voir les nouveautés et installer 🐾"))
                .setColor(0xFF3DFFB0)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 0, new Intent(c, UpdateActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        try { nm.notify(NID, n); } catch (Exception ignored) { }
    }

    interface Progress { void on(long done, long total); }

    /** Télécharge l'APK dans le cache de l'appli. */
    static File download(Context c, String url, long expected, Progress pr, java.util.concurrent.atomic.AtomicBoolean cancel) throws Exception {
        return download(c, url, expected, "PuppyPhone-update.apk", pr, cancel);
    }
    static File download(Context c, String url, long expected, String name, Progress pr, java.util.concurrent.atomic.AtomicBoolean cancel) throws Exception {
        File out = new File(c.getCacheDir(), name);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000); con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "PuppyPhone-PupUpdate");
        int code = con.getResponseCode();
        if (code / 100 == 3) { String loc = con.getHeaderField("Location"); con.disconnect(); con = (HttpURLConnection) new URL(loc).openConnection(); con.setRequestProperty("User-Agent", "PuppyPhone-PupUpdate"); code = con.getResponseCode(); }
        if (code != 200) throw new Exception("Téléchargement refusé (" + code + ")");
        long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : expected;
        try (InputStream in = con.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[65536]; int n; long done = 0;
            while ((n = in.read(b)) > 0) {
                if (cancel.get()) throw new Exception("Annulé");
                o.write(b, 0, n); done += n;
                pr.on(done, total);
            }
        } finally { con.disconnect(); }
        if (expected > 0 && out.length() != expected) throw new Exception("Fichier incomplet, réessaie");
        return out;
    }

    /** Installe via PackageInstaller : Android demande la confirmation (et vérifie la signature). */
    static void install(Context c, File apk) throws Exception { install(c, apk, c.getPackageName()); }
    static void install(Context c, File apk, String pkg) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        params.setSize(apk.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("PuppyPhone.apk", 0, apk.length())) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                s.fsync(o);
            }
            Intent i = new Intent(c, UpdateReceiver.class).setAction("fr.piika.puppyphone.UPDATE_STATUS").putExtra("pkg", pkg);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, i, flags).getIntentSender());
        }
    }

    // ------------------------------------------------------------ appli PupUpdate séparée
    static final String STANDALONE = "fr.piika.pupupdate";
    static boolean standalone(Context c) {
        try { c.getPackageManager().getPackageInfo(STANDALONE, 0); return true; } catch (Exception e) { return false; }
    }
    /** Ouvre l'appli PupUpdate séparée, habillée aux couleurs du pelage. */
    static boolean openStandalone(Context c) {
        String[] pel = Pelage.cur(c);
        Intent i = new Intent("fr.piika.pupupdate.OUVRIR").setPackage(STANDALONE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("acc", pel[2]).putExtra("acc2", pel[3]);
        try { c.startActivity(i); return true; } catch (Exception e) { return false; }
    }
    /** Cache l'ancienne icône PupUpdate intégrée quand l'appli séparée est là (et la remet sinon). */
    static void syncLauncherIcon(Context c) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            android.content.ComponentName cn = new android.content.ComponentName(c, UpdateActivity.class);
            int want = standalone(c) ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            if (pm.getComponentEnabledSetting(cn) != want) pm.setComponentEnabledSetting(cn, want, android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) { }
    }
}

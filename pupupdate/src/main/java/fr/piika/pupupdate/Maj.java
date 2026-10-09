package fr.piika.pupupdate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PupUpdate (appli séparée) : vérifie GitHub, télécharge et installe PuppyPhone sans fermer l'appli qui installe,
 * puis relance l'accueil PuppyPhone. Se met aussi à jour elle-même.
 */
final class Maj {
    private Maj() { }
    static final String PUPPY = "fr.piika.puppyphone";
    static final String API = "https://api.github.com/repos/Piika4puppyplay/Piika/releases?per_page=15";
    static final String PAGE = "https://github.com/Piika4puppyplay/Piika/releases";
    static final String CH = "pupupdate";
    static final int NID = 4748, JOB = 4749, NID_SELF = 4750;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("maj", Context.MODE_PRIVATE); }

    static long version(Context c, String pkg) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }
    /** Qui a installé PuppyPhone ? (si c'est PupUpdate, Android 12+ permet les mises à jour sans confirmation) */
    static String installer(Context c, String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return c.getPackageManager().getInstallSourceInfo(pkg).getInstallingPackageName();
            return c.getPackageManager().getInstallerPackageName(pkg);
        } catch (Exception e) { return null; }
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "PupUpdate");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception(code == 403 ? "GitHub demande de patienter un peu (limite atteinte)" : "GitHub a répondu " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    /**
     * {pp:{cur, latest, url, size, installed, silent}, self:{cur, latest, url, size}, notes:[...], err}
     */
    static JSONObject check(Context c) {
        JSONObject r = new JSONObject();
        try {
            long ppCur = version(c, PUPPY), selfCur = version(c, c.getPackageName());
            JSONArray rel = new JSONArray(get(API));
            long latest = 0, selfLatest = 0; String url = "", selfUrl = ""; long size = 0, selfSize = 0;
            long prev = 0; String prevUrl = ""; long prevSize = 0; // la version la plus haute SOUS la version installée
            JSONArray notes = new JSONArray();
            JSONArray versions = new JSONArray(); // toutes les versions dispo, pour le dépannage (réinstaller / rétrograder)
            for (int i = 0; i < rel.length(); i++) {
                JSONObject o = rel.getJSONObject(i);
                String tag = o.optString("tag_name");
                if (!tag.startsWith("puppyphone-v") || o.optBoolean("draft") || o.optBoolean("prerelease")) continue;
                long v;
                try { v = Long.parseLong(tag.substring("puppyphone-v".length())); } catch (Exception e) { continue; }
                JSONArray as = o.optJSONArray("assets");
                String apk = ""; long sz = 0;
                if (as != null) for (int k = 0; k < as.length(); k++) {
                    JSONObject a = as.getJSONObject(k); String n = a.optString("name");
                    if (n.equals("PuppyPhone.apk")) { apk = a.optString("browser_download_url"); sz = a.optLong("size"); }
                    if (n.startsWith("PupUpdate-v") && n.endsWith(".apk")) {
                        try {
                            long sv = Long.parseLong(n.substring("PupUpdate-v".length(), n.length() - 4));
                            if (sv > selfLatest) { selfLatest = sv; selfUrl = a.optString("browser_download_url"); selfSize = a.optLong("size"); }
                        } catch (Exception ignored) { }
                    }
                }
                if (apk.isEmpty()) continue;
                versions.put(new JSONObject().put("v", v).put("url", apk).put("size", sz).put("date", o.optString("published_at")));
                if (v > latest) { latest = v; url = apk; size = sz; }
                if (ppCur > 0 && v < ppCur && v > prev) { prev = v; prevUrl = apk; prevSize = sz; } // candidat rétrograde
                if (v > ppCur) notes.put(new JSONObject().put("v", v).put("date", o.optString("published_at")).put("body", o.optString("body")));
            }
            boolean silent = Build.VERSION.SDK_INT >= 31 && c.getPackageName().equals(installer(c, PUPPY));
            r.put("pp", new JSONObject().put("cur", ppCur).put("latest", latest).put("url", url).put("size", size).put("installed", ppCur > 0).put("silent", silent)
                    .put("prev", prev).put("prevUrl", prevUrl).put("prevSize", prevSize));
            r.put("self", new JSONObject().put("cur", selfCur).put("latest", selfLatest).put("url", selfUrl).put("size", selfSize));
            r.put("notes", notes);
            r.put("versions", versions);
            sp(c).edit().putLong("lastCheck", System.currentTimeMillis()).putString("last", r.toString()).apply();
        } catch (Exception e) {
            try {
                r.put("err", e.getMessage() == null ? e.toString() : e.getMessage());
                r.put("pp", new JSONObject().put("cur", version(c, PUPPY)).put("installed", version(c, PUPPY) > 0));
                r.put("self", new JSONObject().put("cur", version(c, c.getPackageName())));
            } catch (Exception ignored) { }
        }
        return r;
    }

    interface Progress { void on(long done, long total); }

    static File download(Context c, String url, long expected, String name, Progress pr, AtomicBoolean cancel) throws Exception {
        File out = new File(c.getCacheDir(), name);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000); con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "PupUpdate");
        int code = con.getResponseCode();
        int hops = 0;
        while (code / 100 == 3 && hops++ < 4) { String loc = con.getHeaderField("Location"); con.disconnect(); con = (HttpURLConnection) new URL(loc).openConnection(); con.setRequestProperty("User-Agent", "PupUpdate"); code = con.getResponseCode(); }
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

    /** Installe via PackageInstaller. Android 12+ : sans confirmation si PupUpdate est déjà l'installateur de l'appli. */
    static void install(Context c, File apk, String pkg) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("base.apk", 0, apk.length())) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                s.fsync(o);
            }
            Intent i = new Intent(c, MajReceiver.class).setAction("fr.piika.pupupdate.STATUS").putExtra("pkg", pkg);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, i, flags).getIntentSender());
        }
    }

    /** Demande à Android de désinstaller PuppyPhone (nécessaire pour rétrograder : Android refuse une version plus basse par-dessus une plus haute). */
    static void uninstall(Context c, String pkg) {
        try { c.startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }

    /** Ramène sur l'accueil PuppyPhone. */
    static boolean openPuppy(Context c) {
        if (version(c, PUPPY) == 0) return false; // pas installé : inutile d'essayer
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(PUPPY, PUPPY + ".MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { c.startActivity(i); return true; } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------ atterrissage sans plante
    /** Gestionnaires de root connus (on ne fait que les OUVRIR, jamais invoquer su). */
    static final String[] ROOT_MGRS = {
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk",
            "me.weishu.kernelsu", "com.rifsxd.ksunext", "me.bmax.apatch",
            "eu.chainfire.supersu", "com.koushikdutta.superuser", "me.phh.superuser",
            "com.kingroot.kinguser", "com.kingouser.com"
    };

    /**
     * Après une mise à jour, PupUpdate doit atterrir quelque part — jamais sur un écran mort.
     * Cascade : PuppyPhone → Réglages du téléphone → écran de verrouillage → gestionnaire root → (rien, mais pas de plante).
     * Pensée surtout pour un téléphone rooté sans lanceur, en pleine config de PuppyPhone.
     */
    static boolean landSafely(Context c) {
        if (openPuppy(c)) return true;                                        // 1. PuppyPhone si installé
        if (openAnyLauncher(c)) return true;                                  // 2. Lanceur alternatif ou d'origine
        if (tryStart(c, new Intent(android.provider.Settings.ACTION_SETTINGS))) return true; // 3. Réglages
        if (lockScreen(c)) return true;                                       // 4. Écran de verrouillage
        if (openRootManager(c)) return true;                                  // 5. Gestionnaire root
        return false;                                                         // 6. Dernier recours : on ne plante pas
    }

    /** Ouvre n'importe quel lanceur présent (alternatif ou d'origine), hors PuppyPhone et hors résolveur système. */
    static boolean openAnyLauncher(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (android.content.pm.ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (PUPPY.equals(pkg) || "android".equals(pkg)) continue; // on a déjà tenté PuppyPhone ; "android" = stub/résolveur
            try {
                c.startActivity(new Intent(home).setClassName(pkg, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    /** Combien de lanceurs « réels » (hors PuppyPhone et hors stub système) sont installés. */
    static int otherLaunchers(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        int n = 0;
        for (android.content.pm.ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (PUPPY.equals(pkg) || "android".equals(pkg)) continue;
            n++;
        }
        return n;
    }

    /** Y a-t-il un écran d'accueil utilisable ? (PuppyPhone lanceur, ou un autre lanceur) */
    static boolean hasLauncher(Context c) { return version(c, PUPPY) > 0 || otherLaunchers(c) > 0; }

    static boolean tryStart(Context c, Intent i) {
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; } catch (Exception e) { return false; }
    }

    /** Montre l'écran de verrouillage. Sans privilège Android ne le permet pas → on tente proprement via root, sans rien modifier. */
    static boolean lockScreen(Context c) {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null) { dpm.lockNow(); return true; } // uniquement si PupUpdate est admin ; sinon SecurityException, on passe
        } catch (Exception ignored) { }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "input keyevent 26"}); // POWER : éteint l'écran → le verrouillage apparaît au réveil
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception ignored) { }
        return false;
    }

    /** Ouvre le gestionnaire de root s'il y en a un (Magisk, KernelSU, APatch, SuperSU…), sans invoquer su. */
    static boolean openRootManager(Context c) {
        for (String pkg : ROOT_MGRS) {
            try {
                Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i); return true; }
            } catch (Exception ignored) { }
        }
        return false;
    }

    // ------------------------------------------------------------ vérification automatique
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!sp(c).getBoolean("auto", true)) { js.cancel(JOB); return; }
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB) return;
        JobInfo ji = new JobInfo.Builder(JOB, new ComponentName(c, MajJob.class))
                .setPeriodic(6L * 3600 * 1000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).build();
        try { js.schedule(ji); } catch (Exception ignored) { }
    }

    static void notifyNew(Context c, long v, String firstLine) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Nouvelles versions de PuppyPhone", NotificationManager.IMPORTANCE_DEFAULT));
        Notification n = new Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_paw)
                .setContentTitle("🦴 Nouvelle version de PuppyPhone : v1.0." + v)
                .setContentText(firstLine.isEmpty() ? "Touche pour voir les nouveautés et installer" : firstLine)
                .setStyle(new Notification.BigTextStyle().bigText((firstLine.isEmpty() ? "" : firstLine + "\n") + "Touche pour l'installer, PupUpdate te ramène ensuite sur l'accueil 🐾"))
                .setColor(0xFF3DFFB0).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 0, new Intent(c, MajActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        try { nm.notify(NID, n); } catch (Exception ignored) { }
    }

    /** PupUpdate a une nouvelle version de lui-même : notif distincte (ouvre PupUpdate, qui se met à jour seul). */
    static void notifySelf(Context c, long v) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Nouvelles versions de PuppyPhone", NotificationManager.IMPORTANCE_DEFAULT));
        Notification n = new Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_paw)
                .setContentTitle("🛠️ PupUpdate a une mise à jour : v1." + v)
                .setContentText("Le chien de garde se refait une beauté. Touche pour le mettre à jour.")
                .setStyle(new Notification.BigTextStyle().bigText("Touche pour ouvrir PupUpdate et le mettre à jour tout seul. Il se ferme un instant le temps de s'installer, puis rouvre-le 🐾"))
                .setColor(0xFF3DFFB0).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 2, new Intent(c, MajActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        try { nm.notify(NID_SELF, n); } catch (Exception ignored) { }
    }

    static void notifyDone(Context c, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Nouvelles versions de PuppyPhone", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(PUPPY, PUPPY + ".MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification n = new Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_paw)
                .setContentTitle(title).setContentText(text).setColor(0xFF3DFFB0).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        try { nm.notify(NID, n); } catch (Exception ignored) { }
    }

    static String firstLine(JSONArray notes) {
        if (notes == null || notes.length() == 0) return "";
        String b = notes.optJSONObject(0).optString("body");
        for (String l : b.split("\n")) { l = l.trim(); if (!l.isEmpty() && !l.startsWith("---") && !l.startsWith("Télécharge")) return l; }
        return "";
    }
}

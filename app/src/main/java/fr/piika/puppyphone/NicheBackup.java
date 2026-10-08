package fr.piika.puppyphone;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * « Enterrer un os » : sauvegarde de toute la niche dans un fichier .puppy (zip).
 * Contenu : réglages natifs (shared_prefs), petits fichiers de l'appli, données des pages (Local Storage des WebView).
 * Pas de cookies ni de connexions aux sites (vie privée). Restauration au prochain démarrage (PupApp).
 */
final class NicheBackup {
    private NicheBackup() { }
    static final String MAGIC = "puppyniche";

    static File dataDir(Context c) { return new File(c.getApplicationInfo().dataDir); }
    static File pending(Context c) { return new File(c.getFilesDir(), "restore_pending.puppy"); }
    /** Os choisi mais pas encore confirmé : jamais appliqué tout seul. */
    static File staged(Context c) { return new File(c.getFilesDir(), "restore_staged.puppy"); }
    static boolean commit(Context c) { File p = pending(c); p.delete(); return staged(c).renameTo(p); }

    interface Progress { void on(String what); }

    /** Crée la sauvegarde dans Téléchargements/PupNiche. Renvoie le nom du fichier. */
    static String backup(Context c, Progress pr) throws Exception {
        String name = "niche_" + new SimpleDateFormat("yyyy-MM-dd_HH'h'mm", Locale.FRANCE).format(new Date()) + ".puppy";
        File tmp = new File(c.getCacheDir(), name);
        File root = dataDir(c);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(tmp))) {
            JSONObject man = new JSONObject().put("magic", MAGIC).put("package", c.getPackageName()).put("version", PupUpdate.current(c))
                    .put("date", System.currentTimeMillis()).put("device", Build.MANUFACTURER + " " + Build.MODEL);
            z.putNextEntry(new ZipEntry("niche.json")); z.write(man.toString(2).getBytes("UTF-8")); z.closeEntry();
            pr.on("Réglages des Pup-apps…");
            add(z, root, new File(root, "shared_prefs"), null);
            pr.on("Fichiers de la niche…");
            add(z, root, c.getFilesDir(), (f) -> f.length() < 8 * 1024 * 1024 && !f.getName().startsWith("restore_"));
            pr.on("Mémoire des pages (playlists, favoris…)…");
            add(z, root, new File(root, "app_webview/Default/Local Storage"), null);
        }
        pr.on("Rangement dans Téléchargements…");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PupNiche");
            Uri dst = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            try (InputStream in = new FileInputStream(tmp); OutputStream out = c.getContentResolver().openOutputStream(dst)) { copy(in, out); }
        } else {
            File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PupNiche");
            d.mkdirs();
            try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(new File(d, name))) { copy(in, out); }
        }
        tmp.delete();
        c.getSharedPreferences("pupniche", Context.MODE_MULTI_PROCESS).edit().putLong("lastBackup", System.currentTimeMillis()).putString("lastBackupName", name).commit();
        return name;
    }

    interface Filter { boolean ok(File f); }
    static void add(ZipOutputStream z, File root, File f, Filter flt) throws Exception {
        if (!f.exists()) return;
        if (f.isDirectory()) { File[] fs = f.listFiles(); if (fs != null) for (File x : fs) add(z, root, x, flt); return; }
        if (flt != null && !flt.ok(f)) return;
        if (f.getName().equals("LOCK")) return;
        String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
        z.putNextEntry(new ZipEntry("data/" + rel));
        try (InputStream in = new FileInputStream(f)) { copy(in, z); }
        z.closeEntry();
    }
    static void copy(InputStream in, OutputStream out) throws Exception { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }

    /** Vérifie un fichier choisi et le met de côté pour le prochain démarrage. Renvoie la description de la sauvegarde. */
    static JSONObject prepare(Context c, Uri src) throws Exception {
        File p = staged(c);
        try (InputStream in = c.getContentResolver().openInputStream(src); OutputStream out = new FileOutputStream(p)) { copy(in, out); }
        JSONObject man = null;
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(p))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals("niche.json")) {
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); copy(z, o);
                    man = new JSONObject(o.toString("UTF-8"));
                    break;
                }
            }
        }
        if (man == null || !MAGIC.equals(man.optString("magic")) || !c.getPackageName().equals(man.optString("package"))) { p.delete(); throw new Exception("Ce fichier n'est pas un os de la niche PuppyPhone"); }
        return man;
    }

    /** Appelé par PupApp au démarrage, avant toute page : remet les fichiers en place. */
    static void applyPending(Context c) {
        File p = pending(c);
        if (!p.exists()) return;
        File root = dataDir(c);
        String canon;
        try { canon = root.getCanonicalPath() + File.separator; } catch (Exception e) { p.delete(); return; }
        try {
            // on vide l'ancienne mémoire des pages pour éviter les mélanges
            deleteTree(new File(root, "app_webview/Default/Local Storage"));
            try (ZipInputStream z = new ZipInputStream(new FileInputStream(p))) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory() || !e.getName().startsWith("data/")) continue;
                    String rel = e.getName().substring(5);
                    if (!(rel.startsWith("shared_prefs/") || rel.startsWith("files/") || rel.startsWith("app_webview/Default/Local Storage/"))) continue;
                    File out = new File(root, rel);
                    if (!out.getCanonicalPath().startsWith(canon)) continue; // sécurité
                    out.getParentFile().mkdirs();
                    try (OutputStream o = new FileOutputStream(out)) { copy(z, o); }
                }
            }
            c.getSharedPreferences("pupniche", Context.MODE_MULTI_PROCESS).edit().putLong("restoredAt", System.currentTimeMillis()).commit();
        } catch (Exception ignored) {
        } finally { p.delete(); }
    }
    static void deleteTree(File f) { if (f.isDirectory()) { File[] fs = f.listFiles(); if (fs != null) for (File x : fs) deleteTree(x); } f.delete(); }
}

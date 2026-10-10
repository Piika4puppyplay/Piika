package fr.piika.puppyphone;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PupZip Éco++ — re-compresseur « gagne un max de place ».
 * Les screenshots/images PNG sont réencodés en WebP (5 à 10× plus petit) sans toucher à la résolution.
 * Les ORIGINAUX sont toujours gardés ; on ne les supprime qu'à la demande explicite (confirmation système).
 */
public class PupZipActivity extends PupWebActivity {
    @Override String host() { return "pupzip.local"; }
    @Override String page() { return "pupzip/index.html"; }
    @Override String uiName() { return "ZipUI"; }

    static final int REQ_DELETE = 91;
    static final int REQ_READ = 92;
    final AtomicBoolean cancel = new AtomicBoolean(false);
    final List<Uri> doneOriginals = new ArrayList<>();   // originaux compressés avec succès (candidats à la suppression)

    Object bridge() { return new Zip(); }

    /** Permission de LECTURE des images (nécessaire pour scanner la galerie). L'écriture/suppression passe par MediaStore. */
    String readPerm() { return Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.READ_MEDIA_IMAGES : android.Manifest.permission.READ_EXTERNAL_STORAGE; }
    boolean canRead() { return checkSelfPermission(readPerm()) == android.content.pm.PackageManager.PERMISSION_GRANTED; }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_READ) emit("access", canRead() ? "1" : "0");
    }

    Uri imagesUri() { return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); }

    /** Sélection MediaStore selon la catégorie choisie. */
    String[] where(String cat) {
        if ("shots".equals(cat)) return new String[]{MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?", "%Screenshots%"};
        if ("dl".equals(cat)) return new String[]{MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?", "%Download%"};
        // "all" : les PNG (gros gain) + grosses images
        return new String[]{MediaStore.Images.Media.MIME_TYPE + "=? OR " + MediaStore.Images.Media.SIZE + ">?", "image/png"};
    }

    class Item { long id; String name; long size; String mime; }

    List<Item> list(String cat) {
        List<Item> out = new ArrayList<>();
        String[] w = where(cat);
        String sel; String[] args;
        if ("all".equals(cat)) { sel = w[0]; args = new String[]{w[1], "800000"}; }
        else { sel = w[0]; args = new String[]{w[1]}; }
        String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.MIME_TYPE};
        try (Cursor c = getContentResolver().query(imagesUri(), proj, sel, args, MediaStore.Images.Media.SIZE + " DESC")) {
            while (c != null && c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(0); it.name = c.getString(1); it.size = c.getLong(2); it.mime = c.getString(3);
                if ("image/webp".equals(it.mime)) continue;  // déjà compressé, on saute
                out.add(it);
            }
        } catch (Exception ignored) { }
        return out;
    }

    class Zip extends Common {
        @JavascriptInterface public boolean hasAccess() { return canRead(); }
        @JavascriptInterface public void requestAccess() {
            ui.post(() -> { if (canRead()) emit("access", "1"); else requestPermissions(new String[]{readPerm()}, REQ_READ); });
        }

        @JavascriptInterface public void scan(String cat) {
            new Thread(() -> {
                List<Item> l = list(cat);
                long total = 0; for (Item it : l) total += it.size;
                emit("scanned", "{\"count\":" + l.size() + ",\"total\":" + total + "}");
            }).start();
        }

        /** quality : 85 (léger) / 70 (fort) / 50 (extrême). */
        @JavascriptInterface public void compress(String cat, int quality) {
            cancel.set(false);
            new Thread(() -> {
                doneOriginals.clear();
                List<Item> l = list(cat);
                int done = 0, ok = 0, fail = 0; long origTot = 0, newTot = 0;
                for (Item it : l) {
                    if (cancel.get()) break;
                    done++;
                    try {
                        long ns = recompress(it, quality);
                        if (ns > 0) { ok++; origTot += it.size; newTot += ns; doneOriginals.add(ContentUris.withAppendedId(imagesUri(), it.id)); }
                        else fail++;
                    } catch (Exception e) { fail++; }
                    emit("progress", "{\"done\":" + done + ",\"total\":" + l.size() + ",\"ok\":" + ok + ",\"orig\":" + origTot + ",\"new\":" + newTot + "}");
                }
                emit("finished", "{\"ok\":" + ok + ",\"fail\":" + fail + ",\"orig\":" + origTot + ",\"new\":" + newTot + ",\"candidates\":" + doneOriginals.size() + "}");
            }).start();
        }

        @JavascriptInterface public void cancelJob() { cancel.set(true); }

        /** Supprime les originaux compressés (confirmation système sur Android 11+). */
        @JavascriptInterface public void deleteOriginals() {
            ui.post(() -> {
                if (doneOriginals.isEmpty()) { emit("deleted", "0"); return; }
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), new ArrayList<>(doneOriginals));
                        startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                    } else {
                        int n = 0; for (Uri u : doneOriginals) { try { n += getContentResolver().delete(u, null, null); } catch (Exception ignored) { } }
                        doneOriginals.clear(); emit("deleted", String.valueOf(n));
                    }
                } catch (Exception e) { emit("err", String.valueOf(e.getMessage())); }
            });
        }
    }

    /** Réencode une image en WebP (même résolution), garde l'original. Renvoie la taille du nouveau fichier, ou -1. */
    long recompress(Item it, int quality) {
        Uri src = ContentUris.withAppendedId(imagesUri(), it.id);
        Bitmap bmp = null;
        try (InputStream in = getContentResolver().openInputStream(src)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) { return -1; }
        if (bmp == null) return -1;
        bmp = applyExif(src, bmp);
        String base = it.name.replaceAll("\\.[^.]+$", "");
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, base + "_eco.webp");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/webp");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/PupZip");
        if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri out = getContentResolver().insert(imagesUri(), v);
        if (out == null) { bmp.recycle(); return -1; }
        try (OutputStream os = getContentResolver().openOutputStream(out)) {
            Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
            bmp.compress(fmt, Math.max(10, Math.min(95, quality)), os);
        } catch (Exception e) { bmp.recycle(); try { getContentResolver().delete(out, null, null); } catch (Exception ignored) { } return -1; }
        bmp.recycle();
        if (Build.VERSION.SDK_INT >= 29) { ContentValues f = new ContentValues(); f.put(MediaStore.Images.Media.IS_PENDING, 0); try { getContentResolver().update(out, f, null, null); } catch (Exception ignored) { } }
        long ns = 0;
        try (Cursor c = getContentResolver().query(out, new String[]{MediaStore.Images.Media.SIZE}, null, null, null)) { if (c != null && c.moveToFirst()) ns = c.getLong(0); } catch (Exception ignored) { }
        return ns > 0 ? ns : -1;
    }

    Bitmap applyExif(Uri src, Bitmap bmp) {
        try (InputStream in = getContentResolver().openInputStream(src)) {
            android.media.ExifInterface ex = new android.media.ExifInterface(in);
            int o = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            int deg = o == 6 ? 90 : o == 3 ? 180 : o == 8 ? 270 : 0;
            if (deg != 0) { Matrix m = new Matrix(); m.postRotate(deg); Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true); if (r != bmp) bmp.recycle(); return r; }
        } catch (Throwable ignored) { }
        return bmp;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_DELETE) {
            int n = res == Activity.RESULT_OK ? doneOriginals.size() : 0;
            doneOriginals.clear();
            emit(res == Activity.RESULT_OK ? "deleted" : "delcancel", String.valueOf(n));
        }
    }
}

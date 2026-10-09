package fr.piika.puppyphone;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Rangement des créations dans les dossiers publics (Documents, Pictures, Music) + ouverture / partage. */
final class PupSave {
    interface Writer { void write(OutputStream o) throws Exception; }

    /** kind : "doc" (Documents), "img" (Pictures), "audio" (Music). sub : sous-dossier, ex. "PupScan". */
    static Uri save(Context c, String kind, String sub, String name, String mime, Writer w) throws Exception {
        String dir = "img".equals(kind) ? Environment.DIRECTORY_PICTURES : "video".equals(kind) ? Environment.DIRECTORY_MOVIES : "audio".equals(kind) ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_DOCUMENTS;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            Uri col = "img".equals(kind) ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : "video".equals(kind) ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : "audio".equals(kind) ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/" + sub);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = cr.insert(col, v);
            if (u == null) throw new Exception("MediaStore refuse l'enregistrement");
            try (OutputStream o = cr.openOutputStream(u)) { w.write(o); }
            catch (Exception e) { try { cr.delete(u, null, null); } catch (Exception ignored) { } throw e; }
            v.clear(); v.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(u, v, null, null);
            return u;
        }
        File d = new File(Environment.getExternalStoragePublicDirectory(dir), sub); d.mkdirs();
        File f = new File(d, name);
        try (OutputStream o = new FileOutputStream(f)) { w.write(o); }
        android.media.MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
        return PupFileProvider.uriFor(f);
    }

    static void open(Context c, Uri u, String mime) {
        try { c.startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Ouvrir avec 🐾").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }
    static void share(Context c, Uri u, String mime, String title) {
        try { c.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).putExtra(Intent.EXTRA_SUBJECT, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Partager 🐾").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }
}

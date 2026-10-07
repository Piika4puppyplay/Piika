package fr.piika.puppyphone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Partage des fichiers PDU MMS (noms aléatoires, dossier dédié) avec le service MMS du téléphone. */
public class MmsFileProvider extends ContentProvider {
    static final String AUTH = "fr.piika.puppyphone.mmsfiles";

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[0-9a-f-]{36}\\.[a-z0-9]{1,6}")) throw new FileNotFoundException();
        File f = new File(new File(getContext().getCacheDir(), name.endsWith(".pdu") ? "mms" : "share"), name);
        int m = mode.contains("w") ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) {
        String n = u.getLastPathSegment();
        if (n == null || n.endsWith(".pdu")) return "application/vnd.wap.mms-message";
        String ext = n.substring(n.lastIndexOf('.') + 1);
        String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "application/octet-stream" : m;
    }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}

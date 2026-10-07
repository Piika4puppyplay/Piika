package fr.piika.puppyphone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Partage d'un fichier de PupFile vers une autre appli (ouvrir avec / partager). content://fr.piika.puppyphone.files/<chemin> */
public class PupFileProvider extends ContentProvider {
    static final String AUTH = "fr.piika.puppyphone.files";

    static Uri uriFor(File f) { return new Uri.Builder().scheme("content").authority(AUTH).path(f.getAbsolutePath()).build(); }

    File file(Uri u) { return new File(u.getPath() == null ? "/" : u.getPath()); }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri u) { return FileActivity.mimeOf(file(u).getName()); }

    @Override public Cursor query(Uri u, String[] proj, String sel, String[] args, String sort) {
        File f = file(u);
        if (proj == null) proj = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(proj, 1);
        Object[] row = new Object[proj.length];
        for (int i = 0; i < proj.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(proj[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(proj[i])) row[i] = f.length();
            else if ("_data".equals(proj[i])) row[i] = f.getAbsolutePath();
        }
        c.addRow(row);
        return c;
    }

    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        File f = file(u);
        if (!f.isFile()) throw new FileNotFoundException(f.getPath());
        return ParcelFileDescriptor.open(f, mode != null && mode.contains("w") ? ParcelFileDescriptor.MODE_READ_WRITE : ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}

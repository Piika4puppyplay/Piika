package fr.piika.puppyphone;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.util.Size;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bibliothèque musicale PupMusic : scan MediaStore + pochettes. */
public final class MusicLib {
    private MusicLib() { }

    public static final class Track {
        public long id, albumId, dur;
        public String uri, title, artist, album, path;
        public Track(long id, String uri, String title, String artist, String album, long albumId, long dur, String path) {
            this.id = id; this.uri = uri; this.title = title; this.artist = artist; this.album = album; this.albumId = albumId; this.dur = dur; this.path = path;
        }
        public String key() { return id >= 0 ? String.valueOf(id) : "u:" + uri; }
    }

    static final Map<Long, Track> ALL = new HashMap<>();
    static volatile boolean loaded;
    static String lastJson = "[]";

    static String s(Cursor c, int i) { try { String v = i < 0 ? null : c.getString(i); return v == null ? "" : v; } catch (Exception e) { return ""; } }
    static long l(Cursor c, int i) { try { return i < 0 ? 0 : c.getLong(i); } catch (Exception e) { return 0; } }

    /** Scan complet. Renvoie un tableau JSON compact : [id,titre,artiste,album,albumId,durée,dossier,piste,année,ajout,genre,artisteAlbum,mime,bitrate,taille]. */
    static synchronized String scan(Context ctx, int minSec) {
        List<String> proj = new ArrayList<>();
        String[] base = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DISPLAY_NAME};
        for (String b : base) proj.add(b);
        if (Build.VERSION.SDK_INT >= 30) { proj.add("genre"); proj.add("album_artist"); proj.add("bitrate"); }
        String sel = MediaStore.Audio.Media.DURATION + ">=? AND " + MediaStore.Audio.Media.IS_RINGTONE + "=0 AND " + MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND " + MediaStore.Audio.Media.IS_ALARM + "=0";
        JSONArray out = new JSONArray();
        Map<Long, Track> m = new HashMap<>();
        Cursor c = null;
        try {
            try {
                c = ctx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj.toArray(new String[0]), sel, new String[]{String.valueOf(Math.max(0, minSec) * 1000L)}, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE");
            } catch (Exception e) {
                c = ctx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, base, sel, new String[]{String.valueOf(Math.max(0, minSec) * 1000L)}, null);
            }
            if (c != null) {
                int iG = c.getColumnIndex("genre"), iAA = c.getColumnIndex("album_artist"), iBR = c.getColumnIndex("bitrate");
                while (c.moveToNext()) {
                    long id = l(c, 0);
                    String title = s(c, 1);
                    if (title.isEmpty()) title = s(c, 12).replaceAll("\\.[^.]+$", "");
                    String artist = s(c, 2);
                    if (artist.equals("<unknown>")) artist = "";
                    String data = s(c, 6);
                    String folder = data.contains("/") ? data.substring(0, data.lastIndexOf('/')) : "";
                    Track t = new Track(id, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(), title, artist, s(c, 3), l(c, 4), l(c, 5), data);
                    m.put(id, t);
                    JSONArray a = new JSONArray();
                    a.put(id).put(title).put(artist).put(t.album).put(t.albumId).put(t.dur).put(folder).put(l(c, 7)).put(l(c, 8)).put(l(c, 9))
                            .put(s(c, iG)).put(s(c, iAA)).put(s(c, 10)).put(l(c, iBR)).put(l(c, 11));
                    out.put(a);
                }
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        synchronized (ALL) { ALL.clear(); ALL.putAll(m); }
        loaded = true;
        lastJson = out.toString();
        return lastJson;
    }

    static Track get(Context ctx, long id) {
        synchronized (ALL) { Track t = ALL.get(id); if (t != null) return t; }
        if (!loaded) { scan(ctx, 0); synchronized (ALL) { return ALL.get(id); } }
        return null;
    }

    /** Fichier ouvert depuis une autre appli (content:// ou file://). */
    static Track external(Context ctx, Uri u) {
        String title = null, artist = "", album = "";
        long dur = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, u);
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST); if (a != null) artist = a;
            String al = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM); if (al != null) album = al;
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION); if (d != null) dur = Long.parseLong(d);
        } catch (Exception ignored) {
        } finally { try { r.release(); } catch (Exception ignored) { } }
        if (title == null || title.isEmpty()) {
            try (Cursor c = ctx.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) title = c.getString(0);
            } catch (Exception ignored) { }
        }
        if (title == null || title.isEmpty()) title = u.getLastPathSegment() == null ? "Fichier audio" : u.getLastPathSegment();
        return new Track(-1 - Math.abs((long) u.toString().hashCode()), u.toString(), title, artist, album, -1, dur, "");
    }

    // ------------------------------------------------------------------ pochettes
    static final LruCache<String, byte[]> ART = new LruCache<String, byte[]>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, byte[] v) { return v.length; }
    };
    static final byte[] NONE = new byte[0];

    static byte[] art(Context ctx, long albumId, String trackUri, int size) {
        String key = albumId + "|" + (albumId < 0 ? trackUri : "") + "|" + size;
        byte[] b = ART.get(key);
        if (b != null) return b;
        Bitmap bm = artBitmap(ctx, albumId, trackUri, size);
        if (bm == null) { ART.put(key, NONE); return NONE; }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 88, o);
        b = o.toByteArray();
        ART.put(key, b);
        return b;
    }

    static Bitmap artBitmap(Context ctx, long albumId, String trackUri, int size) {
        if (albumId >= 0) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    return ctx.getContentResolver().loadThumbnail(ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId), new Size(size, size), null);
                }
                try (InputStream in = ctx.getContentResolver().openInputStream(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId))) {
                    Bitmap bm = BitmapFactory.decodeStream(in);
                    if (bm != null) return bm;
                }
            } catch (Exception ignored) { }
        }
        if (trackUri != null && !trackUri.isEmpty()) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(ctx, Uri.parse(trackUri));
                byte[] p = r.getEmbeddedPicture();
                if (p != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(p, 0, p.length, o);
                    o.inSampleSize = 1;
                    while (o.outWidth / (o.inSampleSize * 2) >= size) o.inSampleSize *= 2;
                    o.inJustDecodeBounds = false;
                    return BitmapFactory.decodeByteArray(p, 0, p.length, o);
                }
            } catch (Exception ignored) {
            } finally { try { r.release(); } catch (Exception ignored) { } }
        }
        return null;
    }
}

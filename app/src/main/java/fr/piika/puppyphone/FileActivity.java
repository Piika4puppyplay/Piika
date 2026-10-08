package fr.piika.puppyphone;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.LruCache;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** PupFile — gestionnaire de fichiers façon CX, thème puppyplay. Volumes nommés à la Linux (hda, sda, sdb…). */
public class FileActivity extends Activity {
    static final String HOST = "pupfile.local";
    static final String TRASH = ".PupPoubelle";
    static final int REQ_PERM = 81;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService jobs = Executors.newSingleThreadExecutor();
    final ExecutorService bg = Executors.newFixedThreadPool(2);
    final Map<Integer, AtomicBoolean> cancels = new HashMap<>();
    int jobSeq = 0;
    volatile int searchSeq = 0;
    WebView web;
    float dp = 1f;
    int insT, insB;
    String startPath;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        dp = getResources().getDisplayMetrics().density;
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0614);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        s.setOffscreenPreRaster(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !HOST.equals(r.getUrl().getHost()); }
            @Override public void onPageFinished(WebView v, String u) { pushInsets(); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Files");
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v, ins) -> {
            insT = ins.getSystemWindowInsetTop();
            insB = ins.getSystemWindowInsetBottom();
            pushInsets();
            return ins.consumeSystemWindowInsets();
        });
        web.loadUrl("https://" + HOST + "/file.html");
    }

    @Override protected void onResume() { super.onResume(); Pelage.watch(this, web); emit("resume", hasPerm() ? "1" : "0"); }
    @Override protected void onDestroy() { if (web != null) web.destroy(); jobs.shutdownNow(); bg.shutdownNow(); super.onDestroy(); }
    @Override public void onBackPressed() {
        web.evaluateJavascript("(window.FileUI&&FileUI.back())?'y':'n'", v -> { if (!"\"y\"".equals(v)) finish(); });
    }
    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) { emit("resume", hasPerm() ? "1" : "0"); }

    void emit(String ev, String data) {
        ui.post(() -> { if (web != null) web.evaluateJavascript("window.FileUI&&FileUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); });
    }
    void pushInsets() {
        final float t = insT / dp, bt = insB / dp;
        ui.post(() -> web.evaluateJavascript("window.FileUI&&FileUI.insets(" + t + "," + bt + ")", null));
    }
    void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    boolean hasPerm() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    void askPerm() {
        ui.post(() -> {
            if (Build.VERSION.SDK_INT >= 30) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Exception ignored) { } }
            } else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        });
    }

    // ------------------------------------------------------------------ utilitaires
    static String ext(String n) { int i = n.lastIndexOf('.'); return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT); }
    static String mimeOf(String n) {
        String e = ext(n);
        String m = e.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        if (m == null) {
            switch (e) {
                case "apk": return "application/vnd.android.package-archive";
                case "flac": return "audio/flac";
                case "opus": return "audio/ogg";
                case "mkv": return "video/x-matroska";
                case "md": case "log": case "ini": case "conf": case "cfg": case "yml": case "yaml": case "json": case "csv": case "lrc": return "text/plain";
                default: return "application/octet-stream";
            }
        }
        return m;
    }
    static boolean isText(String n) {
        String e = ext(n);
        return mimeOf(n).startsWith("text/") || e.matches("json|xml|js|css|html?|md|log|ini|conf|cfg|ya?ml|csv|lrc|srt|sh|py|java|kt|c|cpp|h|properties|gradle|toml|txt|nfo|m3u8?");
    }

    File uniq(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ex = "";
        int i = name.lastIndexOf('.');
        if (i > 0 && new File(dir, name).isFile()) { base = name.substring(0, i); ex = name.substring(i); }
        for (int k = 1; k < 10000; k++) { f = new File(dir, base + " (" + k + ")" + ex); if (!f.exists()) return f; }
        return f;
    }

    void scanMedia(List<String> paths) {
        if (paths.isEmpty()) return;
        try { MediaScannerConnection.scanFile(this, paths.toArray(new String[0]), null, null); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ volumes (gamelles)
    static class Vol { String root, dev, kind, label, desc; long total, free; boolean primary; }

    List<Vol> volumes() {
        List<Vol> out = new ArrayList<>();
        StorageManager sm = getSystemService(StorageManager.class);
        int sd = 0, usb = 0;
        List<StorageVolume> list = new ArrayList<>();
        try { list = sm.getStorageVolumes(); } catch (Exception ignored) { }
        for (StorageVolume v : list) {
            File dir = null;
            try {
                if (Build.VERSION.SDK_INT >= 30) dir = v.getDirectory();
                else dir = (File) v.getClass().getMethod("getPathFile").invoke(v);
            } catch (Exception ignored) { }
            if (dir == null || !Environment.MEDIA_MOUNTED.equals(v.getState()) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(v.getState())) continue;
            Vol o = new Vol();
            o.root = dir.getAbsolutePath();
            o.primary = v.isPrimary();
            o.desc = v.getDescription(this);
            String d = o.desc == null ? "" : o.desc.toLowerCase(Locale.ROOT);
            if (o.primary) { o.kind = "food"; o.dev = "hda"; o.label = "Gamelle Nourriture"; }
            else if (d.contains("usb") || d.contains("otg") || d.contains("disque") || d.contains("disk")) { o.kind = "treat"; o.dev = "sd" + (char) ('b' + usb++); o.label = "Gamelle Récompenses"; }
            else { o.kind = "water"; o.dev = sd == 0 ? "sda" : "sd" + (char) ('a' + 8 + sd); sd++; o.label = "Gamelle Eau"; }
            try { StatFs st = new StatFs(o.root); o.total = st.getTotalBytes(); o.free = st.getAvailableBytes(); } catch (Exception ignored) { }
            out.add(o);
        }
        if (out.isEmpty()) {
            Vol o = new Vol(); o.root = Environment.getExternalStorageDirectory().getAbsolutePath(); o.primary = true; o.kind = "food"; o.dev = "hda"; o.label = "Gamelle Nourriture"; o.desc = "";
            try { StatFs st = new StatFs(o.root); o.total = st.getTotalBytes(); o.free = st.getAvailableBytes(); } catch (Exception ignored) { }
            out.add(o);
        }
        // les clés USB après la carte SD
        out.sort((a, b) -> a.kind.equals(b.kind) ? 0 : a.kind.equals("food") ? -1 : b.kind.equals("food") ? 1 : a.kind.equals("water") ? -1 : 1);
        return out;
    }

    String volsJson() {
        JSONArray a = new JSONArray();
        try {
            for (Vol v : volumes())
                a.put(new JSONObject().put("root", v.root).put("dev", v.dev).put("kind", v.kind).put("label", v.label).put("desc", v.desc == null ? "" : v.desc)
                        .put("total", v.total).put("free", v.free).put("trash", trashCount(v.root)));
        } catch (Exception ignored) { }
        return a.toString();
    }

    Vol volOf(String path) {
        Vol best = null;
        for (Vol v : volumes()) if (path.equals(v.root) || path.startsWith(v.root + "/")) { if (best == null || v.root.length() > best.root.length()) best = v; }
        return best;
    }

    // ------------------------------------------------------------------ listage
    String listJson(String path, boolean hidden) {
        JSONObject o = new JSONObject();
        JSONArray a = new JSONArray();
        try {
            File d = new File(path);
            File[] fs = d.listFiles();
            o.put("path", d.getAbsolutePath()).put("ok", fs != null).put("canWrite", d.canWrite());
            if (fs != null) for (File f : fs) {
                String n = f.getName();
                if (!hidden && n.startsWith(".")) continue;
                boolean dir = f.isDirectory();
                JSONObject e = new JSONObject().put("n", n).put("d", dir).put("m", f.lastModified());
                if (dir) { String[] c = f.list(); e.put("c", c == null ? -1 : c.length); }
                else e.put("s", f.length());
                a.put(e);
            }
            o.put("items", a);
        } catch (Exception e) { try { o.put("ok", false).put("err", e.getMessage()); } catch (Exception ignored) { } }
        return o.toString();
    }

    // ------------------------------------------------------------------ bibliothèque (MediaStore)
    String libJson(String cat, int limit) {
        JSONArray a = new JSONArray();
        Uri u = MediaStore.Files.getContentUri("external");
        String sel;
        switch (cat) {
            case "images": sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE; break;
            case "videos": sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO; break;
            case "audio": sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO; break;
            case "docs": sel = "(" + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/pdf' OR " + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/msword' OR "
                    + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/vnd.openxmlformats%' OR " + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/vnd.ms-%' OR "
                    + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/vnd.oasis%' OR " + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'text/%' OR " + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/epub%')"; break;
            case "apk": sel = MediaStore.Files.FileColumns.DATA + " LIKE '%.apk'"; break;
            case "archives": sel = "(" + MediaStore.Files.FileColumns.DATA + " LIKE '%.zip' OR " + MediaStore.Files.FileColumns.DATA + " LIKE '%.rar' OR " + MediaStore.Files.FileColumns.DATA + " LIKE '%.7z' OR "
                    + MediaStore.Files.FileColumns.DATA + " LIKE '%.tar%' OR " + MediaStore.Files.FileColumns.DATA + " LIKE '%.gz')"; break;
            case "downloads": sel = MediaStore.Files.FileColumns.DATA + " LIKE '%/Download/%' AND " + MediaStore.Files.FileColumns.MIME_TYPE + " IS NOT NULL"; break;
            case "recent": sel = MediaStore.Files.FileColumns.MIME_TYPE + " IS NOT NULL AND " + MediaStore.Files.FileColumns.DATE_MODIFIED + ">" + (System.currentTimeMillis() / 1000 - 30L * 86400); break;
            case "big": sel = MediaStore.Files.FileColumns.SIZE + ">" + (20L * 1024 * 1024); break;
            default: return "[]";
        }
        String order = cat.equals("big") ? MediaStore.Files.FileColumns.SIZE + " DESC" : MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";
        try (Cursor c = getContentResolver().query(u, new String[]{MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED}, sel, null, order + " LIMIT " + limit)) {
            while (c != null && c.moveToNext()) {
                String p = c.getString(0);
                if (p == null || p.contains("/" + TRASH + "/")) continue;
                a.put(new JSONObject().put("p", p).put("s", c.getLong(1)).put("m", c.getLong(2) * 1000));
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    long sumOf(String cat) {
        Uri u = MediaStore.Files.getContentUri("external");
        String sel;
        switch (cat) {
            case "images": sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE; break;
            case "videos": sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO; break;
            case "audio": sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO; break;
            case "apk": sel = MediaStore.Files.FileColumns.DATA + " LIKE '%.apk'"; break;
            case "docs": sel = "(" + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/pdf' OR " + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/vnd.%' OR " + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'text/%' OR " + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/msword')"; break;
            case "archives": sel = "(" + MediaStore.Files.FileColumns.DATA + " LIKE '%.zip' OR " + MediaStore.Files.FileColumns.DATA + " LIKE '%.rar' OR " + MediaStore.Files.FileColumns.DATA + " LIKE '%.7z')"; break;
            default: return 0;
        }
        long t = 0;
        try (Cursor c = getContentResolver().query(u, new String[]{MediaStore.Files.FileColumns.SIZE}, sel, null, null)) {
            while (c != null && c.moveToNext()) t += c.getLong(0);
        } catch (Exception ignored) { }
        return t;
    }

    // ------------------------------------------------------------------ poubelle
    File trashDir(String volRoot) { return new File(volRoot, TRASH); }
    JSONObject trashIndex(File td) {
        try {
            File f = new File(td, ".index.json");
            if (!f.exists()) return new JSONObject();
            return new JSONObject(new String(readAll(f, 4 << 20), StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONObject(); }
    }
    void saveTrashIndex(File td, JSONObject idx) {
        try (FileOutputStream o = new FileOutputStream(new File(td, ".index.json"))) { o.write(idx.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { }
    }
    int trashCount(String volRoot) { File[] fs = trashDir(volRoot).listFiles(); if (fs == null) return 0; int n = 0; for (File f : fs) if (!f.getName().startsWith(".")) n++; return n; }

    String trashJson() {
        JSONArray a = new JSONArray();
        for (Vol v : volumes()) {
            File td = trashDir(v.root);
            File[] fs = td.listFiles();
            if (fs == null) continue;
            JSONObject idx = trashIndex(td);
            for (File f : fs) {
                if (f.getName().startsWith(".")) continue;
                JSONObject m = idx.optJSONObject(f.getName());
                try {
                    a.put(new JSONObject().put("p", f.getAbsolutePath()).put("n", m == null ? f.getName() : new File(m.optString("from")).getName())
                            .put("from", m == null ? "" : m.optString("from")).put("t", m == null ? f.lastModified() : m.optLong("t")).put("d", f.isDirectory()).put("s", f.isDirectory() ? 0 : f.length()).put("dev", v.dev));
                } catch (Exception ignored) { }
            }
        }
        return a.toString();
    }

    // ------------------------------------------------------------------ opérations (en tâche de fond)
    interface Job { void run(int id, AtomicBoolean cancel) throws Exception; }

    int job(String kind, Job j) {
        final int id = ++jobSeq;
        final AtomicBoolean cancel = new AtomicBoolean(false);
        synchronized (cancels) { cancels.put(id, cancel); }
        jobs.submit(() -> {
            try { j.run(id, cancel); progress(id, kind, "done", 1, 1, "", cancel.get() ? "Annulé" : ""); }
            catch (Throwable e) { progress(id, kind, "err", 0, 0, "", e.getMessage() == null ? e.toString() : e.getMessage()); }
            synchronized (cancels) { cancels.remove(id); }
        });
        return id;
    }

    long lastProg = 0;
    void progress(int id, String kind, String st, long done, long total, String name, String msg) {
        long now = System.currentTimeMillis();
        if ("run".equals(st) && now - lastProg < 120) return;
        lastProg = now;
        try { emit("job", new JSONObject().put("id", id).put("kind", kind).put("st", st).put("done", done).put("total", total).put("name", name).put("msg", msg == null ? "" : msg).toString()); } catch (Exception ignored) { }
    }

    static long sizeOf(File f, AtomicBoolean cancel) {
        if (cancel != null && cancel.get()) return 0;
        if (f.isFile()) return f.length();
        long t = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) t += sizeOf(c, cancel);
        return t;
    }
    static int[] countOf(File f) {
        int[] r = {0, 0};
        File[] fs = f.listFiles();
        if (fs == null) return r;
        for (File c : fs) { if (c.isDirectory()) { r[1]++; int[] s = countOf(c); r[0] += s[0]; r[1] += s[1]; } else r[0]++; }
        return r;
    }

    static class Ctr { long done, total; }

    void copyTree(File src, File dst, Ctr ctr, int id, String kind, AtomicBoolean cancel, List<String> scanned) throws Exception {
        if (cancel.get()) return;
        if (src.isDirectory()) {
            if (dst.getAbsolutePath().startsWith(src.getAbsolutePath() + "/")) throw new Exception("Impossible de copier un dossier dans lui-même");
            if (!dst.exists() && !dst.mkdirs()) throw new Exception("Création impossible : " + dst.getName());
            File[] fs = src.listFiles();
            if (fs != null) for (File c : fs) copyTree(c, new File(dst, c.getName()), ctr, id, kind, cancel, scanned);
            dst.setLastModified(src.lastModified());
            return;
        }
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.get()) break;
                out.write(buf, 0, n);
                ctr.done += n;
                progress(id, kind, "run", ctr.done, ctr.total, src.getName(), "");
            }
        }
        if (cancel.get()) { dst.delete(); return; }
        dst.setLastModified(src.lastModified());
        scanned.add(dst.getAbsolutePath());
    }

    static boolean deleteTree(File f, AtomicBoolean cancel) {
        if (cancel != null && cancel.get()) return false;
        if (f.isDirectory()) { File[] fs = f.listFiles(); if (fs != null) for (File c : fs) deleteTree(c, cancel); }
        return f.delete();
    }

    /** policy : rename | overwrite | skip */
    void paste(JSONArray srcs, String destDir, boolean move, String policy) {
        String kind = move ? "move" : "copy";
        job(kind, (id, cancel) -> {
            File dd = new File(destDir);
            List<File> list = new ArrayList<>();
            for (int i = 0; i < srcs.length(); i++) list.add(new File(srcs.getString(i)));
            Ctr ctr = new Ctr();
            for (File f : list) ctr.total += sizeOf(f, cancel);
            List<String> scanned = new ArrayList<>(), gone = new ArrayList<>();
            int done = 0;
            for (File f : list) {
                if (cancel.get()) break;
                File dst = new File(dd, f.getName());
                if (dst.getAbsolutePath().equals(f.getAbsolutePath())) { if (move) continue; dst = uniq(dd, f.getName()); }
                else if (dst.exists()) {
                    if ("skip".equals(policy)) continue;
                    if ("rename".equals(policy)) dst = uniq(dd, f.getName());
                    else { deleteTree(dst, null); }
                }
                if (move && f.renameTo(dst)) { ctr.done += sizeOf(dst, null); gone.add(f.getAbsolutePath()); scanned.add(dst.getAbsolutePath()); }
                else {
                    copyTree(f, dst, ctr, id, kind, cancel, scanned);
                    if (move && !cancel.get()) { deleteTree(f, null); gone.add(f.getAbsolutePath()); }
                }
                done++;
                progress(id, kind, "run", ctr.done, ctr.total, f.getName(), "");
            }
            scanMedia(scanned); scanMedia(gone);
        });
    }

    void toTrash(JSONArray paths) {
        job("trash", (id, cancel) -> {
            List<String> gone = new ArrayList<>();
            for (int i = 0; i < paths.length(); i++) {
                if (cancel.get()) break;
                File f = new File(paths.getString(i));
                Vol v = volOf(f.getAbsolutePath());
                if (v == null) throw new Exception("Volume introuvable");
                File td = trashDir(v.root);
                if (f.getAbsolutePath().startsWith(td.getAbsolutePath())) { deleteTree(f, cancel); continue; }
                td.mkdirs();
                try { new File(td, ".nomedia").createNewFile(); } catch (Exception ignored) { }
                String key = System.currentTimeMillis() + "_" + f.getName();
                File dst = new File(td, key);
                if (!f.renameTo(dst)) {
                    Ctr c = new Ctr(); c.total = sizeOf(f, cancel);
                    copyTree(f, dst, c, id, "trash", cancel, new ArrayList<>());
                    deleteTree(f, null);
                }
                JSONObject idx = trashIndex(td);
                idx.put(key, new JSONObject().put("from", f.getAbsolutePath()).put("t", System.currentTimeMillis()));
                saveTrashIndex(td, idx);
                gone.add(f.getAbsolutePath());
                progress(id, "trash", "run", i + 1, paths.length(), f.getName(), "");
            }
            scanMedia(gone);
        });
    }

    void deleteForever(JSONArray paths) {
        job("delete", (id, cancel) -> {
            List<String> gone = new ArrayList<>();
            for (int i = 0; i < paths.length(); i++) {
                File f = new File(paths.getString(i));
                progress(id, "delete", "run", i, paths.length(), f.getName(), "");
                deleteTree(f, cancel);
                File td = f.getParentFile();
                if (td != null && TRASH.equals(td.getName())) { JSONObject idx = trashIndex(td); idx.remove(f.getName()); saveTrashIndex(td, idx); }
                gone.add(f.getAbsolutePath());
            }
            scanMedia(gone);
        });
    }

    void restore(JSONArray paths) {
        job("restore", (id, cancel) -> {
            List<String> back = new ArrayList<>();
            for (int i = 0; i < paths.length(); i++) {
                File f = new File(paths.getString(i));
                File td = f.getParentFile();
                JSONObject idx = trashIndex(td);
                JSONObject m = idx.optJSONObject(f.getName());
                File dst = m == null ? new File(Environment.getExternalStorageDirectory(), "Restaurés/" + f.getName()) : new File(m.optString("from"));
                if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
                if (dst.exists()) dst = uniq(dst.getParentFile(), dst.getName());
                if (!f.renameTo(dst)) throw new Exception("Restauration impossible : " + dst.getName());
                idx.remove(f.getName()); saveTrashIndex(td, idx);
                back.add(dst.getAbsolutePath());
                progress(id, "restore", "run", i + 1, paths.length(), dst.getName(), "");
            }
            scanMedia(back);
        });
    }

    void zip(JSONArray paths, String dest) {
        job("zip", (id, cancel) -> {
            File out = new File(dest);
            if (out.exists()) out = uniq(out.getParentFile(), out.getName());
            Ctr ctr = new Ctr();
            List<File> list = new ArrayList<>();
            for (int i = 0; i < paths.length(); i++) { File f = new File(paths.getString(i)); list.add(f); ctr.total += sizeOf(f, cancel); }
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
                for (File f : list) addZip(z, f, f.getName(), ctr, id, cancel);
            }
            if (cancel.get()) out.delete();
            else { List<String> l = new ArrayList<>(); l.add(out.getAbsolutePath()); scanMedia(l); }
        });
    }
    void addZip(ZipOutputStream z, File f, String name, Ctr ctr, int id, AtomicBoolean cancel) throws Exception {
        if (cancel.get()) return;
        if (f.isDirectory()) {
            z.putNextEntry(new ZipEntry(name + "/")); z.closeEntry();
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) addZip(z, c, name + "/" + c.getName(), ctr, id, cancel);
            return;
        }
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        z.putNextEntry(e);
        byte[] buf = new byte[128 * 1024];
        try (InputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) { if (cancel.get()) break; z.write(buf, 0, n); ctr.done += n; progress(id, "zip", "run", ctr.done, ctr.total, f.getName(), ""); }
        }
        z.closeEntry();
    }

    void unzip(String zipPath, String destDir) {
        job("unzip", (id, cancel) -> {
            File zf = new File(zipPath);
            File dd = new File(destDir);
            if (dd.exists()) dd = uniq(dd.getParentFile(), dd.getName());
            dd.mkdirs();
            String canon = dd.getCanonicalPath() + File.separator;
            long total = zf.length(), done = 0;
            List<String> scanned = new ArrayList<>();
            try (ZipInputStream z = new ZipInputStream(new FileInputStream(zf))) {
                ZipEntry e;
                byte[] buf = new byte[128 * 1024];
                while ((e = z.getNextEntry()) != null) {
                    if (cancel.get()) break;
                    File out = new File(dd, e.getName());
                    if (!out.getCanonicalPath().startsWith(canon)) continue; // protection « zip slip »
                    if (e.isDirectory()) { out.mkdirs(); continue; }
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    try (OutputStream o = new FileOutputStream(out)) {
                        int n;
                        while ((n = z.read(buf)) > 0) { o.write(buf, 0, n); }
                    }
                    if (e.getTime() > 0) out.setLastModified(e.getTime());
                    done += Math.max(0, e.getCompressedSize());
                    scanned.add(out.getAbsolutePath());
                    progress(id, "unzip", "run", Math.min(done, total), total, out.getName(), "");
                }
            }
            scanMedia(scanned);
        });
    }

    static byte[] readAll(File f, int max) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[64 * 1024];
            int n, t = 0;
            while ((n = in.read(b)) > 0) { o.write(b, 0, n); t += n; if (t > max) throw new Exception("Fichier trop gros pour l'éditeur (" + (max >> 20) + " Mo max)"); }
            return o.toByteArray();
        }
    }

    // ------------------------------------------------------------------ analyse (top dossiers / gros fichiers)
    void analyze(String root) {
        bg.submit(() -> {
            try {
                File r = new File(root);
                JSONArray dirs = new JSONArray();
                File[] fs = r.listFiles();
                List<Object[]> ds = new ArrayList<>();
                PriorityQueue<Object[]> big = new PriorityQueue<>((a, b) -> Long.compare((long) a[1], (long) b[1]));
                if (fs != null) for (File f : fs) {
                    long s = f.isDirectory() ? walk(f, big) : f.length();
                    if (f.isFile()) { big.add(new Object[]{f.getAbsolutePath(), s}); if (big.size() > 40) big.poll(); }
                    ds.add(new Object[]{f.getAbsolutePath(), s, f.isDirectory()});
                    emit("analyzeStep", f.getName());
                }
                ds.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
                for (Object[] d : ds) dirs.put(new JSONObject().put("p", d[0]).put("s", d[1]).put("d", d[2]));
                List<Object[]> bl = new ArrayList<>(big);
                bl.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
                JSONArray ba = new JSONArray();
                for (Object[] b : bl) ba.put(new JSONObject().put("p", b[0]).put("s", b[1]));
                JSONObject cats = new JSONObject();
                if (volOf(root) != null && volOf(root).primary) for (String c : new String[]{"images", "videos", "audio", "docs", "apk", "archives"}) cats.put(c, sumOf(c));
                emit("analyze", new JSONObject().put("root", root).put("dirs", dirs).put("big", ba).put("cats", cats).toString());
            } catch (Exception e) { emit("analyze", "{}"); }
        });
    }
    long walk(File d, PriorityQueue<Object[]> big) {
        long t = 0;
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            if (f.isDirectory()) t += walk(f, big);
            else { long s = f.length(); t += s; if (big.size() < 40 || s > (long) big.peek()[1]) { big.add(new Object[]{f.getAbsolutePath(), s}); if (big.size() > 40) big.poll(); } }
        }
        return t;
    }

    // ------------------------------------------------------------------ applis
    String appsJson(boolean sys) {
        JSONArray a = new JSONArray();
        PackageManager pm = getPackageManager();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean isSys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (isSys && !sys) continue;
                PackageInfo pi = null;
                try { pi = pm.getPackageInfo(ai.packageName, 0); } catch (Exception ignored) { }
                long size = new File(ai.sourceDir).length();
                if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) size += new File(s).length();
                a.put(new JSONObject().put("pkg", ai.packageName).put("n", String.valueOf(pm.getApplicationLabel(ai))).put("v", pi == null ? "" : pi.versionName)
                        .put("s", size).put("t", pi == null ? 0 : pi.firstInstallTime).put("u", pi == null ? 0 : pi.lastUpdateTime).put("sys", isSys)
                        .put("launch", pm.getLaunchIntentForPackage(ai.packageName) != null).put("split", ai.splitSourceDirs != null && ai.splitSourceDirs.length > 0));
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    void backupApk(String pkg) {
        job("apk", (id, cancel) -> {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PupFile/APK");
            dir.mkdirs();
            String base = (pm.getApplicationLabel(ai) + "_" + pi.versionName).replaceAll("[\\\\/:*?\"<>|]", "_");
            File out = uniq(dir, base + ".apk");
            Ctr c = new Ctr(); c.total = new File(ai.sourceDir).length();
            List<String> sc = new ArrayList<>();
            copyTree(new File(ai.sourceDir), out, c, id, "apk", cancel, sc);
            scanMedia(sc);
            emit("saved", out.getAbsolutePath());
        });
    }

    // ------------------------------------------------------------------ miniatures
    final LruCache<String, byte[]> thumbs = new LruCache<String, byte[]>(20 * 1024 * 1024) { @Override protected int sizeOf(String k, byte[] v) { return v.length; } };

    byte[] thumb(String path, int size) {
        String key = path + "|" + size;
        byte[] b = thumbs.get(key);
        if (b != null) return b;
        File f = new File(path);
        Bitmap bm = null;
        String m = mimeOf(f.getName());
        try {
            if (m.startsWith("image/")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                o.inSampleSize = 1;
                while (Math.min(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= size) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                bm = BitmapFactory.decodeFile(path, o);
                try {
                    int or = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                    int deg = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
                    if (deg != 0 && bm != null) { Matrix mx = new Matrix(); mx.postRotate(deg); bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), mx, true); }
                } catch (Exception ignored) { }
            } else if (m.startsWith("video/")) {
                if (Build.VERSION.SDK_INT >= 29) bm = ThumbnailUtils.createVideoThumbnail(f, new android.util.Size(size, size), null);
                else bm = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND);
            } else if (m.startsWith("audio/")) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try { r.setDataSource(path); byte[] p = r.getEmbeddedPicture(); if (p != null) bm = BitmapFactory.decodeByteArray(p, 0, p.length); } finally { try { r.release(); } catch (Exception ignored) { } }
            } else if (path.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                PackageInfo pi = getPackageManager().getPackageArchiveInfo(path, 0);
                if (pi != null && pi.applicationInfo != null) {
                    pi.applicationInfo.sourceDir = path; pi.applicationInfo.publicSourceDir = path;
                    bm = drawableToBitmap(pi.applicationInfo.loadIcon(getPackageManager()), size);
                }
            }
        } catch (Throwable ignored) { }
        if (bm == null) { thumbs.put(key, new byte[0]); return new byte[0]; }
        if (Math.max(bm.getWidth(), bm.getHeight()) > size * 2) {
            float k = size * 2f / Math.max(bm.getWidth(), bm.getHeight());
            bm = Bitmap.createScaledBitmap(bm, Math.max(1, (int) (bm.getWidth() * k)), Math.max(1, (int) (bm.getHeight() * k)), true);
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        bm.compress(path.toLowerCase(Locale.ROOT).endsWith(".apk") ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 84, o);
        b = o.toByteArray();
        thumbs.put(key, b);
        return b;
    }
    static Bitmap drawableToBitmap(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        try {
            if (p.equals("/thumb")) {
                byte[] b = thumb(u.getQueryParameter("p"), Integer.parseInt(u.getQueryParameter("s") == null ? "128" : u.getQueryParameter("s")));
                if (b.length == 0) throw new Exception();
                h.put("Cache-Control", "max-age=86400");
                return new WebResourceResponse(b[0] == (byte) 0x89 ? "image/png" : "image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(b));
            }
            if (p.equals("/raw")) {
                File f = new File(u.getQueryParameter("p"));
                return new WebResourceResponse(mimeOf(f.getName()), null, 200, "OK", h, new FileInputStream(f));
            }
            if (p.equals("/appicon")) {
                String key = "app:" + u.getQueryParameter("p");
                byte[] b = thumbs.get(key);
                if (b == null) {
                    Bitmap bm = drawableToBitmap(getPackageManager().getApplicationIcon(u.getQueryParameter("p")), 144);
                    ByteArrayOutputStream o = new ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.PNG, 90, o); b = o.toByteArray(); thumbs.put(key, b);
                }
                h.put("Cache-Control", "max-age=86400");
                return new WebResourceResponse("image/png", null, 200, "OK", h, new ByteArrayInputStream(b));
            }
            String path = p.equals("/") ? "/file.html" : p;
            InputStream in = Pelage.open(this, path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    // ------------------------------------------------------------------ ouvrir / partager
    void openFile(String path, boolean chooser) {
        ui.post(() -> {
            File f = new File(path);
            String m = mimeOf(f.getName());
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(PupFileProvider.uriFor(f), m).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (m.startsWith("audio/")) { try { startActivity(new Intent(this, MusicActivity.class).setAction(Intent.ACTION_VIEW).setDataAndType(PupFileProvider.uriFor(f), m).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); return; } catch (Exception ignored) { } }
            try { startActivity(chooser ? Intent.createChooser(i, "Ouvrir avec…") : i); }
            catch (Exception e) { try { startActivity(Intent.createChooser(i.setDataAndType(PupFileProvider.uriFor(f), "*/*"), "Ouvrir avec…")); } catch (Exception ex) { toast("Aucune appli pour ouvrir ce fichier"); } }
        });
    }

    void share(JSONArray paths) {
        ui.post(() -> {
            try {
                ArrayList<Uri> us = new ArrayList<>();
                String type = null;
                for (int i = 0; i < paths.length(); i++) {
                    File f = new File(paths.getString(i));
                    if (f.isDirectory()) continue;
                    us.add(PupFileProvider.uriFor(f));
                    String m = mimeOf(f.getName());
                    type = type == null ? m : type.equals(m) ? type : type.split("/")[0].equals(m.split("/")[0]) ? m.split("/")[0] + "/*" : "*/*";
                }
                if (us.isEmpty()) { toast("Les dossiers ne se partagent pas : compresse-les d'abord en ZIP"); return; }
                Intent i;
                if (us.size() == 1) i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, us.get(0));
                else i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, us);
                i.setType(type);
                ClipData cd = ClipData.newRawUri("", us.get(0));
                for (int k = 1; k < us.size(); k++) cd.addItem(new ClipData.Item(us.get(k)));
                i.setClipData(cd);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Partager"));
            } catch (Exception e) { toast("Partage impossible"); }
        });
    }

    // ------------------------------------------------------------------ pont JS
    class Bridge {
        @JavascriptInterface public boolean perm() { return hasPerm(); }
        @JavascriptInterface public void askPerm() { FileActivity.this.askPerm(); }
        @JavascriptInterface public String vols() { return volsJson(); }
        @JavascriptInterface public String list(String path, boolean hidden) { return listJson(path, hidden); }
        @JavascriptInterface public void lib(String cat, int limit) { bg.submit(() -> emit("lib", "{\"cat\":" + JSONObject.quote(cat) + ",\"items\":" + libJson(cat, limit) + "}")); }
        @JavascriptInterface public String home() { return Environment.getExternalStorageDirectory().getAbsolutePath(); }
        @JavascriptInterface public boolean exists(String p) { return new File(p).exists(); }
        @JavascriptInterface public String conflicts(String srcs, String dest) {
            JSONArray a = new JSONArray();
            try { JSONArray s = new JSONArray(srcs); for (int i = 0; i < s.length(); i++) { File f = new File(s.getString(i)); File d = new File(dest, f.getName()); if (d.exists() && !d.getAbsolutePath().equals(f.getAbsolutePath())) a.put(f.getName()); } } catch (Exception ignored) { }
            return a.toString();
        }
        @JavascriptInterface public int paste(String srcs, String dest, boolean move, String policy) { try { FileActivity.this.paste(new JSONArray(srcs), dest, move, policy); } catch (Exception e) { toast("Erreur : " + e.getMessage()); } return jobSeq; }
        @JavascriptInterface public void trash(String paths) { try { toTrash(new JSONArray(paths)); } catch (Exception ignored) { } }
        @JavascriptInterface public void delete(String paths) { try { deleteForever(new JSONArray(paths)); } catch (Exception ignored) { } }
        @JavascriptInterface public void restore(String paths) { try { FileActivity.this.restore(new JSONArray(paths)); } catch (Exception ignored) { } }
        @JavascriptInterface public String trashList() { return trashJson(); }
        @JavascriptInterface public void cancel(int id) { synchronized (cancels) { AtomicBoolean c = cancels.get(id); if (c != null) c.set(true); } }
        @JavascriptInterface public String mkdir(String parent, String name) {
            File f = new File(parent, name);
            if (f.exists()) return "Existe déjà";
            return f.mkdirs() ? "" : "Création refusée ici";
        }
        @JavascriptInterface public String mkfile(String parent, String name) {
            File f = new File(parent, name);
            if (f.exists()) return "Existe déjà";
            try { return f.createNewFile() ? "" : "Création refusée ici"; } catch (Exception e) { return e.getMessage(); }
        }
        @JavascriptInterface public String rename(String path, String name) {
            File f = new File(path), d = new File(f.getParentFile(), name);
            if (name.contains("/")) return "Le nom ne peut pas contenir « / »";
            if (d.exists() && !d.getAbsolutePath().equalsIgnoreCase(f.getAbsolutePath())) return "Un élément porte déjà ce nom";
            if (!f.renameTo(d)) return "Renommage refusé";
            List<String> l = new ArrayList<>(); l.add(f.getAbsolutePath()); l.add(d.getAbsolutePath()); scanMedia(l);
            return "";
        }
        @JavascriptInterface public void zip(String paths, String dest) { try { FileActivity.this.zip(new JSONArray(paths), dest); } catch (Exception ignored) { } }
        @JavascriptInterface public void unzip(String zip, String dest) { FileActivity.this.unzip(zip, dest); }
        @JavascriptInterface public String props(String path) {
            try {
                File f = new File(path);
                JSONObject o = new JSONObject().put("p", f.getAbsolutePath()).put("n", f.getName()).put("d", f.isDirectory()).put("m", f.lastModified())
                        .put("r", f.canRead()).put("w", f.canWrite()).put("x", f.canExecute()).put("hidden", f.isHidden()).put("mime", f.isDirectory() ? "dossier" : mimeOf(f.getName()));
                if (f.isFile()) o.put("s", f.length());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void deepSize(String path) {
            bg.submit(() -> {
                File f = new File(path);
                long s = sizeOf(f, null);
                int[] c = f.isDirectory() ? countOf(f) : new int[]{1, 0};
                try { emit("size", new JSONObject().put("p", path).put("s", s).put("files", c[0]).put("dirs", c[1]).toString()); } catch (Exception ignored) { }
            });
        }
        @JavascriptInterface public void hash(String path, String algo) {
            bg.submit(() -> {
                try {
                    MessageDigest md = MessageDigest.getInstance(algo);
                    try (InputStream in = new FileInputStream(path)) { byte[] b = new byte[256 * 1024]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
                    StringBuilder sb = new StringBuilder();
                    for (byte x : md.digest()) sb.append(String.format("%02x", x));
                    emit("hash", new JSONObject().put("p", path).put("algo", algo).put("v", sb.toString()).toString());
                } catch (Exception e) { emit("hash", "{}"); }
            });
        }
        @JavascriptInterface public String readText(String path) {
            try { return new JSONObject().put("ok", true).put("t", new String(readAll(new File(path), 3 << 20), StandardCharsets.UTF_8)).toString(); }
            catch (Exception e) { try { return new JSONObject().put("ok", false).put("err", e.getMessage()).toString(); } catch (Exception x) { return "{}"; } }
        }
        @JavascriptInterface public String writeText(String path, String text) {
            try (FileOutputStream o = new FileOutputStream(path)) { o.write(text.getBytes(StandardCharsets.UTF_8)); return ""; } catch (Exception e) { return e.getMessage(); }
        }
        @JavascriptInterface public boolean isText(String name) { return FileActivity.isText(name); }
        @JavascriptInterface public String mime(String name) { return mimeOf(name); }
        @JavascriptInterface public void open(String path) { openFile(path, false); }
        @JavascriptInterface public void openWith(String path) { openFile(path, true); }
        @JavascriptInterface public void share(String paths) { try { FileActivity.this.share(new JSONArray(paths)); } catch (Exception ignored) { } }
        @JavascriptInterface public void analyze(String root) { FileActivity.this.analyze(root); }
        @JavascriptInterface public void search(String root, String q, boolean hidden) {
            final int sid = ++searchSeq;
            bg.submit(() -> {
                JSONArray a = new JSONArray();
                String n = q.toLowerCase(Locale.ROOT);
                java.util.ArrayDeque<File> st = new java.util.ArrayDeque<>();
                st.push(new File(root));
                int seen = 0;
                while (!st.isEmpty() && a.length() < 500 && sid == searchSeq) {
                    File d = st.pop();
                    File[] fs = d.listFiles();
                    if (fs == null) continue;
                    for (File f : fs) {
                        String nm = f.getName();
                        if (!hidden && nm.startsWith(".")) continue;
                        if (nm.toLowerCase(Locale.ROOT).contains(n)) { try { a.put(new JSONObject().put("p", f.getAbsolutePath()).put("d", f.isDirectory()).put("s", f.isFile() ? f.length() : 0).put("m", f.lastModified())); } catch (Exception ignored) { } }
                        if (f.isDirectory()) st.push(f);
                        if (++seen % 400 == 0) emit("searchStep", d.getName());
                    }
                }
                if (sid == searchSeq) try { emit("search", new JSONObject().put("q", q).put("items", a).toString()); } catch (Exception ignored) { }
            });
        }
        @JavascriptInterface public void stopSearch() { searchSeq++; }
        @JavascriptInterface public String apps(boolean sys) { return appsJson(sys); }
        @JavascriptInterface public void launchApp(String pkg) { ui.post(() -> { Intent i = getPackageManager().getLaunchIntentForPackage(pkg); if (i != null) startActivity(i); else toast("Pas d'écran à ouvrir"); }); }
        @JavascriptInterface public void appInfo(String pkg) { ui.post(() -> { try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void uninstall(String pkg) { ui.post(() -> { try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void backupApk(String pkg) { FileActivity.this.backupApk(pkg); }
        @JavascriptInterface public void haptic() { ui.post(() -> web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)); }
        @JavascriptInterface public void toast(String s) { FileActivity.this.toast(s); }
    }
}

package fr.piika.puppyphone;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.OpenableColumns;
import android.provider.Telephony;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PupSMS — SMS & MMS dans le thème puppyplay. */
public class SmsActivity extends Activity {
    static final String HOST = "pupsms.local";
    static final int REQ_PERM = 51, REQ_ROLE = 52, REQ_PICK = 53;
    static volatile String openThreadAddr;

    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    float dp = 1f;
    int insT, insB;
    String startAddr, startBody;
    final List<String> startAttach = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        dp = getResources().getDisplayMetrics().density;
        readIntent(getIntent());

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0614);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        s.setOffscreenPreRaster(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
            @Override public void onPageFinished(WebView v, String u) { pushInsets(); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Sms");
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v, ins) -> {
            insT = ins.getSystemWindowInsetTop();
            insB = ins.getSystemWindowInsetBottom();
            pushInsets();
            return ins.consumeSystemWindowInsets();
        });
        web.loadUrl("https://" + HOST + "/sms.html");
        SmsCore.channel(this);
        askPerms();
    }

    void readIntent(Intent i) {
        if (i == null) return;
        String a = i.getAction();
        startAddr = i.getStringExtra("addr");
        startBody = null;
        startAttach.clear();
        Uri d = i.getData();
        if ((Intent.ACTION_SENDTO.equals(a) || Intent.ACTION_VIEW.equals(a)) && d != null) {
            String ssp = d.getSchemeSpecificPart();
            if (ssp != null) {
                int q = ssp.indexOf('?');
                String num = q >= 0 ? ssp.substring(0, q) : ssp;
                startAddr = Uri.decode(num).replaceAll("[^0-9+,;]", "").split("[,;]")[0];
                if (q >= 0) { Uri qu = Uri.parse("x:?" + ssp.substring(q + 1)); startBody = qu.getQueryParameter("body"); }
            }
            if (i.hasExtra("sms_body")) startBody = i.getStringExtra("sms_body");
        }
        if (Intent.ACTION_SEND.equals(a) || Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            startBody = i.getStringExtra(Intent.EXTRA_TEXT);
            Uri one = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) startAttach.add(copyIn(one));
            ArrayList<Uri> many = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null && Intent.ACTION_SEND_MULTIPLE.equals(a)) for (Uri u : many) startAttach.add(copyIn(u));
            startAttach.removeIf(x -> x == null);
        }
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); readIntent(i); emit("open", startJson()); }

    @Override protected void onResume() {
        super.onResume(); Pelage.watch(this, web);
        SmsCore.listener = () -> emit("changed", "");
        emit("resume", "");
    }

    @Override protected void onPause() { SmsCore.listener = null; openThreadAddr = null; super.onPause(); }

    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }

    @Override public void onBackPressed() {
        web.evaluateJavascript("(window.SmsUI&&SmsUI.back())?'y':'n'", v -> { if (!"\"y\"".equals(v)) finish(); });
    }

    void emit(String ev, String data) {
        ui.post(() -> { if (web != null) web.evaluateJavascript("window.SmsUI&&SmsUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); });
    }

    void pushInsets() {
        final float t = insT / dp, bt = insB / dp;
        ui.post(() -> web.evaluateJavascript("window.SmsUI&&SmsUI.insets(" + t + "," + bt + ")", null));
    }

    void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    String startJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("addr", startAddr == null ? "" : startAddr);
            o.put("body", startBody == null ? "" : startBody);
            JSONArray a = new JSONArray();
            for (String u : startAttach) a.put(attJson(Uri.parse(u)));
            o.put("attach", a);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    // ------------------------------------------------------------------ permissions & appli par défaut
    void askPerms() {
        List<String> p = new ArrayList<>();
        String[] want = {Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.RECEIVE_MMS,
                Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE};
        for (String x : want) if (checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) p.add(x);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) { emit("changed", ""); }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_ROLE) { emit("changed", ""); return; }
        if (req == REQ_PICK) {
            JSONArray a = new JSONArray();
            if (res == RESULT_OK && data != null) {
                List<Uri> us = new ArrayList<>();
                if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) us.add(data.getClipData().getItemAt(i).getUri());
                else if (data.getData() != null) us.add(data.getData());
                for (Uri u : us) { String c = copyIn(u); if (c != null) a.put(attJson(Uri.parse(c))); }
            }
            emit("attach", a.toString());
            return;
        }
        super.onActivityResult(req, res, data);
    }

    // ------------------------------------------------------------------ pièces jointes : copie locale (l'autorisation de lecture peut expirer)
    File shareDir() { File d = new File(getCacheDir(), "share"); d.mkdirs(); return d; }

    String copyIn(Uri u) {
        try {
            String name = "fichier";
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
            } catch (Exception ignored) { }
            String mime = SmsCore.mimeOf(this, u);
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext == null) { int dot = name.lastIndexOf('.'); ext = dot > 0 ? name.substring(dot + 1).toLowerCase() : "bin"; }
            File f = new File(shareDir(), UUID.randomUUID() + "." + ext.replaceAll("[^a-z0-9]", ""));
            try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            getSharedPreferences("pupsms_names", MODE_PRIVATE).edit().putString(f.getName(), name).putString(f.getName() + ".mime", mime).apply();
            return Uri.fromFile(f).toString();
        } catch (Exception e) { toast("Pièce jointe illisible"); return null; }
    }

    JSONObject attJson(Uri fileUri) {
        JSONObject o = new JSONObject();
        try {
            File f = new File(fileUri.getPath());
            o.put("u", fileUri.toString());
            o.put("name", getSharedPreferences("pupsms_names", MODE_PRIVATE).getString(f.getName(), f.getName()));
            o.put("mime", getSharedPreferences("pupsms_names", MODE_PRIVATE).getString(f.getName() + ".mime", "application/octet-stream"));
            o.put("size", f.length());
            o.put("key", f.getName());
        } catch (Exception ignored) { }
        return o;
    }

    // ------------------------------------------------------------------ lecture des conversations
    Map<String, String> canonical() {
        Map<String, String> m = new HashMap<>();
        try (Cursor c = getContentResolver().query(Uri.parse("content://mms-sms/canonical-addresses"), new String[]{"_id", "address"}, null, null, null)) {
            while (c != null && c.moveToNext()) m.put(c.getString(0), c.getString(1));
        } catch (Exception ignored) { }
        return m;
    }

    String convosJson() {
        JSONArray a = new JSONArray();
        Map<String, String> can = canonical();
        Uri u = Uri.parse("content://mms-sms/conversations?simple=true");
        try (Cursor c = getContentResolver().query(u, new String[]{"_id", "date", "message_count", "recipient_ids", "snippet", "read"}, null, null, "date DESC")) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(2) == 0) continue;
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("date", c.getLong(1));
                o.put("count", c.getInt(2));
                String ids = c.getString(3);
                JSONArray addrs = new JSONArray(), names = new JSONArray();
                String photo = null;
                if (ids != null) for (String id : ids.trim().split(" ")) {
                    String ad = can.get(id);
                    if (ad == null) continue;
                    addrs.put(ad);
                    String[] ct = SmsCore.contact(this, ad);
                    names.put(ct[0] != null ? ct[0] : ad);
                    if (photo == null) photo = ct[1];
                }
                o.put("addrs", addrs);
                o.put("names", names);
                o.put("photo", photo == null ? "" : photo);
                o.put("snippet", c.getString(4) == null ? "" : c.getString(4));
                o.put("unread", c.getInt(5) == 0);
                a.put(o);
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    String messagesJson(long thread) {
        List<JSONObject> l = new ArrayList<>();
        try (Cursor c = getContentResolver().query(Telephony.Sms.CONTENT_URI, new String[]{"_id", "address", "body", "date", "type"},
                "thread_id=?", new String[]{String.valueOf(thread)}, "date DESC LIMIT 400")) {
            while (c != null && c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("k", "s");
                o.put("id", c.getLong(0));
                o.put("addr", c.getString(1));
                o.put("body", c.getString(2) == null ? "" : c.getString(2));
                o.put("date", c.getLong(3));
                int t = c.getInt(4);
                o.put("me", t != Telephony.Sms.MESSAGE_TYPE_INBOX);
                o.put("st", t == Telephony.Sms.MESSAGE_TYPE_FAILED ? "fail" : (t == Telephony.Sms.MESSAGE_TYPE_OUTBOX || t == Telephony.Sms.MESSAGE_TYPE_QUEUED) ? "sending" : "ok");
                l.add(o);
            }
        } catch (Exception ignored) { }
        Map<Long, JSONObject> mms = new HashMap<>();
        try (Cursor c = getContentResolver().query(Telephony.Mms.CONTENT_URI, new String[]{"_id", "date", "msg_box"},
                "thread_id=?", new String[]{String.valueOf(thread)}, "date DESC LIMIT 200")) {
            while (c != null && c.moveToNext()) {
                JSONObject o = new JSONObject();
                long id = c.getLong(0);
                o.put("k", "m");
                o.put("id", id);
                o.put("date", c.getLong(1) * 1000L);
                int box = c.getInt(2);
                o.put("me", box != Telephony.Mms.MESSAGE_BOX_INBOX);
                o.put("st", box == Telephony.Mms.MESSAGE_BOX_FAILED ? "fail" : box == Telephony.Mms.MESSAGE_BOX_OUTBOX ? "sending" : "ok");
                o.put("body", "");
                o.put("parts", new JSONArray());
                mms.put(id, o);
                l.add(o);
            }
        } catch (Exception ignored) { }
        if (!mms.isEmpty()) {
            StringBuilder in = new StringBuilder();
            for (Long id : mms.keySet()) { if (in.length() > 0) in.append(','); in.append(id); }
            try (Cursor c = getContentResolver().query(Uri.parse("content://mms/part"), new String[]{"_id", "mid", "ct", "text", "name", "cl"},
                    "mid IN (" + in + ")", null, null)) {
                while (c != null && c.moveToNext()) {
                    JSONObject m = mms.get(c.getLong(1));
                    if (m == null) continue;
                    String ct = c.getString(2) == null ? "" : c.getString(2);
                    if (ct.equals("application/smil")) continue;
                    if (ct.startsWith("text/plain")) { m.put("body", (m.optString("body") + " " + (c.getString(3) == null ? "" : c.getString(3))).trim()); continue; }
                    JSONObject p = new JSONObject();
                    p.put("id", c.getLong(0));
                    p.put("ct", ct);
                    p.put("name", c.getString(4) != null ? c.getString(4) : c.getString(5) != null ? c.getString(5) : "fichier");
                    m.getJSONArray("parts").put(p);
                }
            } catch (Exception ignored) { }
            for (Long id : mms.keySet()) {
                JSONObject m = mms.get(id);
                if (m.optBoolean("me")) continue;
                try (Cursor c = getContentResolver().query(Uri.parse("content://mms/" + id + "/addr"), new String[]{"address"}, "type=137", null, null)) {
                    if (c != null && c.moveToFirst()) m.put("addr", c.getString(0));
                } catch (Exception ignored) { }
            }
        }
        l.sort((x, y) -> Long.compare(x.optLong("date"), y.optLong("date")));
        JSONArray a = new JSONArray();
        for (JSONObject o : l) a.put(o);
        return a.toString();
    }

    // ------------------------------------------------------------------ fichiers servis à la page
    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        try {
            if (p.equals("/part")) {
                Uri pu = Uri.parse("content://mms/part/" + Long.parseLong(u.getQueryParameter("id")));
                String ct = u.getQueryParameter("ct");
                h.put("Cache-Control", "max-age=86400");
                return new WebResourceResponse(ct == null ? "application/octet-stream" : ct, null, 200, "OK", h, getContentResolver().openInputStream(pu));
            }
            if (p.equals("/avatar")) {
                Uri au = Uri.parse(u.getQueryParameter("u"));
                if (!"content".equals(au.getScheme()) || !String.valueOf(au.getAuthority()).contains("contacts")) return notFound();
                h.put("Cache-Control", "max-age=86400");
                return new WebResourceResponse("image/jpeg", null, 200, "OK", h, getContentResolver().openInputStream(au));
            }
            if (p.equals("/att")) {
                File f = new File(shareDir(), new File(u.getQueryParameter("k")).getName());
                return new WebResourceResponse(getSharedPreferences("pupsms_names", MODE_PRIVATE).getString(f.getName() + ".mime", "application/octet-stream"), null, 200, "OK", h, new java.io.FileInputStream(f));
            }
            String path = p.equals("/") ? "/sms.html" : p;
            InputStream in = Pelage.open(this, path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return notFound();
        }
    }

    WebResourceResponse notFound() { return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", new HashMap<>(), new ByteArrayInputStream(new byte[0])); }

    // ------------------------------------------------------------------ pont
    class Bridge {
        @JavascriptInterface public boolean isDefault() { return SmsCore.isDefault(SmsActivity.this); }
        @JavascriptInterface public boolean canRead() { return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED; }
        @JavascriptInterface public void askDefault() {
            ui.post(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        RoleManager rm = getSystemService(RoleManager.class);
                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), REQ_ROLE); return; }
                    }
                    startActivityForResult(new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getPackageName()), REQ_ROLE);
                } catch (Exception e) { toast("Ouvre Paramètres → Applis par défaut → SMS"); }
            });
        }
        @JavascriptInterface public void askPerms() { ui.post(SmsActivity.this::askPerms); }
        @JavascriptInterface public String start() { String s = startJson(); startAddr = null; startBody = null; startAttach.clear(); return s; }
        @JavascriptInterface public String convos() { return convosJson(); }
        @JavascriptInterface public String messages(long thread) { return messagesJson(thread); }
        @JavascriptInterface public long threadFor(String addrsJson) {
            try {
                JSONArray a = new JSONArray(addrsJson);
                HashSet<String> s = new HashSet<>();
                for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                return Telephony.Threads.getOrCreateThreadId(SmsActivity.this, s);
            } catch (Exception e) { return -1; }
        }
        @JavascriptInterface public String contact(String addr) {
            String[] c = SmsCore.contact(SmsActivity.this, addr);
            try { return new JSONObject().put("name", c[0] == null ? "" : c[0]).put("photo", c[1] == null ? "" : c[1]).toString(); } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public String searchContacts(String q) {
            JSONArray a = new JSONArray();
            try (Cursor c = getContentResolver().query(Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q == null || q.isEmpty() ? "%" : q)),
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI},
                    null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIMIT 60")) {
                HashSet<String> seen = new HashSet<>();
                while (c != null && c.moveToNext()) {
                    String n = c.getString(1);
                    if (n == null || !seen.add(n.replaceAll("[^0-9+]", ""))) continue;
                    a.put(new JSONObject().put("name", c.getString(0)).put("num", n).put("photo", c.getString(2) == null ? "" : c.getString(2)));
                }
            } catch (Exception ignored) { }
            return a.toString();
        }
        @JavascriptInterface public void opened(String addr) { openThreadAddr = addr; new Thread(() -> SmsCore.markThreadRead(SmsActivity.this, addr)).start(); }
        @JavascriptInterface public void closed() { openThreadAddr = null; }
        @JavascriptInterface public int maxMms() { return SmsCore.maxMmsSize(SmsActivity.this); }

        @JavascriptInterface public void pick(String kind) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE);
                switch (kind) {
                    case "image": i.setType("image/*"); break;
                    case "video": i.setType("video/*"); break;
                    case "pdf": i.setType("application/pdf"); break;
                    default: i.setType("*/*");
                }
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(Intent.createChooser(i, "Joindre"), REQ_PICK); } catch (Exception e) { toast("Aucun sélecteur de fichiers"); }
            });
        }

        @JavascriptInterface public void send(String addrsJson, String text, String attJson) {
            new Thread(() -> {
                try {
                    JSONArray a = new JSONArray(addrsJson);
                    List<String> to = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) to.add(a.getString(i));
                    JSONArray at = new JSONArray(attJson == null || attJson.isEmpty() ? "[]" : attJson);
                    if (at.length() == 0 && to.size() == 1) { SmsCore.sendSms(SmsActivity.this, to.get(0), text); emit("sent", ""); return; }
                    int max = SmsCore.maxMmsSize(SmsActivity.this) - 12 * 1024;
                    int used = text == null ? 0 : text.length() * 3;
                    List<MmsPdu.Part> parts = new ArrayList<>();
                    int nImg = 0;
                    for (int i = 0; i < at.length(); i++) if (at.getJSONObject(i).optString("mime").startsWith("image/") && !at.getJSONObject(i).optString("mime").contains("gif")) nImg++;
                    for (int i = 0; i < at.length(); i++) {
                        JSONObject o = at.getJSONObject(i);
                        Uri fu = Uri.parse(o.getString("u"));
                        String mime = o.optString("mime", "application/octet-stream");
                        String name = o.optString("name", "fichier").replaceAll("[^A-Za-z0-9._-]", "_");
                        byte[] data;
                        if (mime.startsWith("image/") && !mime.contains("gif")) {
                            int budget = Math.max(40 * 1024, (max - used) / Math.max(1, nImg));
                            data = SmsCore.fitImage(SmsActivity.this, fu, budget);
                            mime = "image/jpeg";
                            if (!name.toLowerCase().endsWith(".jpg")) name = name.replaceAll("\\.[A-Za-z0-9]+$", "") + ".jpg";
                            nImg--;
                        } else {
                            data = SmsCore.readAll(SmsActivity.this, fu, Math.max(0, max - used));
                        }
                        used += data.length;
                        if (used > max) throw new IllegalStateException("trop lourd");
                        parts.add(new MmsPdu.Part(mime, name, data));
                    }
                    SmsCore.sendMms(SmsActivity.this, to, text, parts);
                    emit("sent", "");
                } catch (IllegalStateException e) {
                    emit("senderr", "Trop lourd pour un MMS : ton opérateur limite à " + (SmsCore.maxMmsSize(SmsActivity.this) / 1024) + " Ko. Vidéo plus courte ou fichier plus léger 🐾");
                } catch (Exception e) {
                    emit("senderr", "Envoi impossible : " + e.getMessage());
                }
            }, "pupsms-send").start();
        }

        @JavascriptInterface public void deleteThread(long thread) {
            new Thread(() -> {
                try { getContentResolver().delete(Uri.parse("content://mms-sms/conversations/" + thread), null, null); } catch (Exception e) { toast("Suppression réservée à l'appli SMS par défaut"); }
                emit("changed", "");
            }).start();
        }

        @JavascriptInterface public void deleteMsg(String kind, long id) {
            new Thread(() -> {
                try { getContentResolver().delete(Uri.parse(("m".equals(kind) ? "content://mms/" : "content://sms/") + id), null, null); } catch (Exception ignored) { }
                emit("changed", "");
            }).start();
        }

        @JavascriptInterface public void retry(String kind, long id, String addr, String body) {
            new Thread(() -> {
                if ("s".equals(kind)) {
                    try { getContentResolver().delete(Uri.parse("content://sms/" + id), null, null); } catch (Exception ignored) { }
                    SmsCore.sendSms(SmsActivity.this, addr, body);
                } else toast("Renvoie la pièce jointe avec le bouton +");
            }).start();
        }

        @JavascriptInterface public void openPart(long id, String ct, String name) {
            new Thread(() -> {
                try {
                    String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(ct);
                    File f = new File(shareDir(), UUID.randomUUID() + "." + (ext == null ? "bin" : ext));
                    try (InputStream in = getContentResolver().openInputStream(Uri.parse("content://mms/part/" + id)); OutputStream o = new FileOutputStream(f)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    }
                    Uri cu = new Uri.Builder().scheme("content").authority(MmsFileProvider.AUTH).path(f.getName()).build();
                    Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(cu, ct).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    ui.post(() -> { try { startActivity(Intent.createChooser(v, name)); } catch (Exception e) { toast("Aucune appli pour ouvrir ce fichier"); } });
                } catch (Exception e) { toast("Fichier illisible"); }
            }).start();
        }

        @JavascriptInterface public void dial(String num) { ui.post(() -> { try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + num))); } catch (Exception ignored) { } }); }
        @JavascriptInterface public void haptic() { ui.post(() -> web.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)); }
        @JavascriptInterface public void toast(String s) { SmsActivity.this.toast(s); }
        @JavascriptInterface public String get(String k) { return getSharedPreferences("pupsms", MODE_PRIVATE).getString(k, null); }
        @JavascriptInterface public void set(String k, String v) { getSharedPreferences("pupsms", MODE_PRIVATE).edit().putString(k, v).apply(); }
    }
}

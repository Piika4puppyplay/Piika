package fr.piika.puppyphone;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** PupPhone — clavier, récents, contacts, favoris. */
public class DialerActivity extends Activity {
    static final String HOST = "pupphone.local";
    static final int REQ_PERM = 61, REQ_ROLE = 62;
    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    float dp = 1f;
    int insT, insB;
    String startNumber, startTab;
    String pendingCall;

    String mode() { return "dial"; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        dp = getResources().getDisplayMetrics().density;
        beforeWeb();
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
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !HOST.equals(r.getUrl().getHost()); }
            @Override public void onPageFinished(WebView v, String u) { pushInsets(); }
        });
        web.setWebChromeClient(new WebChromeClient());
        addBridges(web);
        setContentView(web);
        web.setOnApplyWindowInsetsListener((v, ins) -> {
            insT = ins.getSystemWindowInsetTop();
            insB = ins.getSystemWindowInsetBottom();
            pushInsets();
            return ins.consumeSystemWindowInsets();
        });
        web.loadUrl("https://" + HOST + "/phone.html?mode=" + mode());
        askPerms();
    }

    void beforeWeb() { }

    void addBridges(WebView w) { w.addJavascriptInterface(new Bridge(), "Phone"); }

    void readIntent(Intent i) {
        if (i == null) return;
        startTab = i.getStringExtra("tab");
        Uri d = i.getData();
        if (d != null && "tel".equals(d.getScheme())) startNumber = d.getSchemeSpecificPart();
        String c = i.getStringExtra("call");
        if (c != null) { startNumber = c; pendingCall = c; }
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); readIntent(i); emit("start", startJson()); if (pendingCall != null) { String p = pendingCall; pendingCall = null; place(p); } }

    @Override protected void onResume() { super.onResume(); Pelage.watch(this, web); emit("resume", ""); if (pendingCall != null && hasCallPerm()) { String p = pendingCall; pendingCall = null; place(p); } }

    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }

    @Override public void onBackPressed() {
        web.evaluateJavascript("(window.PhoneUI&&PhoneUI.back())?'y':'n'", v -> { if (!"\"y\"".equals(v)) finish(); });
    }

    void emit(String ev, String data) {
        ui.post(() -> { if (web != null) web.evaluateJavascript("window.PhoneUI&&PhoneUI.on(" + JSONObject.quote(ev) + "," + JSONObject.quote(data == null ? "" : data) + ")", null); });
    }

    void pushInsets() {
        final float t = insT / dp, bt = insB / dp;
        ui.post(() -> web.evaluateJavascript("window.PhoneUI&&PhoneUI.insets(" + t + "," + bt + ")", null));
    }

    void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    String startJson() {
        try { return new JSONObject().put("number", startNumber == null ? "" : startNumber).put("tab", startTab == null ? "" : startTab).toString(); } catch (Exception e) { return "{}"; }
    }

    void askPerms() {
        List<String> p = new ArrayList<>();
        for (String x : new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE})
            if (checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) p.add(x);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) { emit("resume", ""); }
    @Override protected void onActivityResult(int r, int res, Intent d) { emit("resume", ""); super.onActivityResult(r, res, d); }

    boolean hasCallPerm() { return checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED; }

    boolean isDefaultDialer() {
        try { return getPackageName().equals(getSystemService(TelecomManager.class).getDefaultDialerPackage()); } catch (Exception e) { return false; }
    }

    void place(String number) {
        ui.post(() -> {
            String n = number == null ? "" : number.trim();
            if (n.isEmpty()) return;
            if (!hasCallPerm()) { pendingCall = n; askPerms(); return; }
            try {
                getSystemService(TelecomManager.class).placeCall(Uri.fromParts("tel", n, null), new Bundle());
            } catch (SecurityException e) {
                try { startActivity(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null))); } catch (Exception ex) { toast("Appel impossible"); }
            } catch (Exception e) { toast("Appel impossible : " + e.getMessage()); }
        });
    }

    // ------------------------------------------------------------------ données
    String callLogJson() {
        JSONArray a = new JSONArray();
        try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_PHOTO_URI},
                null, null, CallLog.Calls.DATE + " DESC LIMIT 300")) {
            while (c != null && c.moveToNext()) {
                String num = c.getString(1) == null ? "" : c.getString(1);
                String name = c.getString(2);
                String photo = c.getString(6);
                if (name == null || name.isEmpty()) { String[] ct = SmsCore.contact(this, num); name = ct[0]; if (photo == null) photo = ct[1]; }
                a.put(new JSONObject().put("id", c.getLong(0)).put("num", num).put("name", name == null ? "" : name).put("type", c.getInt(3))
                        .put("date", c.getLong(4)).put("dur", c.getLong(5)).put("photo", photo == null ? "" : photo));
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    String contactsJson(boolean starredOnly) {
        JSONArray a = new JSONArray();
        String sel = starredOnly ? ContactsContract.CommonDataKinds.Phone.STARRED + "=1" : null;
        try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI, ContactsContract.CommonDataKinds.Phone.STARRED, ContactsContract.CommonDataKinds.Phone.TYPE},
                sel, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")) {
            HashSet<String> seen = new HashSet<>();
            while (c != null && c.moveToNext()) {
                String num = c.getString(2);
                if (num == null) continue;
                String key = c.getLong(0) + ":" + num.replaceAll("[^0-9+]", "");
                if (!seen.add(key)) continue;
                a.put(new JSONObject().put("cid", c.getLong(0)).put("name", c.getString(1) == null ? num : c.getString(1)).put("num", num)
                        .put("photo", c.getString(3) == null ? "" : c.getString(3)).put("star", c.getInt(4) == 1)
                        .put("label", ContactsContract.CommonDataKinds.Phone.getTypeLabel(getResources(), c.getInt(5), "").toString()));
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> h = new HashMap<>();
        try {
            if (p.equals("/avatar")) {
                Uri au = Uri.parse(u.getQueryParameter("u"));
                if (!"content".equals(au.getScheme()) || !String.valueOf(au.getAuthority()).contains("contacts")) throw new Exception();
                h.put("Cache-Control", "max-age=86400");
                return new WebResourceResponse("image/jpeg", null, 200, "OK", h, getContentResolver().openInputStream(au));
            }
            String path = p.equals("/") ? "/phone.html" : p;
            InputStream in = Pelage.open(this, path);
            String mime = MainActivity.mime(path);
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Introuvable", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    class Bridge {
        @JavascriptInterface public String start() { String s = startJson(); startNumber = null; startTab = null; return s; }
        @JavascriptInterface public String log() { return callLogJson(); }
        @JavascriptInterface public String contacts() { return contactsJson(false); }
        @JavascriptInterface public String favorites() { return contactsJson(true); }
        @JavascriptInterface public void call(String n) { place(n); }
        @JavascriptInterface public void sms(String n) { ui.post(() -> startActivity(new Intent(DialerActivity.this, SmsActivity.class).putExtra("addr", n))); }
        @JavascriptInterface public void addContact(String n) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE).putExtra(ContactsContract.Intents.Insert.PHONE, n);
                try { startActivity(i); } catch (Exception e) { toast("Aucune appli Contacts"); }
            });
        }
        @JavascriptInterface public void openContact(long cid) {
            ui.post(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, String.valueOf(cid)))); } catch (Exception ignored) { } });
        }
        @JavascriptInterface public void star(long cid, boolean on) {
            new Thread(() -> {
                try {
                    ContentValues v = new ContentValues();
                    v.put(ContactsContract.Contacts.STARRED, on ? 1 : 0);
                    getContentResolver().update(ContactsContract.Contacts.CONTENT_URI, v, ContactsContract.Contacts._ID + "=?", new String[]{String.valueOf(cid)});
                } catch (Exception e) { toast("Modification des favoris refusée"); }
                emit("resume", "");
            }).start();
        }
        @JavascriptInterface public void deleteLog(long id) {
            new Thread(() -> {
                try { getContentResolver().delete(CallLog.Calls.CONTENT_URI, CallLog.Calls._ID + "=?", new String[]{String.valueOf(id)}); }
                catch (Exception e) { toast("Suppression réservée à l'appli Téléphone par défaut"); }
                emit("resume", "");
            }).start();
        }
        @JavascriptInterface public boolean isDefault() { return isDefaultDialer(); }
        @JavascriptInterface public void askDefault() {
            ui.post(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        RoleManager rm = getSystemService(RoleManager.class);
                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE); return; }
                    }
                    startActivityForResult(new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName()), REQ_ROLE);
                } catch (Exception e) { toast("Ouvre Paramètres → Applis par défaut → Téléphone"); }
            });
        }
        @JavascriptInterface public boolean inCall() { return PupInCallService.current() != null || PupInCallService.ringing() != null; }
        @JavascriptInterface public void openCall() { ui.post(() -> startActivity(new Intent(DialerActivity.this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))); }
        @JavascriptInterface public void haptic() { ui.post(() -> web.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)); }
        @JavascriptInterface public void toast(String s) { DialerActivity.this.toast(s); }
        @JavascriptInterface public void tone(String d) { Tones.play(d); }
    }
}

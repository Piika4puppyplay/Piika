package fr.piika.puppyphone;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Oreille de PuppyPhone : lit les notifications (avec ton accord dans « Accès aux notifications »)
 * pour les redessiner en puppyplay dans le volet, les pop-ups, la Veille et l'horloge d'accueil.
 * Rien ne quitte le téléphone.
 */
public class PupNotifs extends NotificationListenerService {
    static volatile PupNotifs I;
    static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    static final Handler h = new Handler(Looper.getMainLooper());
    static final HashMap<String, byte[]> appIcons = new HashMap<>();
    static final HashMap<String, String> labels = new HashMap<>();

    static boolean granted(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(new ComponentName(c, PupNotifs.class).flattenToString());
    }
    static void openSettings(Context c) {
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(c, PupNotifs.class).flattenToString());
        else i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { try { c.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }
    }

    static Runnable fireR = () -> { for (Runnable r : listeners) try { r.run(); } catch (Exception ignored) { } };
    static void fire() { h.removeCallbacks(fireR); h.postDelayed(fireR, 120); }

    @Override public void onListenerConnected() { I = this; fire(); }
    @Override public void onListenerDisconnected() { I = null; fire(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn, RankingMap rm) {
        fire();
        try {
            Ranking r = new Ranking();
            boolean high = rm != null && rm.getRanking(sbn.getKey(), r) && r.getImportance() >= android.app.NotificationManager.IMPORTANCE_HIGH;
            if (high) h.post(() -> PupVolet.popup(this, sbn));
        } catch (Exception ignored) { }
    }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { fire(); h.post(() -> PupVolet.popupGone(sbn.getKey())); }
    @Override public void onNotificationRankingUpdate(RankingMap rm) { fire(); }

    // ------------------------------------------------------------ lecture
    static StatusBarNotification[] all() {
        PupNotifs s = I; if (s == null) return new StatusBarNotification[0];
        try { StatusBarNotification[] a = s.getActiveNotifications(); return a == null ? new StatusBarNotification[0] : a; } catch (Exception e) { return new StatusBarNotification[0]; }
    }
    static StatusBarNotification find(String key) { for (StatusBarNotification s : all()) if (s.getKey().equals(key)) return s; return null; }

    static CharSequence cs(Bundle b, String k) { Object o = b.get(k); return o instanceof CharSequence ? (CharSequence) o : null; }
    static String str(CharSequence c) { return c == null ? "" : c.toString(); }

    /** Une notification « montrable » : pas de résumé de groupe vide, pas de notification sans texte. */
    static boolean showable(StatusBarNotification s, HashSet<String> groupsWithChildren) {
        Notification n = s.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && groupsWithChildren.contains(s.getPackageName() + "|" + s.getGroupKey())) return false;
        Bundle e = n.extras;
        return !(str(cs(e, Notification.EXTRA_TITLE)).isEmpty() && str(cs(e, Notification.EXTRA_TEXT)).isEmpty() && str(cs(e, Notification.EXTRA_BIG_TEXT)).isEmpty() && e.get(Notification.EXTRA_MESSAGES) == null);
    }

    static JSONObject one(Context c, StatusBarNotification s, boolean content) throws Exception {
        Notification n = s.getNotification(); Bundle e = n.extras;
        JSONObject o = new JSONObject().put("key", s.getKey()).put("pkg", s.getPackageName()).put("app", label(c, s.getPackageName()))
                .put("when", n.when != 0 ? n.when : s.getPostTime()).put("ongoing", s.isOngoing()).put("clear", s.isClearable())
                .put("color", String.format("#%06X", n.color & 0xFFFFFF)).put("hasColor", n.color != 0).put("cat", n.category == null ? "" : n.category)
                .put("media", e.containsKey(Notification.EXTRA_MEDIA_SESSION));
        if (!content) return o;
        String title = str(cs(e, Notification.EXTRA_TITLE));
        String conv = str(cs(e, Notification.EXTRA_CONVERSATION_TITLE));
        String text = str(cs(e, Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) text = str(cs(e, Notification.EXTRA_TEXT));
        Parcelable[] msgs = e.getParcelableArray(Notification.EXTRA_MESSAGES);
        JSONArray lines = new JSONArray();
        if (msgs != null && msgs.length > 0) {
            for (int i = Math.max(0, msgs.length - 4); i < msgs.length; i++) {
                if (!(msgs[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) msgs[i];
                CharSequence who = m.getCharSequence("sender"); CharSequence t = m.getCharSequence("text");
                if (who == null && Build.VERSION.SDK_INT >= 28) { Object p = m.get("sender_person"); if (p instanceof android.app.Person) who = ((android.app.Person) p).getName(); }
                lines.put(new JSONObject().put("who", str(who)).put("t", str(t)));
            }
        } else {
            CharSequence[] tl = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (tl != null) for (int i = 0; i < Math.min(tl.length, 5); i++) lines.put(new JSONObject().put("who", "").put("t", str(tl[i])));
        }
        o.put("title", conv.isEmpty() ? title : conv).put("text", text).put("sub", str(cs(e, Notification.EXTRA_SUB_TEXT))).put("lines", lines);
        int pmax = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        if (pmax > 0 || e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) o.put("prog", pmax > 0 ? e.getInt(Notification.EXTRA_PROGRESS, 0) * 100 / pmax : -1);
        o.put("large", n.getLargeIcon() != null).put("pic", e.containsKey(Notification.EXTRA_PICTURE) && e.get(Notification.EXTRA_PICTURE) != null);
        JSONArray acts = new JSONArray();
        if (n.actions != null) for (int i = 0; i < n.actions.length && i < 3; i++) {
            Notification.Action a = n.actions[i];
            boolean reply = a.getRemoteInputs() != null && a.getRemoteInputs().length > 0;
            acts.put(new JSONObject().put("i", i).put("t", str(a.title)).put("reply", reply));
        }
        o.put("acts", acts).put("tap", n.contentIntent != null);
        return o;
    }

    /** Toutes les notifications visibles, les plus récentes d'abord. */
    static String json(Context c, boolean content) {
        JSONArray a = new JSONArray();
        try {
            StatusBarNotification[] list = all();
            HashSet<String> groups = new HashSet<>();
            for (StatusBarNotification s : list) if ((s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0 && s.getNotification().getGroup() != null) groups.add(s.getPackageName() + "|" + s.getGroupKey());
            ArrayList<StatusBarNotification> l = new ArrayList<>();
            for (StatusBarNotification s : list) if (showable(s, groups)) l.add(s);
            l.sort((x, y) -> Long.compare(y.getPostTime(), x.getPostTime()));
            for (StatusBarNotification s : l) a.put(one(c, s, content));
        } catch (Exception ignored) { }
        return a.toString();
    }

    /** Résumé par appli (pour la Veille et l'horloge) : [{pkg, app, n, title, text}]. */
    static String summary(Context c, boolean content) {
        JSONArray out = new JSONArray();
        try {
            JSONArray a = new JSONArray(json(c, true));
            java.util.LinkedHashMap<String, JSONObject> by = new java.util.LinkedHashMap<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optBoolean("ongoing") && !o.optBoolean("media")) continue; // services en cours : pas d'intérêt sur la Veille
                String p = o.getString("pkg");
                JSONObject g = by.get(p);
                if (g == null) { g = new JSONObject().put("pkg", p).put("app", o.optString("app")).put("n", 0); if (content) g.put("title", o.optString("title")).put("text", o.optString("text")); by.put(p, g); }
                g.put("n", g.getInt("n") + 1);
            }
            for (JSONObject g : by.values()) out.put(g);
        } catch (Exception ignored) { }
        return out.toString();
    }

    static String label(Context c, String pkg) {
        synchronized (labels) { String l = labels.get(pkg); if (l != null) return l; }
        String l;
        try { PackageManager pm = c.getPackageManager(); ApplicationInfo ai = pm.getApplicationInfo(pkg, 0); l = String.valueOf(pm.getApplicationLabel(ai)); }
        catch (Exception e) { l = pkg; }
        if ("android".equals(pkg)) l = "Système";
        synchronized (labels) { labels.put(pkg, l); }
        return l;
    }

    // ------------------------------------------------------------ images
    static byte[] png(Bitmap b, int max) {
        if (b == null) return null;
        int w = b.getWidth(), hh = b.getHeight(); float k = Math.min(1f, max / (float) Math.max(w, hh));
        Bitmap s = k < 1 ? Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(hh * k)), true) : b;
        ByteArrayOutputStream o = new ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.PNG, 100, o); return o.toByteArray();
    }
    static Bitmap draw(Drawable d, int size) {
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b); d.setBounds(0, 0, size, size); d.draw(cv); return b;
    }
    static byte[] appIcon(Context c, String pkg) {
        synchronized (appIcons) { if (appIcons.containsKey(pkg)) return appIcons.get(pkg); }
        byte[] r = null;
        try { r = png(draw(c.getPackageManager().getApplicationIcon(pkg), 128), 128); } catch (Exception ignored) { }
        synchronized (appIcons) { appIcons.put(pkg, r); }
        return r;
    }
    static byte[] image(Context c, String key, String kind) {
        StatusBarNotification s = find(key); if (s == null) return null;
        try {
            Notification n = s.getNotification();
            if ("pic".equals(kind)) { Object o = n.extras.get(Notification.EXTRA_PICTURE); if (o instanceof Bitmap) return png((Bitmap) o, 720); return null; }
            if ("small".equals(kind)) { Icon i = n.getSmallIcon(); return i == null ? null : png(draw(i.loadDrawable(s.getPackageName().equals(c.getPackageName()) ? c : c.createPackageContext(s.getPackageName(), 0)), 64), 64); }
            Icon i = n.getLargeIcon(); return i == null ? null : png(draw(i.loadDrawable(c), 160), 160);
        } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------ actions
    static Bundle bal() {
        if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            return o.toBundle();
        }
        return null;
    }
    static void send(Context c, PendingIntent pi, Intent fill) throws Exception {
        if (Build.VERSION.SDK_INT >= 34) pi.send(c, 0, fill, null, null, null, bal());
        else pi.send(c, 0, fill);
    }
    /** Ouvre la notification (comme un appui dans le volet Android). */
    static boolean open(Context c, String key) {
        StatusBarNotification s = find(key); if (s == null) return false;
        Notification n = s.getNotification();
        try {
            if (n.contentIntent != null) send(c, n.contentIntent, null);
            else { Intent i = c.getPackageManager().getLaunchIntentForPackage(s.getPackageName()); if (i != null) c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0) dismiss(key);
            return true;
        } catch (Exception e) { return false; }
    }
    static boolean action(Context c, String key, int idx, String reply) {
        StatusBarNotification s = find(key); if (s == null) return false;
        Notification n = s.getNotification();
        if (n.actions == null || idx < 0 || idx >= n.actions.length) return false;
        Notification.Action a = n.actions[idx];
        try {
            Intent fill = null;
            RemoteInput[] ri = a.getRemoteInputs();
            if (ri != null && ri.length > 0 && reply != null) {
                fill = new Intent(); Bundle b = new Bundle();
                for (RemoteInput r : ri) b.putCharSequence(r.getResultKey(), reply);
                RemoteInput.addResultsToIntent(ri, fill, b);
            }
            send(c, a.actionIntent, fill);
            return true;
        } catch (Exception e) { return false; }
    }
    static void dismiss(String key) { PupNotifs s = I; if (s != null) try { s.cancelNotification(key); } catch (Exception ignored) { } }
    static void clearAll() { PupNotifs s = I; if (s != null) try { s.cancelAllNotifications(); } catch (Exception ignored) { } }

    // ------------------------------------------------------------ musique en cours
    static MediaController controller(Context c) {
        if (I == null) return null;
        try {
            List<MediaController> l = c.getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(c, PupNotifs.class));
            MediaController best = null;
            for (MediaController m : l) {
                PlaybackState st = m.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return m;
                if (best == null && m.getMetadata() != null) best = m;
            }
            return best;
        } catch (Exception e) { return null; }
    }
    static String media(Context c) {
        MediaController m = controller(c);
        if (m == null || m.getMetadata() == null) return "{}";
        try {
            MediaMetadata md = m.getMetadata(); PlaybackState st = m.getPlaybackState();
            return new JSONObject().put("pkg", m.getPackageName()).put("app", label(c, m.getPackageName()))
                    .put("title", str(md.getText(MediaMetadata.METADATA_KEY_TITLE))).put("artist", str(md.getText(MediaMetadata.METADATA_KEY_ARTIST)))
                    .put("album", str(md.getText(MediaMetadata.METADATA_KEY_ALBUM))).put("dur", md.getLong(MediaMetadata.METADATA_KEY_DURATION))
                    .put("pos", st == null ? 0 : st.getPosition()).put("playing", st != null && st.getState() == PlaybackState.STATE_PLAYING)
                    .put("art", md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null || md.getBitmap(MediaMetadata.METADATA_KEY_ART) != null)
                    .put("id", str(md.getText(MediaMetadata.METADATA_KEY_TITLE)).hashCode()).toString();
        } catch (Exception e) { return "{}"; }
    }
    static byte[] mediaArt(Context c) {
        MediaController m = controller(c); if (m == null || m.getMetadata() == null) return null;
        Bitmap b = m.getMetadata().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getMetadata().getBitmap(MediaMetadata.METADATA_KEY_ART);
        return png(b, 400);
    }
    static void mediaCmd(Context c, String cmd) {
        MediaController m = controller(c); if (m == null) return;
        MediaController.TransportControls t = m.getTransportControls();
        PlaybackState st = m.getPlaybackState();
        switch (cmd) {
            case "toggle": if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play(); break;
            case "next": t.skipToNext(); break;
            case "prev": t.skipToPrevious(); break;
            default: break;
        }
        h.postDelayed(PupNotifs::fire, 300);
    }
}

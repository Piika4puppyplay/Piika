package fr.piika.puppyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.CarrierConfigManager;
import android.telephony.SmsManager;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Boîte à outils PupSMS : envoi/réception SMS & MMS, base de messages Android, contacts, notifications. */
public final class SmsCore {
    private SmsCore() { }

    static final String CH = "pupsms";
    static final String ACT_SMS_SENT = "fr.piika.puppyphone.SMS_SENT";
    static final String ACT_MMS_SENT = "fr.piika.puppyphone.MMS_SENT";
    static final String ACT_MMS_DOWNLOADED = "fr.piika.puppyphone.MMS_DOWNLOADED";
    static final String ACT_REPLY = "fr.piika.puppyphone.SMS_REPLY";
    static final String ACT_READ = "fr.piika.puppyphone.SMS_READ";
    static final String KEY_REPLY = "pupsms_reply";

    /** Prévenir l'écran ouvert qu'il y a du nouveau. */
    static volatile Runnable listener;
    static void changed() { Runnable l = listener; if (l != null) l.run(); }

    static SmsManager sms(Context c) {
        if (Build.VERSION.SDK_INT >= 31) return c.getSystemService(SmsManager.class);
        return SmsManager.getDefault();
    }

    static boolean isDefault(Context c) {
        try { return c.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(c)); } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ contacts
    static final Map<String, String[]> contactCache = new HashMap<>();

    /** {nom, photoUri} pour un numéro (ou {null,null}). */
    static String[] contact(Context c, String number) {
        if (number == null || number.isEmpty()) return new String[]{null, null};
        synchronized (contactCache) { if (contactCache.containsKey(number)) return contactCache.get(number); }
        String[] r = {null, null};
        try (Cursor cu = c.getContentResolver().query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI}, null, null, null)) {
            if (cu != null && cu.moveToFirst()) { r[0] = cu.getString(0); r[1] = cu.getString(1); }
        } catch (Exception ignored) { }
        synchronized (contactCache) { contactCache.put(number, r); }
        return r;
    }

    static String nameOr(Context c, String number) {
        String n = contact(c, number)[0];
        return n != null ? n : number;
    }

    // ------------------------------------------------------------------ réception SMS
    static Uri storeIncomingSms(Context c, String from, String body, long sent, int subId) {
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.ADDRESS, from);
        v.put(Telephony.Sms.BODY, body);
        v.put(Telephony.Sms.DATE, System.currentTimeMillis());
        v.put(Telephony.Sms.DATE_SENT, sent);
        v.put(Telephony.Sms.READ, 0);
        v.put(Telephony.Sms.SEEN, 0);
        v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
        if (subId >= 0) v.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        try { return c.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, v); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ envoi SMS
    static void sendSms(Context c, String to, String body) {
        long thread = Telephony.Threads.getOrCreateThreadId(c, to);
        Uri uri = null;
        if (isDefault(c)) {
            ContentValues v = new ContentValues();
            v.put(Telephony.Sms.ADDRESS, to);
            v.put(Telephony.Sms.BODY, body);
            v.put(Telephony.Sms.DATE, System.currentTimeMillis());
            v.put(Telephony.Sms.READ, 1);
            v.put(Telephony.Sms.SEEN, 1);
            v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX);
            v.put(Telephony.Sms.THREAD_ID, thread);
            try { uri = c.getContentResolver().insert(Telephony.Sms.CONTENT_URI, v); } catch (Exception ignored) { }
        }
        SmsManager sm = sms(c);
        ArrayList<String> parts = sm.divideMessage(body);
        ArrayList<PendingIntent> sent = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            Intent si = new Intent(ACT_SMS_SENT, uri).setClass(c, SmsResultReceiver.class).putExtra("last", i == parts.size() - 1);
            sent.add(PendingIntent.getBroadcast(c, (int) (System.nanoTime() & 0xFFFFFF) + i, si, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT));
        }
        try {
            if (parts.size() > 1) sm.sendMultipartTextMessage(to, null, parts, sent, null);
            else sm.sendTextMessage(to, null, body, sent.get(0), null);
        } catch (Exception e) {
            markSms(c, uri, false);
        }
        changed();
    }

    static void markSms(Context c, Uri uri, boolean ok) {
        if (uri == null) return;
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.TYPE, ok ? Telephony.Sms.MESSAGE_TYPE_SENT : Telephony.Sms.MESSAGE_TYPE_FAILED);
        try { c.getContentResolver().update(uri, v, null, null); } catch (Exception ignored) { }
        changed();
    }

    // ------------------------------------------------------------------ MMS : taille max opérateur + préparation des pièces
    static int maxMmsSize(Context c) {
        try {
            CarrierConfigManager cm = c.getSystemService(CarrierConfigManager.class);
            Bundle b = cm == null ? null : cm.getConfig();
            int v = b == null ? 0 : b.getInt(CarrierConfigManager.KEY_MMS_MAX_MESSAGE_SIZE_INT, 0);
            if (v > 0) return v;
        } catch (Exception ignored) { }
        return 600 * 1024;
    }

    static String mimeOf(Context c, Uri u) {
        String m = null;
        try { m = c.getContentResolver().getType(u); } catch (Exception ignored) { }
        return m == null ? "application/octet-stream" : m;
    }

    static byte[] readAll(Context c, Uri u, int limit) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); if (limit > 0 && o.size() > limit) throw new IllegalStateException("trop lourd"); }
            return o.toByteArray();
        }
    }

    /** Photo réduite automatiquement pour tenir dans la limite MMS de l'opérateur. */
    static byte[] fitImage(Context c, Uri u, int budget) throws Exception {
        int[] dims = {2048, 1600, 1280, 1024, 800, 640, 480};
        int[] qs = {90, 82, 72, 62};
        for (int d : dims) {
            final int max = d;
            Bitmap bm = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), u), (dec, info, src) -> {
                Size s = info.getSize();
                int big = Math.max(s.getWidth(), s.getHeight());
                if (big > max) { float k = max / (float) big; dec.setTargetSize(Math.round(s.getWidth() * k), Math.round(s.getHeight() * k)); }
                dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            for (int q : qs) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, q, o);
                if (o.size() <= budget) { bm.recycle(); return o.toByteArray(); }
            }
            bm.recycle();
        }
        throw new IllegalStateException("photo trop lourde même réduite");
    }

    // ------------------------------------------------------------------ fichiers PDU partagés avec le service MMS du téléphone
    static Uri pduFile(Context c, byte[] content) throws Exception {
        File dir = new File(c.getCacheDir(), "mms");
        dir.mkdirs();
        File f = new File(dir, UUID.randomUUID().toString() + ".pdu");
        if (content != null) try (FileOutputStream o = new FileOutputStream(f)) { o.write(content); }
        else f.createNewFile();
        Uri u = new Uri.Builder().scheme("content").authority(MmsFileProvider.AUTH).path(f.getName()).build();
        for (String pkg : new String[]{"com.android.mms.service", "com.android.phone", "com.samsung.android.mms.service"}) {
            try { c.grantUriPermission(pkg, u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) { }
        }
        return u;
    }

    static File pduPath(Context c, Uri u) { return new File(new File(c.getCacheDir(), "mms"), u.getLastPathSegment()); }

    // ------------------------------------------------------------------ envoi MMS
    static void sendMms(Context c, List<String> to, String text, List<MmsPdu.Part> files) throws Exception {
        List<MmsPdu.Part> parts = new ArrayList<>();
        if (text != null && !text.trim().isEmpty()) {
            MmsPdu.Part t = new MmsPdu.Part("text/plain", "text_0.txt", text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            t.text = text;
            parts.add(t);
        }
        parts.addAll(files);
        byte[] pdu = MmsPdu.buildSendReq(to, null, parts);
        Uri stored = isDefault(c) ? storeMms(c, Telephony.Mms.MESSAGE_BOX_OUTBOX, null, to, parts, System.currentTimeMillis() / 1000, true) : null;
        Uri file = pduFile(c, pdu);
        Intent si = new Intent(ACT_MMS_SENT).setClass(c, SmsResultReceiver.class).putExtra("mms", stored == null ? null : stored.toString()).putExtra("file", file.toString());
        int flags = PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(c, (int) (System.nanoTime() & 0xFFFFFF), si, flags);
        sms(c).sendMultimediaMessage(c, file, null, null, pi);
        changed();
    }

    static void markMms(Context c, String uri, boolean ok) {
        if (uri == null) return;
        ContentValues v = new ContentValues();
        v.put(Telephony.Mms.MESSAGE_BOX, ok ? Telephony.Mms.MESSAGE_BOX_SENT : Telephony.Mms.MESSAGE_BOX_FAILED);
        try { c.getContentResolver().update(Uri.parse(uri), v, null, null); } catch (Exception ignored) { }
        changed();
    }

    // ------------------------------------------------------------------ réception MMS
    static void downloadMms(Context c, byte[] notifPdu) {
        try {
            MmsPdu.Message n = MmsPdu.parse(notifPdu);
            if (n.contentLocation == null || n.contentLocation.isEmpty()) return;
            Uri file = pduFile(c, null);
            Intent di = new Intent(ACT_MMS_DOWNLOADED).setClass(c, SmsResultReceiver.class).putExtra("file", file.toString()).putExtra("from", n.from);
            int flags = PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(c, (int) (System.nanoTime() & 0xFFFFFF), di, flags);
            sms(c).downloadMultimediaMessage(c, n.contentLocation, file, null, pi);
        } catch (Exception ignored) { }
    }

    static void onMmsDownloaded(Context c, Uri file, String fallbackFrom) {
        try {
            File f = pduPath(c, file);
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            f.delete();
            MmsPdu.Message m = MmsPdu.parse(data);
            String from = m.from != null && !m.from.isEmpty() ? m.from : fallbackFrom;
            List<String> addrs = new ArrayList<>();
            addrs.add(from);
            long date = m.date > 0 ? m.date : System.currentTimeMillis() / 1000;
            Uri u = storeMms(c, Telephony.Mms.MESSAGE_BOX_INBOX, from, addrs, m.parts, date, false);
            String txt = "";
            String kind = "";
            for (MmsPdu.Part p : m.parts) {
                if (p.text != null && txt.isEmpty()) txt = p.text;
                else if (p.mime.startsWith("image/")) kind = "📷 Photo";
                else if (p.mime.startsWith("video/")) kind = "🎬 Vidéo";
                else if (!p.mime.equals("application/smil")) kind = "📎 Fichier";
            }
            notifyIncoming(c, from, (kind.isEmpty() ? "" : kind + (txt.isEmpty() ? "" : " · ")) + txt, u);
            changed();
        } catch (Exception ignored) { }
    }

    /** Écrit un MMS dans la base Android (boîte de réception / d'envoi). */
    static Uri storeMms(Context c, int box, String from, List<String> addrs, List<MmsPdu.Part> parts, long dateSec, boolean read) {
        ContentResolver cr = c.getContentResolver();
        try {
            long thread = Telephony.Threads.getOrCreateThreadId(c, new HashSet<>(addrs));
            ContentValues v = new ContentValues();
            v.put(Telephony.Mms.THREAD_ID, thread);
            v.put(Telephony.Mms.DATE, dateSec);
            v.put(Telephony.Mms.DATE_SENT, dateSec);
            v.put(Telephony.Mms.MESSAGE_BOX, box);
            v.put(Telephony.Mms.READ, read ? 1 : 0);
            v.put(Telephony.Mms.SEEN, read ? 1 : 0);
            v.put(Telephony.Mms.MESSAGE_TYPE, box == Telephony.Mms.MESSAGE_BOX_INBOX ? 132 : 128);
            v.put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related");
            v.put(Telephony.Mms.MMS_VERSION, 18);
            v.put(Telephony.Mms.TEXT_ONLY, 0);
            Uri m = cr.insert(Telephony.Mms.CONTENT_URI, v);
            if (m == null) return null;
            long id = Long.parseLong(m.getLastPathSegment());
            Uri partUri = Uri.parse("content://mms/" + id + "/part");
            for (MmsPdu.Part p : parts) {
                ContentValues pv = new ContentValues();
                pv.put(Telephony.Mms.Part.MSG_ID, id);
                pv.put(Telephony.Mms.Part.CONTENT_TYPE, p.mime);
                pv.put(Telephony.Mms.Part.NAME, p.name);
                pv.put(Telephony.Mms.Part.CONTENT_LOCATION, p.name);
                pv.put(Telephony.Mms.Part.CONTENT_ID, "<" + p.name + ">");
                if (p.text != null) { pv.put(Telephony.Mms.Part.CHARSET, 106); pv.put(Telephony.Mms.Part.TEXT, p.text); }
                Uri pu = cr.insert(partUri, pv);
                if (p.text == null && pu != null && p.data != null) {
                    try (OutputStream o = cr.openOutputStream(pu)) { o.write(p.data); }
                }
            }
            Uri addrUri = Uri.parse("content://mms/" + id + "/addr");
            if (from != null) {
                ContentValues av = new ContentValues();
                av.put(Telephony.Mms.Addr.ADDRESS, from);
                av.put(Telephony.Mms.Addr.TYPE, 137);
                av.put(Telephony.Mms.Addr.CHARSET, 106);
                cr.insert(addrUri, av);
            }
            for (String a : addrs) {
                if (a.equals(from)) continue;
                ContentValues av = new ContentValues();
                av.put(Telephony.Mms.Addr.ADDRESS, a);
                av.put(Telephony.Mms.Addr.TYPE, 151);
                av.put(Telephony.Mms.Addr.CHARSET, 106);
                cr.insert(addrUri, av);
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ notifications
    static void channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Messages PupSMS", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Nouveaux SMS et MMS");
            ch.enableLights(true);
            ch.setLightColor(0xFFFF7A29);
            nm.createNotificationChannel(ch);
        }
    }

    static void notifyIncoming(Context c, String from, String text, Uri msg) {
        try {
            channel(c);
            if (SmsActivity.openThreadAddr != null && SmsActivity.openThreadAddr.equals(from)) return; // déjà à l'écran
            int nid = from == null ? 1 : from.hashCode();
            String name = nameOr(c, from);
            Intent open = new Intent(c, SmsActivity.class).putExtra("addr", from).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent po = PendingIntent.getActivity(c, nid, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            RemoteInput ri = new RemoteInput.Builder(KEY_REPLY).setLabel("Répondre 🐾").build();
            Intent rep = new Intent(ACT_REPLY).setClass(c, SmsResultReceiver.class).putExtra("addr", from).putExtra("nid", nid);
            PendingIntent pr = PendingIntent.getBroadcast(c, nid, rep, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            Intent rd = new Intent(ACT_READ).setClass(c, SmsResultReceiver.class).putExtra("addr", from).putExtra("nid", nid);
            PendingIntent prd = PendingIntent.getBroadcast(c, nid + 1, rd, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, CH)
                    .setSmallIcon(R.drawable.ic_paw)
                    .setColor(0xFFFF7A29)
                    .setContentTitle(name)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(po)
                    .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_paw), "Répondre", pr).addRemoteInput(ri).build())
                    .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_paw), "Lu", prd).build())
                    .build();
            c.getSystemService(NotificationManager.class).notify(nid, n);
        } catch (Exception ignored) { }
    }

    static void markThreadRead(Context c, String addr) {
        try {
            long t = Telephony.Threads.getOrCreateThreadId(c, addr);
            ContentValues v = new ContentValues();
            v.put(Telephony.Sms.READ, 1);
            v.put(Telephony.Sms.SEEN, 1);
            c.getContentResolver().update(Telephony.Sms.CONTENT_URI, v, Telephony.Sms.THREAD_ID + "=? AND " + Telephony.Sms.READ + "=0", new String[]{String.valueOf(t)});
            ContentValues mv = new ContentValues();
            mv.put(Telephony.Mms.READ, 1);
            mv.put(Telephony.Mms.SEEN, 1);
            c.getContentResolver().update(Telephony.Mms.CONTENT_URI, mv, Telephony.Mms.THREAD_ID + "=? AND " + Telephony.Mms.READ + "=0", new String[]{String.valueOf(t)});
            c.getSystemService(NotificationManager.class).cancel(addr == null ? 1 : addr.hashCode());
        } catch (Exception ignored) { }
    }

    static List<String> list(String... a) { return new ArrayList<>(Arrays.asList(a)); }
}
